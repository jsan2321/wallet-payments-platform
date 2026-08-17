package com.walletledger.ledger.domain.event

import com.walletledger.common.domain.EntryId
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.domain.model.EntryType
import java.time.Instant

/**
 * In-process domain event published when an immutable double-entry journal transaction is committed.
 */
data class JournalEntryRecordedDomainEvent(
    val entryId: EntryId,
    val transactionId: TransactionId,
    val entryType: EntryType,
    val postedAt: Instant,
    val occurredAt: Instant = Instant.now()
)
