package com.walletledger.ledger.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.common.domain.AccountId
import com.walletledger.ledger.application.port.input.JournalEntryResult
import com.walletledger.ledger.application.port.input.RecordJournalEntryCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryUseCase
import com.walletledger.ledger.application.port.output.LedgerAccountRepositoryPort
import com.walletledger.ledger.application.port.output.LedgerEntryRepositoryPort
import com.walletledger.ledger.application.port.output.LedgerEventPublisherPort
import com.walletledger.ledger.application.port.output.PostingRepositoryPort
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.event.JournalEntryRecordedDomainEvent
import com.walletledger.ledger.domain.model.LedgerAccount
import com.walletledger.ledger.domain.model.LedgerEntry
import com.walletledger.ledger.domain.model.PostingDraft
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Authoritative Application Service executing immutable double-entry journal postings
 * within a single PostgreSQL ACID transaction boundary.
 */
@Service
class RecordJournalEntryService(
    private val ledgerAccountRepository: LedgerAccountRepositoryPort,
    private val ledgerEntryRepository: LedgerEntryRepositoryPort,
    private val postingRepository: PostingRepositoryPort,
    private val eventPublisher: LedgerEventPublisherPort
) : RecordJournalEntryUseCase {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun recordJournalEntry(command: RecordJournalEntryCommand): Either<LedgerDomainError, JournalEntryResult> {
        // 1. Build and validate domain journal entry and double-entry balancing invariant
        val drafts = command.legs.map {
            PostingDraft(
                accountId = it.accountId,
                direction = it.direction,
                amount = it.amount
            )
        }

        val entryEither = LedgerEntry.create(
            transactionId = command.transactionId,
            description = command.description,
            entryType = command.entryType,
            postingsDraft = drafts,
            postedAt = command.postedAt
        )

        val entry = when (entryEither) {
            is Either.Left -> return entryEither.value.left()
            is Either.Right -> entryEither.value
        }

        // 2. Deterministic Account Locking: Sort account IDs ascending by UUID value
        val distinctAccountIds: List<AccountId> = command.legs
            .map { it.accountId }
            .distinct()
            .sortedBy { it.value }

        val lockedAccounts = ledgerAccountRepository.findAllByIdInForUpdate(distinctAccountIds)
        val accountMap = lockedAccounts.associateBy { it.id }.toMutableMap()

        // 3. Verify all accounts exist
        for (accId in distinctAccountIds) {
            if (!accountMap.containsKey(accId)) {
                return LedgerDomainError.AccountNotFound(accId).left()
            }
        }

        // 4. Sequentially apply each posting leg to its locked aggregate root
        val updatedAccounts = mutableMapOf<AccountId, LedgerAccount>()
        for (leg in command.legs) {
            val currentAccount = accountMap[leg.accountId]!!
            val applyResult = currentAccount.applyPosting(leg.direction, leg.amount)
            when (applyResult) {
                is Either.Left -> return applyResult.value.left()
                is Either.Right -> {
                    accountMap[leg.accountId] = applyResult.value
                    updatedAccounts[leg.accountId] = applyResult.value
                }
            }
        }

        // 5. Persist updated accounts, journal entry, and posting records
        ledgerAccountRepository.saveAccounts(updatedAccounts.values.toList())
        ledgerEntryRepository.save(entry)
        postingRepository.savePostings(entry.postings)

        // 6. Publish in-process domain event
        eventPublisher.publish(
            JournalEntryRecordedDomainEvent(
                entryId = entry.id,
                transactionId = entry.transactionId,
                entryType = entry.entryType,
                postedAt = entry.postedAt
            )
        )

        val totalDebitMinor = entry.postings
            .filter { it.direction == com.walletledger.ledger.domain.model.PostingDirection.DEBIT }
            .sumOf { it.amount.amountMinorUnits }

        return JournalEntryResult(
            entryId = entry.id,
            transactionId = entry.transactionId,
            entryType = entry.entryType,
            totalAmountMinorUnits = totalDebitMinor,
            postingCount = entry.postings.size,
            postedAt = entry.postedAt
        ).right()
    }
}
