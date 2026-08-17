package com.walletledger.ledger.application.port.input

import arrow.core.Either
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.model.AccountStatus
import com.walletledger.ledger.domain.model.AccountType
import java.time.Instant

/**
 * Use case to provision a new general ledger account.
 */
interface CreateLedgerAccountUseCase {
    fun createAccount(command: CreateLedgerAccountCommand): Either<LedgerDomainError, LedgerAccountResult>
}

data class CreateLedgerAccountCommand(
    val id: AccountId = AccountId.generate(),
    val accountNumber: String,
    val accountType: AccountType,
    val currency: CurrencyCode,
    val initialBalanceMinor: Long = 0L
)

/**
 * Use case to query a ledger account balance.
 */
interface GetAccountBalanceUseCase {
    fun getAccountBalance(accountId: AccountId): Either<LedgerDomainError, LedgerAccountResult>
    fun getAccountByNumber(accountNumber: String): Either<LedgerDomainError, LedgerAccountResult>
}

data class LedgerAccountResult(
    val id: AccountId,
    val accountNumber: String,
    val accountType: AccountType,
    val currency: CurrencyCode,
    val balance: Money,
    val status: AccountStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)
