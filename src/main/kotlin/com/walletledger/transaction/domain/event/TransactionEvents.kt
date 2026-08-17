package com.walletledger.transaction.domain.event

import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.common.domain.WalletId
import com.walletledger.transaction.domain.model.TransactionType
import java.time.Instant
import java.util.UUID

data class TransactionCompletedDomainEvent(
    val transactionId: TransactionId,
    val transactionType: TransactionType,
    val sourceWalletId: WalletId?,
    val destinationWalletId: WalletId?,
    val amount: Money,
    val fee: Money,
    val occurredAt: Instant = Instant.now()
)

data class TransactionFailedDomainEvent(
    val transactionId: TransactionId,
    val transactionType: TransactionType,
    val reason: String,
    val occurredAt: Instant = Instant.now()
)

data class HoldCapturedDomainEvent(
    val holdId: UUID,
    val walletId: WalletId,
    val transactionId: TransactionId,
    val amount: Money,
    val occurredAt: Instant = Instant.now()
)

data class HoldVoidedDomainEvent(
    val holdId: UUID,
    val walletId: WalletId,
    val transactionId: TransactionId,
    val amount: Money,
    val occurredAt: Instant = Instant.now()
)
