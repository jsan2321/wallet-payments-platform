package com.walletledger.ledger.domain.model

/**
 * Standard Double-Entry Account Types according to GAAP / IFRS accounting standards.
 */
enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    /**
     * Calculates the signed net balance delta produced by a debit or credit posting.
     * - ASSET / EXPENSE: Normal balance is DEBIT (+Debit, -Credit)
     * - LIABILITY / EQUITY / REVENUE: Normal balance is CREDIT (+Credit, -Debit)
     */
    fun calculateBalanceDelta(direction: PostingDirection, amountMinorUnits: Long): Long {
        require(amountMinorUnits > 0) { "Posting amount must be strictly positive" }
        return when (this) {
            ASSET, EXPENSE -> when (direction) {
                PostingDirection.DEBIT -> amountMinorUnits
                PostingDirection.CREDIT -> -amountMinorUnits
            }
            LIABILITY, EQUITY, REVENUE -> when (direction) {
                PostingDirection.CREDIT -> amountMinorUnits
                PostingDirection.DEBIT -> -amountMinorUnits
            }
        }
    }
}

/**
 * Direction of an atomic ledger posting leg.
 */
enum class PostingDirection {
    DEBIT,
    CREDIT
}

/**
 * Lifecycle state of a ledger account.
 */
enum class AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}

/**
 * Categorization of financial journal transactions.
 */
enum class EntryType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    SETTLEMENT,
    FEE,
    ADJUSTMENT,
    REVERSAL
}

/**
 * State of a journal entry header.
 */
enum class EntryStatus {
    COMMITTED,
    REVERSED
}
