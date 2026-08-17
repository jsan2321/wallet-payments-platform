package com.walletledger.account.application.port.input

import arrow.core.Either
import com.walletledger.account.domain.error.AccountDomainError
import com.walletledger.account.domain.model.WalletAccountStatus
import com.walletledger.account.domain.model.WalletStatus
import com.walletledger.account.domain.model.WalletTier
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import java.time.Instant
import java.time.LocalDate

/**
 * Use case for creating a customer wallet and provisioning its default base currency account.
 */
interface CreateWalletUseCase {
    fun createWallet(command: CreateWalletCommand): Either<AccountDomainError, WalletResult>
}

data class CreateWalletCommand(
    val id: WalletId = WalletId.generate(),
    val ownerId: OwnerId,
    val baseCurrency: CurrencyCode,
    val tier: WalletTier = WalletTier.STANDARD,
    val customDailyLimitMinor: Long? = null,
    val customSingleTxLimitMinor: Long? = null
)

/**
 * Use case for adding an additional currency sub-account to an existing wallet.
 */
interface AddCurrencyAccountUseCase {
    fun addCurrencyAccount(command: AddCurrencyAccountCommand): Either<AccountDomainError, WalletAccountResult>
}

data class AddCurrencyAccountCommand(
    val walletId: WalletId,
    val currency: CurrencyCode
)

/**
 * Use case for querying wallet details and balances.
 */
interface GetWalletUseCase {
    fun getWalletById(walletId: WalletId): Either<AccountDomainError, WalletResult>
    fun getWalletByOwnerId(ownerId: OwnerId): Either<AccountDomainError, WalletResult>
}

/**
 * Use case for placing authorization holds on wallet funds.
 */
interface PlaceHoldUseCase {
    fun placeHold(command: PlaceHoldCommand): Either<AccountDomainError, WalletAccountResult>
}

data class PlaceHoldCommand(
    val walletId: WalletId,
    val currency: CurrencyCode,
    val amount: Money
)

/**
 * Use case for releasing holds back into available funds.
 */
interface ReleaseHoldUseCase {
    fun releaseHold(command: ReleaseHoldCommand): Either<AccountDomainError, WalletAccountResult>
}

data class ReleaseHoldCommand(
    val walletId: WalletId,
    val currency: CurrencyCode,
    val amount: Money
)

/**
 * Use case for updating wallet sub-account balances across transaction flows.
 */
interface UpdateWalletBalanceUseCase {
    fun creditBalance(walletId: WalletId, amount: Money): Either<AccountDomainError, WalletAccountResult>
    fun debitBalance(walletId: WalletId, amount: Money): Either<AccountDomainError, WalletAccountResult>
    fun settleHeldBalance(sourceWalletId: WalletId, destinationWalletId: WalletId, amount: Money): Either<AccountDomainError, Unit>
    fun getSubAccount(walletId: WalletId, currency: CurrencyCode): Either<AccountDomainError, WalletAccountResult>
}

/**
 * Use case for checking and atomically accumulating daily spend.
 */
interface CheckSpendLimitUseCase {
    fun checkAndAccumulateSpend(
        walletId: WalletId,
        amount: Money,
        date: LocalDate = LocalDate.now()
    ): Either<AccountDomainError, Unit>
}

data class WalletResult(
    val id: WalletId,
    val ownerId: OwnerId,
    val status: WalletStatus,
    val tier: WalletTier,
    val dailySpendLimitMinor: Long,
    val singleTxLimitMinor: Long,
    val accounts: List<WalletAccountResult>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class WalletAccountResult(
    val id: java.util.UUID,
    val walletId: WalletId,
    val ledgerAccountId: AccountId,
    val currency: CurrencyCode,
    val availableBalance: Money,
    val heldBalance: Money,
    val totalBalance: Money,
    val status: WalletAccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)
