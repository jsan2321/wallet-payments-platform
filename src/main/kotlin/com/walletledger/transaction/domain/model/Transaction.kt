package com.walletledger.transaction.domain.model

import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.common.domain.WalletId
import java.time.Instant
import java.util.UUID

/**
 * Aggregate Root representing an orchestratable financial transaction.
 */
data class Transaction(
    val id: TransactionId = TransactionId.generate(),
    val idempotencyKey: IdempotencyKey,
    val transactionType: TransactionType,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val sourceWalletId: WalletId? = null,
    val destinationWalletId: WalletId? = null,
    val amount: Money,
    val fee: Money = Money.zero(amount.currency),
    val description: String,
    val failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(amount.amountMinorUnits > 0) { "Transaction amount must be strictly positive" }
        require(fee.currency == amount.currency) { "Transaction fee currency must match amount currency" }
        require(fee.amountMinorUnits >= 0) { "Transaction fee cannot be negative" }
        require(description.isNotBlank()) { "Transaction description cannot be blank" }
    }

    fun complete(): Transaction = copy(
        status = TransactionStatus.COMPLETED,
        updatedAt = Instant.now()
    )

    fun fail(reason: String): Transaction = copy(
        status = TransactionStatus.FAILED,
        failureReason = reason,
        updatedAt = Instant.now()
    )
}

/**
 * Entity representing an authorization hold in the two-phase settlement engine.
 */
data class HoldRecord(
    val id: UUID = UUID.randomUUID(),
    val walletId: WalletId,
    val transactionId: TransactionId,
    val amount: Money,
    val status: HoldStatus = HoldStatus.HELD,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(amount.amountMinorUnits > 0) { "Hold amount must be strictly positive" }
    }

    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(expiresAt)

    fun capture(): HoldRecord = copy(
        status = HoldStatus.CAPTURED,
        updatedAt = Instant.now()
    )

    fun voidHold(): HoldRecord = copy(
        status = HoldStatus.VOIDED,
        updatedAt = Instant.now()
    )
}

/**
 * Record for tracking distributed request idempotency and cached responses.
 */
data class IdempotencyRecord(
    val id: UUID = UUID.randomUUID(),
    val idempotencyKey: IdempotencyKey,
    val requestHash: String,
    val status: IdempotencyStatus,
    val responsePayload: String? = null,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant = Instant.now().plusSeconds(86400) // 24 hours TTL
)
