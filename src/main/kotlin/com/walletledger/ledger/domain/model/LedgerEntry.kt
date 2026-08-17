package com.walletledger.ledger.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.EntryId
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.PostingId
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.domain.error.LedgerDomainError
import java.time.Instant

/**
 * Immutable atomic leg of a double-entry journal entry.
 */
data class Posting(
    val id: PostingId,
    val entryId: EntryId,
    val accountId: AccountId,
    val direction: PostingDirection,
    val amount: Money,
    val sequenceNum: Int,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(amount.amountMinorUnits > 0) { "Posting amount must be strictly positive" }
    }
}

/**
 * Aggregate Root representing an immutable financial journal transaction entry.
 * Enforces the strict Double-Entry balancing invariant (sum of debits == sum of credits).
 */
data class LedgerEntry(
    val id: EntryId,
    val transactionId: TransactionId,
    val description: String,
    val entryType: EntryType,
    val status: EntryStatus,
    val postings: List<Posting>,
    val postedAt: Instant,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(description.isNotBlank()) { "Journal entry description cannot be blank" }
        require(postings.size >= 2) { "A journal entry must contain at least 2 posting legs" }
    }

    companion object {
        /**
         * Validates balancing invariants and constructs an immutable LedgerEntry.
         * Returns Either.Left(UnbalancedEntry) if sum(debits) != sum(credits).
         */
        fun create(
            id: EntryId = EntryId.generate(),
            transactionId: TransactionId,
            description: String,
            entryType: EntryType,
            postingsDraft: List<PostingDraft>,
            postedAt: Instant = Instant.now()
        ): Either<LedgerDomainError, LedgerEntry> {
            if (postingsDraft.size < 2) {
                return LedgerDomainError.EmptyPostings().left()
            }

            // Group postings by currency to enforce balancing per currency
            val currencies = postingsDraft.map { it.amount.currency }.distinct()
            for (curr in currencies) {
                val currencyPostings = postingsDraft.filter { it.amount.currency == curr }
                val totalDebits = currencyPostings
                    .filter { it.direction == PostingDirection.DEBIT }
                    .sumOf { it.amount.amountMinorUnits }
                val totalCredits = currencyPostings
                    .filter { it.direction == PostingDirection.CREDIT }
                    .sumOf { it.amount.amountMinorUnits }

                if (totalDebits != totalCredits) {
                    return LedgerDomainError.UnbalancedEntry(
                        totalDebits = totalDebits,
                        totalCredits = totalCredits,
                        currency = curr
                    ).left()
                }
            }

            val postings = postingsDraft.mapIndexed { index, draft ->
                Posting(
                    id = draft.id ?: PostingId.generate(),
                    entryId = id,
                    accountId = draft.accountId,
                    direction = draft.direction,
                    amount = draft.amount,
                    sequenceNum = index + 1,
                    createdAt = postedAt
                )
            }

            return LedgerEntry(
                id = id,
                transactionId = transactionId,
                description = description,
                entryType = entryType,
                status = EntryStatus.COMMITTED,
                postings = postings,
                postedAt = postedAt,
                createdAt = postedAt
            ).right()
        }
    }
}

/**
 * Draft representation of a posting leg prior to journal entry construction.
 */
data class PostingDraft(
    val id: PostingId? = null,
    val accountId: AccountId,
    val direction: PostingDirection,
    val amount: Money
)
