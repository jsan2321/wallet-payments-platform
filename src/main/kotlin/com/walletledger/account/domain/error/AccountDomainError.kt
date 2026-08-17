package com.walletledger.account.domain.error

import com.walletledger.account.domain.model.WalletAccountStatus
import com.walletledger.account.domain.model.WalletStatus
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import java.time.LocalDate

/**
 * Sealed hierarchy of typed domain errors for account and wallet management.
 */
sealed interface AccountDomainError {

    data class WalletNotFound(
        val walletId: WalletId
    ) : AccountDomainError {
        val message: String = "Wallet not found: $walletId"
    }

    data class WalletNotActive(
        val walletId: WalletId,
        val status: WalletStatus
    ) : AccountDomainError {
        val message: String = "Wallet $walletId is not active (status: $status)"
    }

    data class WalletAccountNotFound(
        val walletId: WalletId,
        val currency: CurrencyCode
    ) : AccountDomainError {
        val message: String = "Wallet account for currency $currency not found in wallet $walletId"
    }

    data class WalletAccountNotActive(
        val currency: CurrencyCode,
        val status: WalletAccountStatus
    ) : AccountDomainError {
        val message: String = "Wallet account for currency $currency is not active (status: $status)"
    }

    data class CurrencyAccountAlreadyExists(
        val walletId: WalletId,
        val currency: CurrencyCode
    ) : AccountDomainError {
        val message: String = "Wallet $walletId already has a sub-account for currency $currency"
    }

    data class InsufficientAvailableFunds(
        val walletId: WalletId,
        val currency: CurrencyCode,
        val availableBalance: Money,
        val requestedAmount: Money
    ) : AccountDomainError {
        val message: String = "Insufficient available balance in wallet $walletId for $currency: available ${availableBalance.amountMinorUnits} < requested ${requestedAmount.amountMinorUnits}"
    }

    data class InsufficientHeldFunds(
        val walletId: WalletId,
        val currency: CurrencyCode,
        val heldBalance: Money,
        val requestedRelease: Money
    ) : AccountDomainError {
        val message: String = "Insufficient held balance in wallet $walletId for $currency: held ${heldBalance.amountMinorUnits} < requested ${requestedRelease.amountMinorUnits}"
    }

    data class SingleTransactionLimitExceeded(
        val requestedAmount: Money,
        val limit: Money
    ) : AccountDomainError {
        val message: String = "Transaction amount ${requestedAmount.amountMinorUnits} exceeds single transaction limit of ${limit.amountMinorUnits}"
    }

    data class DailySpendLimitExceeded(
        val date: LocalDate,
        val currentSpend: Money,
        val requestedAmount: Money,
        val dailyLimit: Money
    ) : AccountDomainError {
        val message: String = "Daily spend limit exceeded for $date: current ${currentSpend.amountMinorUnits} + requested ${requestedAmount.amountMinorUnits} > limit ${dailyLimit.amountMinorUnits}"
    }

    data class DuplicateWalletOwner(
        val ownerId: OwnerId
    ) : AccountDomainError {
        val message: String = "Wallet for owner $ownerId already exists"
    }

    data class InvalidHoldOperation(
        val message: String
    ) : AccountDomainError
}
