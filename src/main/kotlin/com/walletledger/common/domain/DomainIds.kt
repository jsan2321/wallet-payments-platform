package com.walletledger.common.domain

import java.util.UUID

/**
 * Strongly-typed domain identifiers utilizing Kotlin @JvmInline value classes
 * to eliminate primitive obsession without runtime allocation overhead.
 */

@JvmInline
value class WalletId(val value: UUID) {
    companion object {
        fun generate(): WalletId = WalletId(UUID.randomUUID())
        fun of(uuidString: String): WalletId = WalletId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun generate(): AccountId = AccountId(UUID.randomUUID())
        fun of(uuidString: String): AccountId = AccountId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class TransactionId(val value: UUID) {
    companion object {
        fun generate(): TransactionId = TransactionId(UUID.randomUUID())
        fun of(uuidString: String): TransactionId = TransactionId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class EntryId(val value: UUID) {
    companion object {
        fun generate(): EntryId = EntryId(UUID.randomUUID())
        fun of(uuidString: String): EntryId = EntryId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class PostingId(val value: UUID) {
    companion object {
        fun generate(): PostingId = PostingId(UUID.randomUUID())
        fun of(uuidString: String): PostingId = PostingId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class ReconciliationCaseId(val value: UUID) {
    companion object {
        fun generate(): ReconciliationCaseId = ReconciliationCaseId(UUID.randomUUID())
        fun of(uuidString: String): ReconciliationCaseId = ReconciliationCaseId(UUID.fromString(uuidString))
    }
    override fun toString(): String = value.toString()
}

@JvmInline
value class OwnerId(val value: String) {
    init {
        require(value.isNotBlank()) { "OwnerId cannot be blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.isNotBlank()) { "IdempotencyKey cannot be blank" }
        require(value.length <= 256) { "IdempotencyKey length exceeds maximum 256 characters" }
    }
    override fun toString(): String = value
}
