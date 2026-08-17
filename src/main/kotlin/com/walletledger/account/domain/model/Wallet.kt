package com.walletledger.account.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.account.domain.error.AccountDomainError
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Entity representing a currency-specific sub-account inside a customer wallet.
 * Enforces the strict financial balance invariant: totalBalance == availableBalance + heldBalance.
 */
data class WalletAccount(
    val id: UUID = UUID.randomUUID(),
    val walletId: WalletId,
    val ledgerAccountId: AccountId,
    val currency: CurrencyCode,
    val availableBalance: Money,
    val heldBalance: Money,
    val totalBalance: Money,
    val status: WalletAccountStatus = WalletAccountStatus.ACTIVE,
    val version: Long = 0L,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(availableBalance.currency == currency) { "Available balance currency must match sub-account currency" }
        require(heldBalance.currency == currency) { "Held balance currency must match sub-account currency" }
        require(totalBalance.currency == currency) { "Total balance currency must match sub-account currency" }
        require(availableBalance.amountMinorUnits >= 0) { "Available balance cannot be negative: ${availableBalance.amountMinorUnits}" }
        require(heldBalance.amountMinorUnits >= 0) { "Held balance cannot be negative: ${heldBalance.amountMinorUnits}" }
        require(availableBalance.amountMinorUnits + heldBalance.amountMinorUnits == totalBalance.amountMinorUnits) {
            "Balance invariant violated: ${availableBalance.amountMinorUnits} + ${heldBalance.amountMinorUnits} != ${totalBalance.amountMinorUnits}"
        }
    }

    /**
     * Places a hold on available funds, moving them into heldBalance while keeping totalBalance unchanged.
     */
    fun placeHold(amount: Money): Either<AccountDomainError, WalletAccount> {
        if (status != WalletAccountStatus.ACTIVE) {
            return AccountDomainError.WalletAccountNotActive(currency, status).left()
        }
        if (amount.amountMinorUnits <= 0) {
            return AccountDomainError.InvalidHoldOperation("Hold amount must be strictly positive").left()
        }
        if (availableBalance.amountMinorUnits < amount.amountMinorUnits) {
            return AccountDomainError.InsufficientAvailableFunds(
                walletId = walletId,
                currency = currency,
                availableBalance = availableBalance,
                requestedAmount = amount
            ).left()
        }

        val newAvailable = Money.ofMinor(availableBalance.amountMinorUnits - amount.amountMinorUnits, currency)
        val newHeld = Money.ofMinor(heldBalance.amountMinorUnits + amount.amountMinorUnits, currency)

        return copy(
            availableBalance = newAvailable,
            heldBalance = newHeld,
            version = version + 1,
            updatedAt = Instant.now()
        ).right()
    }

    /**
     * Releases previously held funds back into availableBalance.
     */
    fun releaseHold(amount: Money): Either<AccountDomainError, WalletAccount> {
        if (status != WalletAccountStatus.ACTIVE) {
            return AccountDomainError.WalletAccountNotActive(currency, status).left()
        }
        if (amount.amountMinorUnits <= 0) {
            return AccountDomainError.InvalidHoldOperation("Release hold amount must be strictly positive").left()
        }
        if (heldBalance.amountMinorUnits < amount.amountMinorUnits) {
            return AccountDomainError.InsufficientHeldFunds(
                walletId = walletId,
                currency = currency,
                heldBalance = heldBalance,
                requestedRelease = amount
            ).left()
        }

        val newAvailable = Money.ofMinor(availableBalance.amountMinorUnits + amount.amountMinorUnits, currency)
        val newHeld = Money.ofMinor(heldBalance.amountMinorUnits - amount.amountMinorUnits, currency)

        return copy(
            availableBalance = newAvailable,
            heldBalance = newHeld,
            version = version + 1,
            updatedAt = Instant.now()
        ).right()
    }

    companion object {
        fun create(
            walletId: WalletId,
            ledgerAccountId: AccountId,
            currency: CurrencyCode,
            initialAvailableMinor: Long = 0L,
            initialHeldMinor: Long = 0L
        ): WalletAccount {
            val totalMinor = initialAvailableMinor + initialHeldMinor
            val now = Instant.now()
            return WalletAccount(
                id = UUID.randomUUID(),
                walletId = walletId,
                ledgerAccountId = ledgerAccountId,
                currency = currency,
                availableBalance = Money.ofMinor(initialAvailableMinor, currency),
                heldBalance = Money.ofMinor(initialHeldMinor, currency),
                totalBalance = Money.ofMinor(totalMinor, currency),
                status = WalletAccountStatus.ACTIVE,
                version = 0L,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}

/**
 * Aggregate Root representing a customer wallet with multi-currency capabilities and spend velocity limits.
 */
data class Wallet(
    val id: WalletId,
    val ownerId: OwnerId,
    val status: WalletStatus,
    val tier: WalletTier,
    val dailySpendLimitMinor: Long,
    val singleTxLimitMinor: Long,
    val accounts: Map<CurrencyCode, WalletAccount> = emptyMap(),
    val version: Long = 0L,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {

    /**
     * Adds a new currency sub-account to this wallet.
     */
    fun addAccount(account: WalletAccount): Either<AccountDomainError, Wallet> {
        if (status != WalletStatus.ACTIVE) {
            return AccountDomainError.WalletNotActive(id, status).left()
        }
        if (accounts.containsKey(account.currency)) {
            return AccountDomainError.CurrencyAccountAlreadyExists(id, account.currency).left()
        }

        val updatedMap = accounts + (account.currency to account)
        return copy(
            accounts = updatedMap,
            version = version + 1,
            updatedAt = Instant.now()
        ).right()
    }

    /**
     * Retrieves the sub-account for the given currency.
     */
    fun getAccount(currency: CurrencyCode): Either<AccountDomainError, WalletAccount> {
        val acc = accounts[currency]
            ?: return AccountDomainError.WalletAccountNotFound(id, currency).left()
        return acc.right()
    }

    /**
     * Validates both single transaction and daily cumulative spend limits.
     */
    fun validateSpendLimits(
        amount: Money,
        currentDailySpend: Money,
        date: LocalDate = LocalDate.now()
    ): Either<AccountDomainError, Unit> {
        if (status != WalletStatus.ACTIVE) {
            return AccountDomainError.WalletNotActive(id, status).left()
        }

        val singleLimit = Money.ofMinor(singleTxLimitMinor, amount.currency)
        if (amount.amountMinorUnits > singleLimit.amountMinorUnits) {
            return AccountDomainError.SingleTransactionLimitExceeded(
                requestedAmount = amount,
                limit = singleLimit
            ).left()
        }

        val dailyLimit = Money.ofMinor(dailySpendLimitMinor, amount.currency)
        if (currentDailySpend.amountMinorUnits + amount.amountMinorUnits > dailyLimit.amountMinorUnits) {
            return AccountDomainError.DailySpendLimitExceeded(
                date = date,
                currentSpend = currentDailySpend,
                requestedAmount = amount,
                dailyLimit = dailyLimit
            ).left()
        }

        return Unit.right()
    }

    fun suspend(): Either<AccountDomainError, Wallet> {
        if (status == WalletStatus.CLOSED) {
            return AccountDomainError.WalletNotActive(id, status).left()
        }
        return copy(status = WalletStatus.SUSPENDED, version = version + 1, updatedAt = Instant.now()).right()
    }

    fun reactivate(): Either<AccountDomainError, Wallet> {
        if (status != WalletStatus.SUSPENDED) {
            return AccountDomainError.WalletNotActive(id, status).left()
        }
        return copy(status = WalletStatus.ACTIVE, version = version + 1, updatedAt = Instant.now()).right()
    }

    fun close(): Either<AccountDomainError, Wallet> {
        return copy(status = WalletStatus.CLOSED, version = version + 1, updatedAt = Instant.now()).right()
    }

    companion object {
        fun create(
            id: WalletId = WalletId.generate(),
            ownerId: OwnerId,
            tier: WalletTier = WalletTier.STANDARD,
            customDailyLimitMinor: Long? = null,
            customSingleTxLimitMinor: Long? = null
        ): Wallet {
            val now = Instant.now()
            return Wallet(
                id = id,
                ownerId = ownerId,
                status = WalletStatus.ACTIVE,
                tier = tier,
                dailySpendLimitMinor = customDailyLimitMinor ?: tier.defaultDailySpendLimitMinor,
                singleTxLimitMinor = customSingleTxLimitMinor ?: tier.defaultSingleTxLimitMinor,
                accounts = emptyMap(),
                version = 0L,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
