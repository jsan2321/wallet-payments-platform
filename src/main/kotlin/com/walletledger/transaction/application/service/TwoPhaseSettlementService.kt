package com.walletledger.transaction.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.account.application.port.input.PlaceHoldCommand
import com.walletledger.account.application.port.input.PlaceHoldUseCase
import com.walletledger.account.application.port.input.ReleaseHoldCommand
import com.walletledger.account.application.port.input.ReleaseHoldUseCase
import com.walletledger.account.application.port.input.UpdateWalletBalanceUseCase
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.application.port.input.PostingLegCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryUseCase
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.PostingDirection
import com.walletledger.transaction.application.port.input.AuthorizeHoldCommand
import com.walletledger.transaction.application.port.input.AuthorizeHoldUseCase
import com.walletledger.transaction.application.port.input.CaptureHoldCommand
import com.walletledger.transaction.application.port.input.CaptureHoldUseCase
import com.walletledger.transaction.application.port.input.HoldResult
import com.walletledger.transaction.application.port.input.TransactionResult
import com.walletledger.transaction.application.port.input.VoidHoldCommand
import com.walletledger.transaction.application.port.input.VoidHoldUseCase
import com.walletledger.transaction.application.port.output.HoldRepositoryPort
import com.walletledger.transaction.application.port.output.TransactionEventPublisherPort
import com.walletledger.transaction.application.port.output.TransactionRepositoryPort
import com.walletledger.transaction.domain.error.TransactionDomainError
import com.walletledger.transaction.domain.event.HoldCapturedDomainEvent
import com.walletledger.transaction.domain.event.HoldVoidedDomainEvent
import com.walletledger.transaction.domain.event.TransactionCompletedDomainEvent
import com.walletledger.transaction.domain.model.HoldRecord
import com.walletledger.transaction.domain.model.HoldStatus
import com.walletledger.transaction.domain.model.Transaction
import com.walletledger.transaction.domain.model.TransactionStatus
import com.walletledger.transaction.domain.model.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TwoPhaseSettlementService(
    private val holdRepository: HoldRepositoryPort,
    private val placeHoldUseCase: PlaceHoldUseCase,
    private val releaseHoldUseCase: ReleaseHoldUseCase,
    private val updateWalletBalanceUseCase: UpdateWalletBalanceUseCase,
    private val recordJournalEntryUseCase: RecordJournalEntryUseCase,
    private val transactionRepository: TransactionRepositoryPort,
    private val eventPublisher: TransactionEventPublisherPort
) : AuthorizeHoldUseCase,
    CaptureHoldUseCase,
    VoidHoldUseCase {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun authorizeHold(command: AuthorizeHoldCommand): Either<TransactionDomainError, HoldResult> {
        // 1. Shift funds into held status in account module
        val placeResult = placeHoldUseCase.placeHold(
            PlaceHoldCommand(
                walletId = command.walletId,
                currency = command.amount.currency,
                amount = command.amount
            )
        )
        when (placeResult) {
            is Either.Left -> return TransactionDomainError.ExecutionFailed(placeResult.value.toString()).left()
            is Either.Right -> {}
        }

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.HOLD_AUTHORIZE,
            status = TransactionStatus.COMPLETED,
            sourceWalletId = command.walletId,
            amount = command.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        val hold = HoldRecord(
            walletId = command.walletId,
            transactionId = tx.id,
            amount = command.amount,
            status = HoldStatus.HELD,
            expiresAt = Instant.now().plusSeconds(command.durationSeconds)
        )
        val savedHold = holdRepository.save(hold)

        return savedHold.toResult().right()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun captureHold(command: CaptureHoldCommand): Either<TransactionDomainError, TransactionResult> {
        val hold = holdRepository.findById(command.holdId)
            ?: return TransactionDomainError.HoldNotFound(command.holdId).left()

        if (hold.status != HoldStatus.HELD) {
            return TransactionDomainError.HoldAlreadySettled(command.holdId, hold.status).left()
        }
        if (hold.isExpired()) {
            return TransactionDomainError.HoldExpired(command.holdId).left()
        }

        val buyerAccountEither = updateWalletBalanceUseCase.getSubAccount(hold.walletId, hold.amount.currency)
        val buyerAccount = when (buyerAccountEither) {
            is Either.Left -> return TransactionDomainError.ExecutionFailed("Buyer sub-account for ${hold.amount.currency} not found").left()
            is Either.Right -> buyerAccountEither.value
        }

        val sellerAccountEither = updateWalletBalanceUseCase.getSubAccount(command.destinationWalletId, hold.amount.currency)
        val sellerAccount = when (sellerAccountEither) {
            is Either.Left -> return TransactionDomainError.ExecutionFailed("Seller sub-account for ${hold.amount.currency} not found").left()
            is Either.Right -> sellerAccountEither.value
        }

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.HOLD_CAPTURE,
            status = TransactionStatus.PENDING,
            sourceWalletId = hold.walletId,
            destinationWalletId = command.destinationWalletId,
            amount = hold.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        // 1. Double-entry transfer: Debit Buyer (Liability), Credit Seller (Liability)
        val journalResult = recordJournalEntryUseCase.recordJournalEntry(
            RecordJournalEntryCommand(
                transactionId = tx.id,
                description = command.description,
                entryType = EntryType.SETTLEMENT,
                legs = listOf(
                    PostingLegCommand(accountId = buyerAccount.ledgerAccountId, direction = PostingDirection.DEBIT, amount = hold.amount),
                    PostingLegCommand(accountId = sellerAccount.ledgerAccountId, direction = PostingDirection.CREDIT, amount = hold.amount)
                )
            )
        )

        when (journalResult) {
            is Either.Left -> {
                val failedTx = tx.fail(journalResult.value.toString())
                transactionRepository.save(failedTx)
                return TransactionDomainError.ExecutionFailed("Ledger recording failed: ${journalResult.value}").left()
            }
            is Either.Right -> {}
        }

        // 2. Decrement Buyer's held balance and increment Seller's available balance
        updateWalletBalanceUseCase.settleHeldBalance(hold.walletId, command.destinationWalletId, hold.amount)

        // 3. Update hold status
        val capturedHold = hold.capture()
        holdRepository.save(capturedHold)

        val completedTx = tx.complete()
        transactionRepository.save(completedTx)

        eventPublisher.publish(
            HoldCapturedDomainEvent(
                holdId = capturedHold.id,
                walletId = capturedHold.walletId,
                transactionId = completedTx.id,
                amount = capturedHold.amount
            )
        )
        eventPublisher.publish(
            TransactionCompletedDomainEvent(
                transactionId = completedTx.id,
                transactionType = completedTx.transactionType,
                sourceWalletId = completedTx.sourceWalletId,
                destinationWalletId = completedTx.destinationWalletId,
                amount = completedTx.amount,
                fee = completedTx.fee
            )
        )

        return completedTx.toResult().right()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun voidHold(command: VoidHoldCommand): Either<TransactionDomainError, HoldResult> {
        val hold = holdRepository.findById(command.holdId)
            ?: return TransactionDomainError.HoldNotFound(command.holdId).left()

        if (hold.status != HoldStatus.HELD) {
            return TransactionDomainError.HoldAlreadySettled(command.holdId, hold.status).left()
        }

        // 1. Release hold in account module, returning funds to available balance
        val releaseResult = releaseHoldUseCase.releaseHold(
            ReleaseHoldCommand(
                walletId = hold.walletId,
                currency = hold.amount.currency,
                amount = hold.amount
            )
        )
        when (releaseResult) {
            is Either.Left -> return TransactionDomainError.ExecutionFailed(releaseResult.value.toString()).left()
            is Either.Right -> {}
        }

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.HOLD_VOID,
            status = TransactionStatus.COMPLETED,
            sourceWalletId = hold.walletId,
            amount = hold.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        val voidedHold = hold.voidHold()
        val savedHold = holdRepository.save(voidedHold)

        eventPublisher.publish(
            HoldVoidedDomainEvent(
                holdId = voidedHold.id,
                walletId = voidedHold.walletId,
                transactionId = tx.id,
                amount = voidedHold.amount
            )
        )

        return savedHold.toResult().right()
    }

    private fun HoldRecord.toResult(): HoldResult = HoldResult(
        holdId = id,
        walletId = walletId,
        transactionId = transactionId,
        amount = amount,
        status = status,
        expiresAt = expiresAt,
        createdAt = createdAt
    )

    private fun Transaction.toResult(): TransactionResult = TransactionResult(
        id = id,
        idempotencyKey = idempotencyKey,
        transactionType = transactionType,
        status = status,
        sourceWalletId = sourceWalletId,
        destinationWalletId = destinationWalletId,
        amount = amount,
        fee = fee,
        description = description,
        createdAt = createdAt
    )
}
