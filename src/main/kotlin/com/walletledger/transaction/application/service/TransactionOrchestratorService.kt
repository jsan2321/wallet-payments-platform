package com.walletledger.transaction.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.account.application.port.input.CheckSpendLimitUseCase
import com.walletledger.account.application.port.input.UpdateWalletBalanceUseCase
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.application.port.input.CreateLedgerAccountCommand
import com.walletledger.ledger.application.port.input.CreateLedgerAccountUseCase
import com.walletledger.ledger.application.port.input.GetAccountBalanceUseCase
import com.walletledger.ledger.application.port.input.PostingLegCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryUseCase
import com.walletledger.ledger.domain.model.AccountType
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.PostingDirection
import com.walletledger.transaction.application.port.input.DepositCommand
import com.walletledger.transaction.application.port.input.DepositFundsUseCase
import com.walletledger.transaction.application.port.input.SplitPaymentCommand
import com.walletledger.transaction.application.port.input.SplitPaymentUseCase
import com.walletledger.transaction.application.port.input.TransactionResult
import com.walletledger.transaction.application.port.input.TransferCommand
import com.walletledger.transaction.application.port.input.TransferFundsUseCase
import com.walletledger.transaction.application.port.input.WithdrawCommand
import com.walletledger.transaction.application.port.input.WithdrawFundsUseCase
import com.walletledger.transaction.application.port.output.IdempotencyRepositoryPort
import com.walletledger.transaction.application.port.output.TransactionEventPublisherPort
import com.walletledger.transaction.application.port.output.TransactionRepositoryPort
import com.walletledger.transaction.domain.error.TransactionDomainError
import com.walletledger.transaction.domain.event.TransactionCompletedDomainEvent
import com.walletledger.transaction.domain.model.IdempotencyRecord
import com.walletledger.transaction.domain.model.IdempotencyStatus
import com.walletledger.transaction.domain.model.Transaction
import com.walletledger.transaction.domain.model.TransactionStatus
import com.walletledger.transaction.domain.model.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class TransactionOrchestratorService(
    private val transactionRepository: TransactionRepositoryPort,
    private val idempotencyRepository: IdempotencyRepositoryPort,
    private val recordJournalEntryUseCase: RecordJournalEntryUseCase,
    private val updateWalletBalanceUseCase: UpdateWalletBalanceUseCase,
    private val checkSpendLimitUseCase: CheckSpendLimitUseCase,
    private val createLedgerAccountUseCase: CreateLedgerAccountUseCase,
    private val getAccountBalanceUseCase: GetAccountBalanceUseCase,
    private val eventPublisher: TransactionEventPublisherPort
) : DepositFundsUseCase,
    WithdrawFundsUseCase,
    TransferFundsUseCase,
    SplitPaymentUseCase {

    // ========================================================================
    // DEPOSIT
    // ========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun deposit(command: DepositCommand): Either<TransactionDomainError, TransactionResult> {
        val payloadHash = hashPayload("DEPOSIT:${command.destinationWalletId}:${command.amount.amountMinorUnits}:${command.amount.currency}")
        val lockResult = acquireLockOrResolveExisting(command.idempotencyKey, payloadHash)
        when (lockResult) {
            is Either.Left -> return lockResult.value.left()
            is Either.Right -> if (lockResult.value != null) return lockResult.value!!.right()
        }

        val destAccountEither = updateWalletBalanceUseCase.getSubAccount(command.destinationWalletId, command.amount.currency)
        val destAccount = when (destAccountEither) {
            is Either.Left -> return TransactionDomainError.ExecutionFailed("Destination wallet sub-account for ${command.amount.currency} not found").left()
            is Either.Right -> destAccountEither.value
        }

        val gatewayLedgerAccountId = getOrCreateSystemAccount("SYS_GATEWAY_CLEARING_${command.amount.currency.value}", AccountType.ASSET, command.amount.currency)

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.DEPOSIT,
            status = TransactionStatus.PENDING,
            destinationWalletId = command.destinationWalletId,
            amount = command.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        // Post balanced double-entry: Debit Gateway Clearing (Asset), Credit Customer Wallet (Liability)
        val journalResult = recordJournalEntryUseCase.recordJournalEntry(
            RecordJournalEntryCommand(
                transactionId = tx.id,
                description = command.description,
                entryType = EntryType.DEPOSIT,
                legs = listOf(
                    PostingLegCommand(accountId = gatewayLedgerAccountId, direction = PostingDirection.DEBIT, amount = command.amount),
                    PostingLegCommand(accountId = destAccount.ledgerAccountId, direction = PostingDirection.CREDIT, amount = command.amount)
                )
            )
        )

        when (journalResult) {
            is Either.Left -> {
                val failedTx = tx.fail(journalResult.value.toString())
                transactionRepository.save(failedTx)
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Ledger recording failed: ${journalResult.value}").left()
            }
            is Either.Right -> {}
        }

        // Update Wallet sub-account projection
        updateWalletBalanceUseCase.creditBalance(command.destinationWalletId, command.amount)

        val completedTx = tx.complete()
        transactionRepository.save(completedTx)
        markIdempotencyCompleted(command.idempotencyKey, payloadHash)

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

    // ========================================================================
    // WITHDRAWAL
    // ========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun withdraw(command: WithdrawCommand): Either<TransactionDomainError, TransactionResult> {
        val payloadHash = hashPayload("WITHDRAW:${command.sourceWalletId}:${command.amount.amountMinorUnits}:${command.amount.currency}")
        val lockResult = acquireLockOrResolveExisting(command.idempotencyKey, payloadHash)
        when (lockResult) {
            is Either.Left -> return lockResult.value.left()
            is Either.Right -> if (lockResult.value != null) return lockResult.value!!.right()
        }

        val sourceAccountEither = updateWalletBalanceUseCase.getSubAccount(command.sourceWalletId, command.amount.currency)
        val sourceAccount = when (sourceAccountEither) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Source wallet sub-account for ${command.amount.currency} not found").left()
            }
            is Either.Right -> sourceAccountEither.value
        }

        if (sourceAccount.availableBalance.amountMinorUnits < command.amount.amountMinorUnits) {
            markIdempotencyFailed(command.idempotencyKey, payloadHash)
            return TransactionDomainError.InsufficientFunds(command.sourceWalletId, "Insufficient available funds for withdrawal").left()
        }

        val spendLimitResult = checkSpendLimitUseCase.checkAndAccumulateSpend(command.sourceWalletId, command.amount)
        when (spendLimitResult) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed(spendLimitResult.value.toString()).left()
            }
            is Either.Right -> {}
        }

        val payoutLedgerAccountId = getOrCreateSystemAccount("SYS_PAYOUT_CLEARING_${command.amount.currency.value}", AccountType.ASSET, command.amount.currency)

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.WITHDRAWAL,
            status = TransactionStatus.PENDING,
            sourceWalletId = command.sourceWalletId,
            amount = command.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        // Post balanced double-entry: Debit Customer Wallet (Liability), Credit Payout Clearing (Asset)
        val journalResult = recordJournalEntryUseCase.recordJournalEntry(
            RecordJournalEntryCommand(
                transactionId = tx.id,
                description = command.description,
                entryType = EntryType.WITHDRAWAL,
                legs = listOf(
                    PostingLegCommand(accountId = sourceAccount.ledgerAccountId, direction = PostingDirection.DEBIT, amount = command.amount),
                    PostingLegCommand(accountId = payoutLedgerAccountId, direction = PostingDirection.CREDIT, amount = command.amount)
                )
            )
        )

        when (journalResult) {
            is Either.Left -> {
                val failedTx = tx.fail(journalResult.value.toString())
                transactionRepository.save(failedTx)
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Ledger recording failed: ${journalResult.value}").left()
            }
            is Either.Right -> {}
        }

        updateWalletBalanceUseCase.debitBalance(command.sourceWalletId, command.amount)

        val completedTx = tx.complete()
        transactionRepository.save(completedTx)
        markIdempotencyCompleted(command.idempotencyKey, payloadHash)

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

    // ========================================================================
    // P2P TRANSFER
    // ========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun transfer(command: TransferCommand): Either<TransactionDomainError, TransactionResult> {
        val payloadHash = hashPayload("TRANSFER:${command.sourceWalletId}:${command.destinationWalletId}:${command.amount.amountMinorUnits}:${command.amount.currency}")
        val lockResult = acquireLockOrResolveExisting(command.idempotencyKey, payloadHash)
        when (lockResult) {
            is Either.Left -> return lockResult.value.left()
            is Either.Right -> if (lockResult.value != null) return lockResult.value!!.right()
        }

        val sourceAccountEither = updateWalletBalanceUseCase.getSubAccount(command.sourceWalletId, command.amount.currency)
        val sourceAccount = when (sourceAccountEither) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Source wallet sub-account for ${command.amount.currency} not found").left()
            }
            is Either.Right -> sourceAccountEither.value
        }

        val destAccountEither = updateWalletBalanceUseCase.getSubAccount(command.destinationWalletId, command.amount.currency)
        val destAccount = when (destAccountEither) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Destination wallet sub-account for ${command.amount.currency} not found").left()
            }
            is Either.Right -> destAccountEither.value
        }

        if (sourceAccount.availableBalance.amountMinorUnits < command.amount.amountMinorUnits) {
            markIdempotencyFailed(command.idempotencyKey, payloadHash)
            return TransactionDomainError.InsufficientFunds(command.sourceWalletId, "Insufficient available funds for transfer").left()
        }

        val spendLimitResult = checkSpendLimitUseCase.checkAndAccumulateSpend(command.sourceWalletId, command.amount)
        when (spendLimitResult) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed(spendLimitResult.value.toString()).left()
            }
            is Either.Right -> {}
        }

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.TRANSFER,
            status = TransactionStatus.PENDING,
            sourceWalletId = command.sourceWalletId,
            destinationWalletId = command.destinationWalletId,
            amount = command.amount,
            description = command.description
        )
        transactionRepository.save(tx)

        // Post balanced double-entry: Debit Sender Wallet (Liability), Credit Receiver Wallet (Liability)
        val journalResult = recordJournalEntryUseCase.recordJournalEntry(
            RecordJournalEntryCommand(
                transactionId = tx.id,
                description = command.description,
                entryType = EntryType.TRANSFER,
                legs = listOf(
                    PostingLegCommand(accountId = sourceAccount.ledgerAccountId, direction = PostingDirection.DEBIT, amount = command.amount),
                    PostingLegCommand(accountId = destAccount.ledgerAccountId, direction = PostingDirection.CREDIT, amount = command.amount)
                )
            )
        )

        when (journalResult) {
            is Either.Left -> {
                val failedTx = tx.fail(journalResult.value.toString())
                transactionRepository.save(failedTx)
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Ledger recording failed: ${journalResult.value}").left()
            }
            is Either.Right -> {}
        }

        // Update Wallet sub-accounts
        updateWalletBalanceUseCase.debitBalance(command.sourceWalletId, command.amount)
        updateWalletBalanceUseCase.creditBalance(command.destinationWalletId, command.amount)

        val completedTx = tx.complete()
        transactionRepository.save(completedTx)
        markIdempotencyCompleted(command.idempotencyKey, payloadHash)

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

    // ========================================================================
    // SPLIT PAYMENT (With Platform Fee)
    // ========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun splitPayment(command: SplitPaymentCommand): Either<TransactionDomainError, TransactionResult> {
        val payloadHash = hashPayload("SPLIT:${command.sourceWalletId}:${command.destinationWalletId}:${command.totalAmount.amountMinorUnits}:${command.platformFee.amountMinorUnits}:${command.totalAmount.currency}")
        val lockResult = acquireLockOrResolveExisting(command.idempotencyKey, payloadHash)
        when (lockResult) {
            is Either.Left -> return lockResult.value.left()
            is Either.Right -> if (lockResult.value != null) return lockResult.value!!.right()
        }

        val sourceAccountEither = updateWalletBalanceUseCase.getSubAccount(command.sourceWalletId, command.totalAmount.currency)
        val sourceAccount = when (sourceAccountEither) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Source wallet sub-account for ${command.totalAmount.currency} not found").left()
            }
            is Either.Right -> sourceAccountEither.value
        }

        val destAccountEither = updateWalletBalanceUseCase.getSubAccount(command.destinationWalletId, command.totalAmount.currency)
        val destAccount = when (destAccountEither) {
            is Either.Left -> {
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Destination wallet sub-account for ${command.totalAmount.currency} not found").left()
            }
            is Either.Right -> destAccountEither.value
        }

        if (sourceAccount.availableBalance.amountMinorUnits < command.totalAmount.amountMinorUnits) {
            markIdempotencyFailed(command.idempotencyKey, payloadHash)
            return TransactionDomainError.InsufficientFunds(command.sourceWalletId, "Insufficient available funds for payment").left()
        }

        val merchantNetAmountMinor = command.totalAmount.amountMinorUnits - command.platformFee.amountMinorUnits
        if (merchantNetAmountMinor <= 0) {
            markIdempotencyFailed(command.idempotencyKey, payloadHash)
            return TransactionDomainError.ExecutionFailed("Merchant net amount must be strictly positive").left()
        }
        val merchantNetAmount = Money.ofMinor(merchantNetAmountMinor, command.totalAmount.currency)

        val feeLedgerAccountId = getOrCreateSystemAccount("SYS_PLATFORM_REVENUE_${command.totalAmount.currency.value}", AccountType.REVENUE, command.totalAmount.currency)

        val tx = Transaction(
            id = TransactionId.generate(),
            idempotencyKey = command.idempotencyKey,
            transactionType = TransactionType.SPLIT_PAYMENT,
            status = TransactionStatus.PENDING,
            sourceWalletId = command.sourceWalletId,
            destinationWalletId = command.destinationWalletId,
            amount = command.totalAmount,
            fee = command.platformFee,
            description = command.description
        )
        transactionRepository.save(tx)

        // Post balanced 3-leg double-entry:
        // Debit Buyer (Liability): $100
        // Credit Merchant (Liability): $95
        // Credit Platform Fee (Revenue): $5
        // Total Debit ($100) == Total Credit ($95 + $5 = $100)
        val journalResult = recordJournalEntryUseCase.recordJournalEntry(
            RecordJournalEntryCommand(
                transactionId = tx.id,
                description = command.description,
                entryType = EntryType.TRANSFER,
                legs = listOf(
                    PostingLegCommand(accountId = sourceAccount.ledgerAccountId, direction = PostingDirection.DEBIT, amount = command.totalAmount),
                    PostingLegCommand(accountId = destAccount.ledgerAccountId, direction = PostingDirection.CREDIT, amount = merchantNetAmount),
                    PostingLegCommand(accountId = feeLedgerAccountId, direction = PostingDirection.CREDIT, amount = command.platformFee)
                )
            )
        )

        when (journalResult) {
            is Either.Left -> {
                val failedTx = tx.fail(journalResult.value.toString())
                transactionRepository.save(failedTx)
                markIdempotencyFailed(command.idempotencyKey, payloadHash)
                return TransactionDomainError.ExecutionFailed("Ledger recording failed: ${journalResult.value}").left()
            }
            is Either.Right -> {}
        }

        // Update Wallet sub-accounts
        updateWalletBalanceUseCase.debitBalance(command.sourceWalletId, command.totalAmount)
        updateWalletBalanceUseCase.creditBalance(command.destinationWalletId, merchantNetAmount)

        val completedTx = tx.complete()
        transactionRepository.save(completedTx)
        markIdempotencyCompleted(command.idempotencyKey, payloadHash)

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

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun acquireLockOrResolveExisting(key: IdempotencyKey, payloadHash: String): Either<TransactionDomainError, TransactionResult?> {
        val acquired = idempotencyRepository.tryAcquireLock(key, payloadHash)
        if (acquired) {
            return null.right() // Lock acquired, proceed with transaction execution
        }

        // Lock already exists, wait and resolve existing record
        for (i in 1..40) {
            val record = idempotencyRepository.findByKey(key)
            if (record != null) {
                if (record.requestHash != payloadHash) {
                    return TransactionDomainError.IdempotencyPayloadMismatch(key).left()
                }
                if (record.status == IdempotencyStatus.COMPLETED) {
                    val existingTx = transactionRepository.findByIdempotencyKey(key)
                    if (existingTx != null) {
                        return existingTx.toResult().right()
                    }
                }
            }
            Thread.sleep(50)
        }

        return TransactionDomainError.IdempotencyKeyInFlight(key).left()
    }

    private fun markIdempotencyCompleted(key: IdempotencyKey, payloadHash: String) {
        val record = IdempotencyRecord(
            idempotencyKey = key,
            requestHash = payloadHash,
            status = IdempotencyStatus.COMPLETED
        )
        idempotencyRepository.save(record)
    }

    private fun markIdempotencyFailed(key: IdempotencyKey, payloadHash: String) {
        val record = IdempotencyRecord(
            idempotencyKey = key,
            requestHash = payloadHash,
            status = IdempotencyStatus.FAILED
        )
        idempotencyRepository.save(record)
    }

    private fun getOrCreateSystemAccount(accountNumber: String, accountType: AccountType, currency: CurrencyCode): AccountId {
        val existing = getAccountBalanceUseCase.getAccountByNumber(accountNumber)
        if (existing is Either.Right) {
            return existing.value.id
        }

        val newId = AccountId.generate()
        createLedgerAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = newId,
                accountNumber = accountNumber,
                accountType = accountType,
                currency = currency,
                initialBalanceMinor = 0L
            )
        )
        return newId
    }

    private fun hashPayload(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(payload.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

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
