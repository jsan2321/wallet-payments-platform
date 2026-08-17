package com.walletledger.ledger.application.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.walletledger.common.domain.AccountId
import com.walletledger.ledger.application.port.input.CreateLedgerAccountCommand
import com.walletledger.ledger.application.port.input.CreateLedgerAccountUseCase
import com.walletledger.ledger.application.port.input.GetAccountBalanceUseCase
import com.walletledger.ledger.application.port.input.LedgerAccountResult
import com.walletledger.ledger.application.port.output.LedgerAccountRepositoryPort
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.model.LedgerAccount
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerAccountService(
    private val ledgerAccountRepository: LedgerAccountRepositoryPort
) : CreateLedgerAccountUseCase, GetAccountBalanceUseCase {

    @Transactional
    override fun createAccount(command: CreateLedgerAccountCommand): Either<LedgerDomainError, LedgerAccountResult> {
        val existing = ledgerAccountRepository.findByAccountNumber(command.accountNumber)
        if (existing != null) {
            return LedgerDomainError.DuplicateEntry(command.id.value).left()
        }

        val account = LedgerAccount.create(
            id = command.id,
            accountNumber = command.accountNumber,
            accountType = command.accountType,
            currency = command.currency,
            initialBalanceMinor = command.initialBalanceMinor
        )

        val saved = ledgerAccountRepository.save(account)
        return saved.toResult().right()
    }

    @Transactional(readOnly = true)
    override fun getAccountBalance(accountId: AccountId): Either<LedgerDomainError, LedgerAccountResult> {
        val account = ledgerAccountRepository.findById(accountId)
            ?: return LedgerDomainError.AccountNotFound(accountId).left()
        return account.toResult().right()
    }

    @Transactional(readOnly = true)
    override fun getAccountByNumber(accountNumber: String): Either<LedgerDomainError, LedgerAccountResult> {
        val account = ledgerAccountRepository.findByAccountNumber(accountNumber)
            ?: return LedgerDomainError.AccountNotFound(AccountId.generate()).left()
        return account.toResult().right()
    }

    private fun LedgerAccount.toResult(): LedgerAccountResult = LedgerAccountResult(
        id = id,
        accountNumber = accountNumber,
        accountType = accountType,
        currency = currency,
        balance = balance,
        status = status,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
