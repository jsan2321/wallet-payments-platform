package com.walletledger.ledger.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.ledger.domain.error.LedgerDomainError
import java.time.Instant

/**
 * Aggregate Root representing an authoritative double-entry general ledger account.
 */
data class LedgerAccount(
    val id: AccountId,
    val accountNumber: String,
    val accountType: AccountType,
    val currency: CurrencyCode,
    val balance: Money,
    val status: AccountStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require(accountNumber.isNotBlank()) { "Account number cannot be blank" }
        require(balance.currency == currency) { "Account balance currency must match account currency" }
    }

    /**
     * Applies an atomic posting leg to this account, updating its running balance according
     * to its accounting normal balance rules.
     */
    fun applyPosting(direction: PostingDirection, amount: Money): Either<LedgerDomainError, LedgerAccount> {
        if (status != AccountStatus.ACTIVE) {
            return LedgerDomainError.AccountNotActive(id, status).left()
        }
        if (amount.currency != currency) {
            return LedgerDomainError.AccountCurrencyMismatch(
                accountId = id,
                accountCurrency = currency,
                postingCurrency = amount.currency
            ).left()
        }

        val delta = accountType.calculateBalanceDelta(direction, amount.amountMinorUnits)
        val newBalanceMinor = balance.amountMinorUnits + delta

        // Disallow negative balances on standard liability/customer accounts unless credit limit configured
        if (accountType == AccountType.LIABILITY && newBalanceMinor < 0) {
            return LedgerDomainError.InsufficientBalance(
                accountId = id,
                currentBalance = balance.amountMinorUnits,
                requestedDelta = delta
            ).left()
        }

        return copy(
            balance = Money.ofMinor(newBalanceMinor, currency),
            updatedAt = Instant.now(),
            version = version + 1
        ).right()
    }

    /**
     * Freezes the account, preventing further postings.
     */
    fun freeze(): Either<LedgerDomainError, LedgerAccount> {
        if (status == AccountStatus.CLOSED) {
            return LedgerDomainError.AccountNotActive(id, status).left()
        }
        return copy(status = AccountStatus.FROZEN, updatedAt = Instant.now(), version = version + 1).right()
    }

    /**
     * Unfreezes the account back to ACTIVE status.
     */
    fun unfreeze(): Either<LedgerDomainError, LedgerAccount> {
        if (status != AccountStatus.FROZEN) {
            return LedgerDomainError.AccountNotActive(id, status).left()
        }
        return copy(status = AccountStatus.ACTIVE, updatedAt = Instant.now(), version = version + 1).right()
    }

    companion object {
        fun create(
            id: AccountId = AccountId.generate(),
            accountNumber: String,
            accountType: AccountType,
            currency: CurrencyCode,
            initialBalanceMinor: Long = 0L
        ): LedgerAccount {
            val now = Instant.now()
            return LedgerAccount(
                id = id,
                accountNumber = accountNumber,
                accountType = accountType,
                currency = currency,
                balance = Money.ofMinor(initialBalanceMinor, currency),
                status = AccountStatus.ACTIVE,
                version = 0L,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
