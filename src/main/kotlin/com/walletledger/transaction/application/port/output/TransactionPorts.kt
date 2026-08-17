package com.walletledger.transaction.application.port.output

import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.TransactionId
import com.walletledger.transaction.domain.event.HoldCapturedDomainEvent
import com.walletledger.transaction.domain.event.HoldVoidedDomainEvent
import com.walletledger.transaction.domain.event.TransactionCompletedDomainEvent
import com.walletledger.transaction.domain.event.TransactionFailedDomainEvent
import com.walletledger.transaction.domain.model.HoldRecord
import com.walletledger.transaction.domain.model.IdempotencyRecord
import com.walletledger.transaction.domain.model.Transaction
import java.util.UUID

/**
 * Outbound persistence SPI for Transactions.
 */
interface TransactionRepositoryPort {
    fun findById(id: TransactionId): Transaction?
    fun findByIdempotencyKey(key: IdempotencyKey): Transaction?
    fun save(transaction: Transaction): Transaction
}

/**
 * Outbound persistence SPI for Idempotency Records.
 */
interface IdempotencyRepositoryPort {
    fun tryAcquireLock(key: IdempotencyKey, requestHash: String): Boolean
    fun findByKey(key: IdempotencyKey): IdempotencyRecord?
    fun save(record: IdempotencyRecord): IdempotencyRecord
}

/**
 * Outbound persistence SPI for Settlement Holds.
 */
interface HoldRepositoryPort {
    fun findById(id: UUID): HoldRecord?
    fun save(hold: HoldRecord): HoldRecord
}

/**
 * Outbound domain event publisher for Transactions.
 */
interface TransactionEventPublisherPort {
    fun publish(event: TransactionCompletedDomainEvent)
    fun publish(event: TransactionFailedDomainEvent)
    fun publish(event: HoldCapturedDomainEvent)
    fun publish(event: HoldVoidedDomainEvent)
}
