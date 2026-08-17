package com.walletledger.account.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.account.application.port.input.AddCurrencyAccountCommand
import com.walletledger.account.application.port.input.AddCurrencyAccountUseCase
import com.walletledger.account.application.port.input.CheckSpendLimitUseCase
import com.walletledger.account.application.port.input.CreateWalletCommand
import com.walletledger.account.application.port.input.CreateWalletUseCase
import com.walletledger.account.application.port.input.GetWalletUseCase
import com.walletledger.account.application.port.input.PlaceHoldCommand
import com.walletledger.account.application.port.input.PlaceHoldUseCase
import com.walletledger.account.application.port.input.ReleaseHoldCommand
import com.walletledger.account.application.port.input.ReleaseHoldUseCase
import com.walletledger.account.application.port.input.UpdateWalletBalanceUseCase
import com.walletledger.account.application.port.input.WalletAccountResult
import com.walletledger.account.application.port.input.WalletResult
import com.walletledger.account.application.port.output.SpendAccumulatorRepositoryPort
import com.walletledger.account.application.port.output.WalletAccountRepositoryPort
import com.walletledger.account.application.port.output.WalletEventPublisherPort
import com.walletledger.account.application.port.output.WalletRepositoryPort
import com.walletledger.account.domain.error.AccountDomainError
import com.walletledger.account.domain.event.HoldPlacedDomainEvent
import com.walletledger.account.domain.event.HoldReleasedDomainEvent
import com.walletledger.account.domain.event.WalletAccountAddedDomainEvent
import com.walletledger.account.domain.event.WalletCreatedDomainEvent
import com.walletledger.account.domain.model.Wallet
import com.walletledger.account.domain.model.WalletAccount
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import com.walletledger.ledger.application.port.input.CreateLedgerAccountCommand
import com.walletledger.ledger.application.port.input.CreateLedgerAccountUseCase
import com.walletledger.ledger.domain.model.AccountType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@Service
class WalletService(
    private val walletRepository: WalletRepositoryPort,
    private val walletAccountRepository: WalletAccountRepositoryPort,
    private val spendAccumulatorRepository: SpendAccumulatorRepositoryPort,
    private val createLedgerAccountUseCase: CreateLedgerAccountUseCase,
    private val eventPublisher: WalletEventPublisherPort
) : CreateWalletUseCase,
    AddCurrencyAccountUseCase,
    GetWalletUseCase,
    PlaceHoldUseCase,
    ReleaseHoldUseCase,
    UpdateWalletBalanceUseCase,
    CheckSpendLimitUseCase {

    @Transactional
    override fun createWallet(command: CreateWalletCommand): Either<AccountDomainError, WalletResult> {
        val existing = walletRepository.findByOwnerId(command.ownerId)
        if (existing != null) {
            return AccountDomainError.DuplicateWalletOwner(command.ownerId).left()
        }

        // 1. Create Wallet Aggregate Root
        val wallet = Wallet.create(
            id = command.id,
            ownerId = command.ownerId,
            tier = command.tier,
            customDailyLimitMinor = command.customDailyLimitMinor,
            customSingleTxLimitMinor = command.customSingleTxLimitMinor
        )

        // 2. Provision backing Double-Entry General Ledger Account in ledger module
        val ledgerAccountId = AccountId.generate()
        val ledgerAccountNumber = "WAL_${wallet.id.value.toString().take(8)}_${command.baseCurrency.value}"
        val ledgerAccountResult = createLedgerAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = ledgerAccountId,
                accountNumber = ledgerAccountNumber,
                accountType = AccountType.LIABILITY,
                currency = command.baseCurrency,
                initialBalanceMinor = 0L
            )
        )

        when (ledgerAccountResult) {
            is Either.Left -> return AccountDomainError.InvalidHoldOperation("Failed to provision ledger account: ${ledgerAccountResult.value}").left()
            is Either.Right -> {}
        }

        // 3. Create default base currency sub-account
        val baseAccount = WalletAccount.create(
            walletId = wallet.id,
            ledgerAccountId = ledgerAccountId,
            currency = command.baseCurrency
        )

        val updatedWalletEither = wallet.addAccount(baseAccount)
        val updatedWallet = when (updatedWalletEither) {
            is Either.Left -> return updatedWalletEither.value.left()
            is Either.Right -> updatedWalletEither.value
        }

        // 4. Persist wallet and sub-account
        walletRepository.save(updatedWallet)
        walletAccountRepository.save(baseAccount)

        // 5. Emit Domain Events
        eventPublisher.publish(
            WalletCreatedDomainEvent(
                walletId = wallet.id,
                ownerId = wallet.ownerId,
                tier = wallet.tier
            )
        )
        eventPublisher.publish(
            WalletAccountAddedDomainEvent(
                walletId = wallet.id,
                ledgerAccountId = baseAccount.ledgerAccountId,
                currency = baseAccount.currency
            )
        )

        return updatedWallet.toResult(listOf(baseAccount)).right()
    }

    @Transactional
    override fun addCurrencyAccount(command: AddCurrencyAccountCommand): Either<AccountDomainError, WalletAccountResult> {
        val wallet = walletRepository.findById(command.walletId)
            ?: return AccountDomainError.WalletNotFound(command.walletId).left()

        val existingAccount = walletAccountRepository.findByWalletIdAndCurrency(command.walletId, command.currency)
        if (existingAccount != null) {
            return AccountDomainError.CurrencyAccountAlreadyExists(command.walletId, command.currency).left()
        }

        // 1. Provision backing Double-Entry General Ledger Account in ledger module
        val ledgerAccountId = AccountId.generate()
        val ledgerAccountNumber = "WAL_${wallet.id.value.toString().take(8)}_${command.currency.value}"
        val ledgerAccountResult = createLedgerAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = ledgerAccountId,
                accountNumber = ledgerAccountNumber,
                accountType = AccountType.LIABILITY,
                currency = command.currency,
                initialBalanceMinor = 0L
            )
        )

        when (ledgerAccountResult) {
            is Either.Left -> return AccountDomainError.InvalidHoldOperation("Failed to provision ledger account: ${ledgerAccountResult.value}").left()
            is Either.Right -> {}
        }

        // 2. Create and persist new WalletAccount
        val subAccount = WalletAccount.create(
            walletId = wallet.id,
            ledgerAccountId = ledgerAccountId,
            currency = command.currency
        )

        val updatedWalletEither = wallet.addAccount(subAccount)
        when (updatedWalletEither) {
            is Either.Left -> return updatedWalletEither.value.left()
            is Either.Right -> walletRepository.save(updatedWalletEither.value)
        }

        walletAccountRepository.save(subAccount)

        eventPublisher.publish(
            WalletAccountAddedDomainEvent(
                walletId = wallet.id,
                ledgerAccountId = subAccount.ledgerAccountId,
                currency = subAccount.currency
            )
        )

        return subAccount.toResult().right()
    }

    @Transactional(readOnly = true)
    override fun getWalletById(walletId: WalletId): Either<AccountDomainError, WalletResult> {
        val wallet = walletRepository.findById(walletId)
            ?: return AccountDomainError.WalletNotFound(walletId).left()
        val accounts = walletAccountRepository.findByWalletId(walletId)
        return wallet.toResult(accounts).right()
    }

    @Transactional(readOnly = true)
    override fun getWalletByOwnerId(ownerId: OwnerId): Either<AccountDomainError, WalletResult> {
        val wallet = walletRepository.findByOwnerId(ownerId)
            ?: return AccountDomainError.DuplicateWalletOwner(ownerId).left()
        val accounts = walletAccountRepository.findByWalletId(wallet.id)
        return wallet.toResult(accounts).right()
    }

    @Transactional
    override fun placeHold(command: PlaceHoldCommand): Either<AccountDomainError, WalletAccountResult> {
        val subAccount = walletAccountRepository.findByWalletIdAndCurrency(command.walletId, command.currency)
            ?: return AccountDomainError.WalletAccountNotFound(command.walletId, command.currency).left()

        val holdEither = subAccount.placeHold(command.amount)
        val updatedAccount = when (holdEither) {
            is Either.Left -> return holdEither.value.left()
            is Either.Right -> holdEither.value
        }

        walletAccountRepository.save(updatedAccount)

        eventPublisher.publish(
            HoldPlacedDomainEvent(
                walletId = updatedAccount.walletId,
                currency = updatedAccount.currency,
                holdAmount = command.amount,
                remainingAvailable = updatedAccount.availableBalance
            )
        )

        return updatedAccount.toResult().right()
    }

    @Transactional
    override fun releaseHold(command: ReleaseHoldCommand): Either<AccountDomainError, WalletAccountResult> {
        val subAccount = walletAccountRepository.findByWalletIdAndCurrency(command.walletId, command.currency)
            ?: return AccountDomainError.WalletAccountNotFound(command.walletId, command.currency).left()

        val releaseEither = subAccount.releaseHold(command.amount)
        val updatedAccount = when (releaseEither) {
            is Either.Left -> return releaseEither.value.left()
            is Either.Right -> releaseEither.value
        }

        walletAccountRepository.save(updatedAccount)

        eventPublisher.publish(
            HoldReleasedDomainEvent(
                walletId = updatedAccount.walletId,
                currency = updatedAccount.currency,
                releaseAmount = command.amount,
                newAvailable = updatedAccount.availableBalance
            )
        )

        return updatedAccount.toResult().right()
    }

    @Transactional
    override fun checkAndAccumulateSpend(
        walletId: WalletId,
        amount: Money,
        date: LocalDate
    ): Either<AccountDomainError, Unit> {
        val wallet = walletRepository.findById(walletId)
            ?: return AccountDomainError.WalletNotFound(walletId).left()

        val currentSpend = spendAccumulatorRepository.getAccumulatedSpend(walletId, amount.currency, date)
        val validateResult = wallet.validateSpendLimits(amount, currentSpend, date)
        when (validateResult) {
            is Either.Left -> return validateResult.value.left()
            is Either.Right -> {}
        }

        spendAccumulatorRepository.addSpend(walletId, amount, date)
        return Unit.right()
    }

    @Transactional
    override fun creditBalance(walletId: WalletId, amount: Money): Either<AccountDomainError, WalletAccountResult> {
        val subAccount = walletAccountRepository.findByWalletIdAndCurrency(walletId, amount.currency)
            ?: return AccountDomainError.WalletAccountNotFound(walletId, amount.currency).left()

        val updated = subAccount.copy(
            availableBalance = Money.ofMinor(subAccount.availableBalance.amountMinorUnits + amount.amountMinorUnits, subAccount.currency),
            totalBalance = Money.ofMinor(subAccount.totalBalance.amountMinorUnits + amount.amountMinorUnits, subAccount.currency),
            version = subAccount.version + 1,
            updatedAt = Instant.now()
        )
        walletAccountRepository.save(updated)
        return updated.toResult().right()
    }

    @Transactional
    override fun debitBalance(walletId: WalletId, amount: Money): Either<AccountDomainError, WalletAccountResult> {
        val subAccount = walletAccountRepository.findByWalletIdAndCurrency(walletId, amount.currency)
            ?: return AccountDomainError.WalletAccountNotFound(walletId, amount.currency).left()

        if (subAccount.availableBalance.amountMinorUnits < amount.amountMinorUnits) {
            return AccountDomainError.InsufficientAvailableFunds(walletId, amount.currency, subAccount.availableBalance, amount).left()
        }

        val updated = subAccount.copy(
            availableBalance = Money.ofMinor(subAccount.availableBalance.amountMinorUnits - amount.amountMinorUnits, subAccount.currency),
            totalBalance = Money.ofMinor(subAccount.totalBalance.amountMinorUnits - amount.amountMinorUnits, subAccount.currency),
            version = subAccount.version + 1,
            updatedAt = Instant.now()
        )
        walletAccountRepository.save(updated)
        return updated.toResult().right()
    }

    @Transactional
    override fun settleHeldBalance(sourceWalletId: WalletId, destinationWalletId: WalletId, amount: Money): Either<AccountDomainError, Unit> {
        val sourceAccount = walletAccountRepository.findByWalletIdAndCurrency(sourceWalletId, amount.currency)
            ?: return AccountDomainError.WalletAccountNotFound(sourceWalletId, amount.currency).left()

        val destAccount = walletAccountRepository.findByWalletIdAndCurrency(destinationWalletId, amount.currency)
            ?: return AccountDomainError.WalletAccountNotFound(destinationWalletId, amount.currency).left()

        val updatedSource = sourceAccount.copy(
            heldBalance = Money.ofMinor(sourceAccount.heldBalance.amountMinorUnits - amount.amountMinorUnits, sourceAccount.currency),
            totalBalance = Money.ofMinor(sourceAccount.totalBalance.amountMinorUnits - amount.amountMinorUnits, sourceAccount.currency),
            version = sourceAccount.version + 1,
            updatedAt = Instant.now()
        )

        val updatedDest = destAccount.copy(
            availableBalance = Money.ofMinor(destAccount.availableBalance.amountMinorUnits + amount.amountMinorUnits, destAccount.currency),
            totalBalance = Money.ofMinor(destAccount.totalBalance.amountMinorUnits + amount.amountMinorUnits, destAccount.currency),
            version = destAccount.version + 1,
            updatedAt = Instant.now()
        )

        walletAccountRepository.saveAll(listOf(updatedSource, updatedDest))
        return Unit.right()
    }

    @Transactional(readOnly = true)
    override fun getSubAccount(walletId: WalletId, currency: CurrencyCode): Either<AccountDomainError, WalletAccountResult> {
        val subAccount = walletAccountRepository.findByWalletIdAndCurrency(walletId, currency)
            ?: return AccountDomainError.WalletAccountNotFound(walletId, currency).left()
        return subAccount.toResult().right()
    }

    private fun Wallet.toResult(accounts: List<WalletAccount>): WalletResult = WalletResult(
        id = id,
        ownerId = ownerId,
        status = status,
        tier = tier,
        dailySpendLimitMinor = dailySpendLimitMinor,
        singleTxLimitMinor = singleTxLimitMinor,
        accounts = accounts.map { it.toResult() },
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun WalletAccount.toResult(): WalletAccountResult = WalletAccountResult(
        id = id,
        walletId = walletId,
        ledgerAccountId = ledgerAccountId,
        currency = currency,
        availableBalance = availableBalance,
        heldBalance = heldBalance,
        totalBalance = totalBalance,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
