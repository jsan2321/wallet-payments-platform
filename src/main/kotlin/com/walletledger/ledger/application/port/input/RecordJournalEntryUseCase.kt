package com.walletledger.ledger.application.port.input

import arrow.core.Either
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.EntryId
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.PostingDirection
import java.time.Instant

/**
 * Inbound Use Case for recording atomic, balanced double-entry journal transactions.
 */
interface RecordJournalEntryUseCase {
    fun recordJournalEntry(command: RecordJournalEntryCommand): Either<LedgerDomainError, JournalEntryResult>
}

data class RecordJournalEntryCommand(
    val transactionId: TransactionId,
    val description: String,
    val entryType: EntryType,
    val legs: List<PostingLegCommand>,
    val postedAt: Instant = Instant.now()
)

data class PostingLegCommand(
    val accountId: AccountId,
    val direction: PostingDirection,
    val amount: Money
)

data class JournalEntryResult(
    val entryId: EntryId,
    val transactionId: TransactionId,
    val entryType: EntryType,
    val totalAmountMinorUnits: Long,
    val postingCount: Int,
    val postedAt: Instant
)
