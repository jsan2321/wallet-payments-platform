package com.walletledger.ledger.domain.error

import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.ledger.domain.model.AccountStatus
import java.util.UUID

/**
 * Sealed hierarchy of typed domain errors in the ledger bounded context.
 */
sealed interface LedgerDomainError {

    /**
     * The journal entry violates the double-entry balancing invariant (sum of debits != sum of credits).
     */
    data class UnbalancedEntry(
        val totalDebits: Long,
        val totalCredits: Long,
        val currency: CurrencyCode
    ) : LedgerDomainError {
        val message: String = "Double-entry imbalance: total debits ($totalDebits) != total credits ($totalCredits) in $currency"
    }

    /**
     * Attempted posting contains zero legs.
     */
    data class EmptyPostings(
        val message: String = "A journal entry must contain at least two balanced posting legs"
    ) : LedgerDomainError

    /**
     * Account could not be found.
     */
    data class AccountNotFound(
        val accountId: AccountId
    ) : LedgerDomainError {
        val message: String = "Ledger account not found: $accountId"
    }

    /**
     * Account is not in ACTIVE state.
     */
    data class AccountNotActive(
        val accountId: AccountId,
        val status: AccountStatus
    ) : LedgerDomainError {
        val message: String = "Ledger account $accountId is not active (current status: $status)"
    }

    /**
     * Currency mismatch between posting leg and ledger account.
     */
    data class AccountCurrencyMismatch(
        val accountId: AccountId,
        val accountCurrency: CurrencyCode,
        val postingCurrency: CurrencyCode
    ) : LedgerDomainError {
        val message: String = "Currency mismatch for account $accountId: account currency $accountCurrency != posting currency $postingCurrency"
    }

    /**
     * Insufficient balance for an asset or liability account that disallows negative balances.
     */
    data class InsufficientBalance(
        val accountId: AccountId,
        val currentBalance: Long,
        val requestedDelta: Long
    ) : LedgerDomainError {
        val message: String = "Insufficient balance for account $accountId: current $currentBalance cannot accommodate delta $requestedDelta"
    }

    /**
     * Duplicate journal entry or transaction ID conflict.
     */
    data class DuplicateEntry(
        val transactionId: UUID
    ) : LedgerDomainError {
        val message: String = "Journal entry with transaction ID $transactionId already exists"
    }

    /**
     * General persistence or lock timeout failure.
     */
    data class ConcurrencyLockTimeout(
        val message: String
    ) : LedgerDomainError
}
