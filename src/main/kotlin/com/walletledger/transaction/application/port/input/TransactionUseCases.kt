package com.walletledger.transaction.application.port.input

import arrow.core.Either
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.common.domain.WalletId
import com.walletledger.transaction.domain.error.TransactionDomainError
import com.walletledger.transaction.domain.model.HoldStatus
import com.walletledger.transaction.domain.model.TransactionStatus
import com.walletledger.transaction.domain.model.TransactionType
import java.time.Instant
import java.util.UUID

/**
 * Inbound Use Cases for financial transactions.
 */
interface DepositFundsUseCase {
    fun deposit(command: DepositCommand): Either<TransactionDomainError, TransactionResult>
}

data class DepositCommand(
    val idempotencyKey: IdempotencyKey,
    val destinationWalletId: WalletId,
    val amount: Money,
    val description: String = "Funds Deposit"
)

interface WithdrawFundsUseCase {
    fun withdraw(command: WithdrawCommand): Either<TransactionDomainError, TransactionResult>
}

data class WithdrawCommand(
    val idempotencyKey: IdempotencyKey,
    val sourceWalletId: WalletId,
    val amount: Money,
    val description: String = "Funds Withdrawal"
)

interface TransferFundsUseCase {
    fun transfer(command: TransferCommand): Either<TransactionDomainError, TransactionResult>
}

data class TransferCommand(
    val idempotencyKey: IdempotencyKey,
    val sourceWalletId: WalletId,
    val destinationWalletId: WalletId,
    val amount: Money,
    val description: String = "P2P Transfer"
)

interface SplitPaymentUseCase {
    fun splitPayment(command: SplitPaymentCommand): Either<TransactionDomainError, TransactionResult>
}

data class SplitPaymentCommand(
    val idempotencyKey: IdempotencyKey,
    val sourceWalletId: WalletId,
    val destinationWalletId: WalletId,
    val totalAmount: Money,
    val platformFee: Money,
    val description: String = "Split Payment with Platform Fee"
)

interface AuthorizeHoldUseCase {
    fun authorizeHold(command: AuthorizeHoldCommand): Either<TransactionDomainError, HoldResult>
}

data class AuthorizeHoldCommand(
    val idempotencyKey: IdempotencyKey,
    val walletId: WalletId,
    val amount: Money,
    val durationSeconds: Long = 86400, // 24 hours
    val description: String = "Authorization Hold"
)

interface CaptureHoldUseCase {
    fun captureHold(command: CaptureHoldCommand): Either<TransactionDomainError, TransactionResult>
}

data class CaptureHoldCommand(
    val idempotencyKey: IdempotencyKey,
    val holdId: UUID,
    val destinationWalletId: WalletId,
    val description: String = "Capture Hold Settlement"
)

interface VoidHoldUseCase {
    fun voidHold(command: VoidHoldCommand): Either<TransactionDomainError, HoldResult>
}

data class VoidHoldCommand(
    val idempotencyKey: IdempotencyKey,
    val holdId: UUID,
    val description: String = "Void Authorization Hold"
)

data class TransactionResult(
    val id: TransactionId,
    val idempotencyKey: IdempotencyKey,
    val transactionType: TransactionType,
    val status: TransactionStatus,
    val sourceWalletId: WalletId?,
    val destinationWalletId: WalletId?,
    val amount: Money,
    val fee: Money,
    val description: String,
    val createdAt: Instant
)

data class HoldResult(
    val holdId: UUID,
    val walletId: WalletId,
    val transactionId: TransactionId,
    val amount: Money,
    val status: HoldStatus,
    val expiresAt: Instant,
    val createdAt: Instant
)
