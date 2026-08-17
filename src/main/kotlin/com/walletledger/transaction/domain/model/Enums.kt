package com.walletledger.transaction.domain.model

/**
 * Types of financial transactions supported by the orchestration platform.
 */
enum class TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    SPLIT_PAYMENT,
    HOLD_AUTHORIZE,
    HOLD_CAPTURE,
    HOLD_VOID
}

/**
 * Lifecycle state of a financial transaction.
 */
enum class TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED
}

/**
 * State of a two-phase settlement authorization hold.
 */
enum class HoldStatus {
    HELD,
    CAPTURED,
    VOIDED,
    EXPIRED
}

/**
 * Status of an in-flight or completed idempotency lock record.
 */
enum class IdempotencyStatus {
    IN_FLIGHT,
    COMPLETED,
    FAILED
}
