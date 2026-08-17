package com.walletledger.ledger.integration

import com.walletledger.TestcontainersConfiguration
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.application.port.input.CreateLedgerAccountCommand
import com.walletledger.ledger.application.port.input.CreateLedgerAccountUseCase
import com.walletledger.ledger.application.port.input.GetAccountBalanceUseCase
import com.walletledger.ledger.application.port.input.PostingLegCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryCommand
import com.walletledger.ledger.application.port.input.RecordJournalEntryUseCase
import com.walletledger.ledger.domain.error.LedgerDomainError
import com.walletledger.ledger.domain.model.AccountType
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.PostingDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RecordJournalEntryIntegrationTests {

    @Autowired
    private lateinit var createAccountUseCase: CreateLedgerAccountUseCase

    @Autowired
    private lateinit var recordJournalEntryUseCase: RecordJournalEntryUseCase

    @Autowired
    private lateinit var getAccountBalanceUseCase: GetAccountBalanceUseCase

    @Test
    fun `should successfully record balanced journal entry and update account balances`() {
        val accAId = AccountId.generate()
        val accBId = AccountId.generate()

        // 1. Create two LIABILITY (customer wallet) accounts with initial balances
        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accAId,
                accountNumber = "ACC_TEST_001",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 10000L // $100.00
            )
        )

        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accBId,
                accountNumber = "ACC_TEST_002",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 5000L // $50.00
            )
        )

        // 2. Transfer $30.00 (3000 minor units) from Account A to Account B
        // Liability account: Debit decreases balance (-3000), Credit increases balance (+3000)
        val transactionId = TransactionId.generate()
        val command = RecordJournalEntryCommand(
            transactionId = transactionId,
            description = "P2P Payment from AccA to AccB",
            entryType = EntryType.TRANSFER,
            legs = listOf(
                PostingLegCommand(accountId = accAId, direction = PostingDirection.DEBIT, amount = Money.ofMinor(3000L, CurrencyCode.USD)),
                PostingLegCommand(accountId = accBId, direction = PostingDirection.CREDIT, amount = Money.ofMinor(3000L, CurrencyCode.USD))
            )
        )

        val result = recordJournalEntryUseCase.recordJournalEntry(command)
        assertTrue(result.isRight(), "Expected journal entry to be recorded successfully")

        // 3. Verify Account A balance: 10000 - 3000 = 7000 ($70.00)
        val balanceA = getAccountBalanceUseCase.getAccountBalance(accAId).getOrNull()!!
        assertEquals(7000L, balanceA.balance.amountMinorUnits)

        // 4. Verify Account B balance: 5000 + 3000 = 8000 ($80.00)
        val balanceB = getAccountBalanceUseCase.getAccountBalance(accBId).getOrNull()!!
        assertEquals(8000L, balanceB.balance.amountMinorUnits)
    }

    @Test
    fun `should reject unbalanced entry and leave account balances completely unchanged`() {
        val accId1 = AccountId.generate()
        val accId2 = AccountId.generate()

        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accId1,
                accountNumber = "ACC_UNBAL_001",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 10000L
            )
        )

        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accId2,
                accountNumber = "ACC_UNBAL_002",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 10000L
            )
        )

        // Attempt unbalanced transfer: Debit $50.00 vs Credit $40.00
        val command = RecordJournalEntryCommand(
            transactionId = TransactionId.generate(),
            description = "Unbalanced transfer attempt",
            entryType = EntryType.TRANSFER,
            legs = listOf(
                PostingLegCommand(accountId = accId1, direction = PostingDirection.DEBIT, amount = Money.ofMinor(5000L, CurrencyCode.USD)),
                PostingLegCommand(accountId = accId2, direction = PostingDirection.CREDIT, amount = Money.ofMinor(4000L, CurrencyCode.USD))
            )
        )

        val result = recordJournalEntryUseCase.recordJournalEntry(command)
        assertTrue(result.isLeft(), "Expected unbalanced entry to be rejected")
        assertTrue(result.swap().getOrNull() is LedgerDomainError.UnbalancedEntry)

        // Balances must remain pristine
        val balance1 = getAccountBalanceUseCase.getAccountBalance(accId1).getOrNull()!!
        val balance2 = getAccountBalanceUseCase.getAccountBalance(accId2).getOrNull()!!
        assertEquals(10000L, balance1.balance.amountMinorUnits)
        assertEquals(10000L, balance2.balance.amountMinorUnits)
    }

    @Test
    fun `should prevent deadlocks under concurrent opposing bidirectional transfers`() {
        val accXId = AccountId.generate()
        val accYId = AccountId.generate()

        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accXId,
                accountNumber = "ACC_CONC_X",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 100000L // $1000.00
            )
        )

        createAccountUseCase.createAccount(
            CreateLedgerAccountCommand(
                id = accYId,
                accountNumber = "ACC_CONC_Y",
                accountType = AccountType.LIABILITY,
                currency = CurrencyCode.USD,
                initialBalanceMinor = 100000L // $1000.00
            )
        )

        val threadPool = Executors.newFixedThreadPool(10)
        val transferCount = 20
        val transferAmount = 100L // $1.00 each

        val tasks = mutableListOf<Callable<Boolean>>()

        for (i in 0 until transferCount) {
            val isXtoY = (i % 2 == 0)
            tasks.add(Callable {
                val fromAcc = if (isXtoY) accXId else accYId
                val toAcc = if (isXtoY) accYId else accXId

                val cmd = RecordJournalEntryCommand(
                    transactionId = TransactionId.generate(),
                    description = "Concurrent Transfer #$i",
                    entryType = EntryType.TRANSFER,
                    legs = listOf(
                        PostingLegCommand(accountId = fromAcc, direction = PostingDirection.DEBIT, amount = Money.ofMinor(transferAmount, CurrencyCode.USD)),
                        PostingLegCommand(accountId = toAcc, direction = PostingDirection.CREDIT, amount = Money.ofMinor(transferAmount, CurrencyCode.USD))
                    )
                )
                val res = recordJournalEntryUseCase.recordJournalEntry(cmd)
                res.isRight()
            })
        }

        val futures = threadPool.invokeAll(tasks)
        val successCount = futures.count { it.get() }

        assertEquals(transferCount, successCount, "All concurrent transfers must succeed without deadlock")

        // Total system money invariant: sum(balanceX + balanceY) must remain exactly 200,000 minor units
        val balanceX = getAccountBalanceUseCase.getAccountBalance(accXId).getOrNull()!!.balance.amountMinorUnits
        val balanceY = getAccountBalanceUseCase.getAccountBalance(accYId).getOrNull()!!.balance.amountMinorUnits
        assertEquals(200000L, balanceX + balanceY, "Total money across accounts must be conserved")

        threadPool.shutdown()
    }
}
