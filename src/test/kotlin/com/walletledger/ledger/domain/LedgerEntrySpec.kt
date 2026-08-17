package com.walletledger.ledger.domain

import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.LedgerEntry
import com.walletledger.ledger.domain.model.PostingDirection
import com.walletledger.ledger.domain.model.PostingDraft
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LedgerEntrySpec : StringSpec({

    val accA = AccountId.generate()
    val accB = AccountId.generate()
    val accFee = AccountId.generate()

    "balanced two-leg journal entry creates valid LedgerEntry" {
        val drafts = listOf(
            PostingDraft(accountId = accA, direction = PostingDirection.DEBIT, amount = Money.ofMinor(5000L, CurrencyCode.USD)),
            PostingDraft(accountId = accB, direction = PostingDirection.CREDIT, amount = Money.ofMinor(5000L, CurrencyCode.USD))
        )

        val result = LedgerEntry.create(
            transactionId = TransactionId.generate(),
            description = "P2P Transfer",
            entryType = EntryType.TRANSFER,
            postingsDraft = drafts
        )

        result.isRight() shouldBe true
        val entry = result.getOrNull()!!
        entry.postings.size shouldBe 2
        entry.postings[0].sequenceNum shouldBe 1
        entry.postings[1].sequenceNum shouldBe 2
    }

    "unbalanced journal entry is rejected with UnbalancedEntry error" {
        val drafts = listOf(
            PostingDraft(accountId = accA, direction = PostingDirection.DEBIT, amount = Money.ofMinor(5000L, CurrencyCode.USD)),
            PostingDraft(accountId = accB, direction = PostingDirection.CREDIT, amount = Money.ofMinor(4500L, CurrencyCode.USD))
        )

        val result = LedgerEntry.create(
            transactionId = TransactionId.generate(),
            description = "Unbalanced Transfer",
            entryType = EntryType.TRANSFER,
            postingsDraft = drafts
        )

        result.isLeft() shouldBe true
        val error = result.swap().getOrNull()
        error.shouldBeInstanceOf<LedgerDomainError.UnbalancedEntry>()
        error.totalDebits shouldBe 5000L
        error.totalCredits shouldBe 4500L
        error.currency shouldBe CurrencyCode.USD
    }

    "multi-leg balanced fee split creates valid LedgerEntry" {
        // Customer pays $100.00: Debit Customer ($100), Credit Merchant ($97), Credit Platform Fee ($3)
        val drafts = listOf(
            PostingDraft(accountId = accA, direction = PostingDirection.DEBIT, amount = Money.ofMinor(10000L, CurrencyCode.USD)),
            PostingDraft(accountId = accB, direction = PostingDirection.CREDIT, amount = Money.ofMinor(9700L, CurrencyCode.USD)),
            PostingDraft(accountId = accFee, direction = PostingDirection.CREDIT, amount = Money.ofMinor(300L, CurrencyCode.USD))
        )

        val result = LedgerEntry.create(
            transactionId = TransactionId.generate(),
            description = "Merchant payment with platform fee",
            entryType = EntryType.TRANSFER,
            postingsDraft = drafts
        )

        result.isRight() shouldBe true
        val entry = result.getOrNull()!!
        entry.postings.size shouldBe 3
        entry.postings.sumOf { if (it.direction == PostingDirection.DEBIT) it.amount.amountMinorUnits else 0L } shouldBe 10000L
        entry.postings.sumOf { if (it.direction == PostingDirection.CREDIT) it.amount.amountMinorUnits else 0L } shouldBe 10000L
    }

    "entry with fewer than 2 postings is rejected with EmptyPostings error" {
        val drafts = listOf(
            PostingDraft(accountId = accA, direction = PostingDirection.DEBIT, amount = Money.ofMinor(5000L, CurrencyCode.USD))
        )

        val result = LedgerEntry.create(
            transactionId = TransactionId.generate(),
            description = "Single leg entry",
            entryType = EntryType.DEPOSIT,
            postingsDraft = drafts
        )

        result.isLeft() shouldBe true
        result.swap().getOrNull().shouldBeInstanceOf<LedgerDomainError.EmptyPostings>()
    }
})
