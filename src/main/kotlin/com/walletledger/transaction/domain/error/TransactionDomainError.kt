package com.walletledger.transaction.domain.error

import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.TransactionId
import com.walletledger.common.domain.WalletId
import com.walletledger.transaction.domain.model.HoldStatus
import java.util.UUID

/**
 * Sealed hierarchy of typed domain errors in transaction orchestration.
 */
sealed interface TransactionDomainError {

    data class IdempotencyPayloadMismatch(
        val key: IdempotencyKey
    ) : TransactionDomainError {
        val message: String = "Idempotency key '${key.value}' was already processed with a different request payload"
    }

    data class IdempotencyKeyInFlight(
        val key: IdempotencyKey
    ) : TransactionDomainError {
        val message: String = "Transaction for idempotency key '${key.value}' is currently in-flight"
    }

    data class TransactionNotFound(
        val transactionId: TransactionId
    ) : TransactionDomainError {
        val message: String = "Transaction not found: $transactionId"
    }

    data class HoldNotFound(
        val holdId: UUID
    ) : TransactionDomainError {
        val message: String = "Authorization hold not found: $holdId"
    }

    data class HoldAlreadySettled(
        val holdId: UUID,
        val status: HoldStatus
    ) : TransactionDomainError {
        val message: String = "Hold $holdId cannot be settled or voided in its current status: $status"
    }

    data class HoldExpired(
        val holdId: UUID
    ) : TransactionDomainError {
        val message: String = "Authorization hold $holdId has expired"
    }

    data class InsufficientFunds(
        val walletId: WalletId,
        val message: String
    ) : TransactionDomainError

    data class PlatformAccountNotFound(
        val identifier: String
    ) : TransactionDomainError {
        val message: String = "Platform system ledger account not found: $identifier"
    }

    data class ExecutionFailed(
        val message: String
    ) : TransactionDomainError
}
