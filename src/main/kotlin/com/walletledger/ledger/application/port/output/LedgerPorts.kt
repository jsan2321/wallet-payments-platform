package com.walletledger.ledger.application.port.output

import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.EntryId
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.domain.event.JournalEntryRecordedDomainEvent
import com.walletledger.ledger.domain.model.LedgerAccount
import com.walletledger.ledger.domain.model.LedgerEntry
import com.walletledger.ledger.domain.model.Posting

/**
 * Outbound persistence SPI for Ledger Accounts.
 */
interface LedgerAccountRepositoryPort {
    fun findById(id: AccountId): LedgerAccount?
    fun findByAccountNumber(accountNumber: String): LedgerAccount?

    /**
     * Acquires row-level pessimistic locks on all accounts specified, strictly ordering
     * by ID ascending to guarantee deadlock avoidance.
     */
    fun findAllByIdInForUpdate(ids: List<AccountId>): List<LedgerAccount>

    fun save(account: LedgerAccount): LedgerAccount
    fun saveAccounts(accounts: List<LedgerAccount>): List<LedgerAccount>
}

/**
 * Outbound persistence SPI for Ledger Entries.
 */
interface LedgerEntryRepositoryPort {
    fun save(entry: LedgerEntry): LedgerEntry
    fun findById(id: EntryId): LedgerEntry?
    fun findByTransactionId(transactionId: TransactionId): LedgerEntry?
}

/**
 * Outbound persistence SPI for Postings.
 */
interface PostingRepositoryPort {
    fun savePostings(postings: List<Posting>): List<Posting>
    fun findAllByEntryId(entryId: EntryId): List<Posting>
    fun findAllByAccountId(accountId: AccountId, limit: Int = 100): List<Posting>
}

/**
 * Outbound domain event publisher.
 */
interface LedgerEventPublisherPort {
    fun publish(event: JournalEntryRecordedDomainEvent)
}
