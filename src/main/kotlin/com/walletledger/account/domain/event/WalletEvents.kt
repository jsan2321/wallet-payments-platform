package com.walletledger.account.domain.event

import com.walletledger.account.domain.model.WalletStatus
import com.walletledger.account.domain.model.WalletTier
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import java.time.Instant

/**
 * Domain events emitted by the Account/Wallet bounded context.
 */
data class WalletCreatedDomainEvent(
    val walletId: WalletId,
    val ownerId: OwnerId,
    val tier: WalletTier,
    val occurredAt: Instant = Instant.now()
)

data class WalletAccountAddedDomainEvent(
    val walletId: WalletId,
    val ledgerAccountId: AccountId,
    val currency: CurrencyCode,
    val occurredAt: Instant = Instant.now()
)

data class HoldPlacedDomainEvent(
    val walletId: WalletId,
    val currency: CurrencyCode,
    val holdAmount: Money,
    val remainingAvailable: Money,
    val occurredAt: Instant = Instant.now()
)

data class HoldReleasedDomainEvent(
    val walletId: WalletId,
    val currency: CurrencyCode,
    val releaseAmount: Money,
    val newAvailable: Money,
    val occurredAt: Instant = Instant.now()
)

data class WalletStatusChangedDomainEvent(
    val walletId: WalletId,
    val previousStatus: WalletStatus,
    val newStatus: WalletStatus,
    val occurredAt: Instant = Instant.now()
)
