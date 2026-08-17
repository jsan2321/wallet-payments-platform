package com.walletledger.common.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class DomainIdsSpec : StringSpec({

    "WalletId generates unique valid identifiers" {
        val id1 = WalletId.generate()
        val id2 = WalletId.generate()

        id1 shouldNotBe id2
        WalletId.of(id1.value.toString()) shouldBe id1
    }

    "TransactionId, EntryId, and PostingId wrap UUIDs accurately" {
        val uuid = UUID.randomUUID()
        TransactionId(uuid).value shouldBe uuid
        EntryId(uuid).value shouldBe uuid
        PostingId(uuid).value shouldBe uuid
        AccountId(uuid).value shouldBe uuid
    }

    "OwnerId validates non-blank strings" {
        val ownerId = OwnerId("user_12345")
        ownerId.value shouldBe "user_12345"

        shouldThrow<IllegalArgumentException> {
            OwnerId("")
        }

        shouldThrow<IllegalArgumentException> {
            OwnerId("   ")
        }
    }

    "IdempotencyKey enforces length and non-blank constraints" {
        val key = IdempotencyKey("idem-req-abc-999")
        key.value shouldBe "idem-req-abc-999"

        shouldThrow<IllegalArgumentException> {
            IdempotencyKey("")
        }

        shouldThrow<IllegalArgumentException> {
            IdempotencyKey("a".repeat(257))
        }
    }
})
