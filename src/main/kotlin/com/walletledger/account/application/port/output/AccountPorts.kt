package com.walletledger.account.application.port.output

import com.walletledger.account.domain.event.HoldPlacedDomainEvent
import com.walletledger.account.domain.event.HoldReleasedDomainEvent
import com.walletledger.account.domain.event.WalletAccountAddedDomainEvent
import com.walletledger.account.domain.event.WalletCreatedDomainEvent
import com.walletledger.account.domain.event.WalletStatusChangedDomainEvent
import com.walletledger.account.domain.model.Wallet
import com.walletledger.account.domain.model.WalletAccount
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import java.time.LocalDate

/**
 * Outbound persistence SPI for Wallets.
 */
interface WalletRepositoryPort {
    fun findById(id: WalletId): Wallet?
    fun findByOwnerId(ownerId: OwnerId): Wallet?
    fun save(wallet: Wallet): Wallet
}

/**
 * Outbound persistence SPI for WalletAccounts.
 */
interface WalletAccountRepositoryPort {
    fun findByWalletId(walletId: WalletId): List<WalletAccount>
    fun findByWalletIdAndCurrency(walletId: WalletId, currency: CurrencyCode): WalletAccount?
    fun save(account: WalletAccount): WalletAccount
    fun saveAll(accounts: List<WalletAccount>): List<WalletAccount>
}

/**
 * Outbound persistence SPI for Spend Accumulators.
 */
interface SpendAccumulatorRepositoryPort {
    fun getAccumulatedSpend(walletId: WalletId, currency: CurrencyCode, date: LocalDate): Money
    fun addSpend(walletId: WalletId, amount: Money, date: LocalDate): Money
}

/**
 * Outbound domain event publisher.
 */
interface WalletEventPublisherPort {
    fun publish(event: WalletCreatedDomainEvent)
    fun publish(event: WalletAccountAddedDomainEvent)
    fun publish(event: HoldPlacedDomainEvent)
    fun publish(event: HoldReleasedDomainEvent)
    fun publish(event: WalletStatusChangedDomainEvent)
}
