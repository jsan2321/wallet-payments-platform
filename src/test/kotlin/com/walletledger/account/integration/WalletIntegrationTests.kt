package com.walletledger.account.integration

import com.walletledger.TestcontainersConfiguration
import com.walletledger.account.application.port.input.AddCurrencyAccountCommand
import com.walletledger.account.application.port.input.AddCurrencyAccountUseCase
import com.walletledger.account.application.port.input.CheckSpendLimitUseCase
import com.walletledger.account.application.port.input.CreateWalletCommand
import com.walletledger.account.application.port.input.CreateWalletUseCase
import com.walletledger.account.application.port.input.GetWalletUseCase
import com.walletledger.account.application.port.input.PlaceHoldCommand
import com.walletledger.account.application.port.input.PlaceHoldUseCase
import com.walletledger.account.application.port.input.ReleaseHoldCommand
import com.walletledger.account.application.port.input.ReleaseHoldUseCase
import com.walletledger.account.domain.error.AccountDomainError
import com.walletledger.account.domain.model.WalletTier
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import com.walletledger.ledger.application.port.input.GetAccountBalanceUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WalletIntegrationTests {

    @Autowired
    private lateinit var createWalletUseCase: CreateWalletUseCase

    @Autowired
    private lateinit var addCurrencyAccountUseCase: AddCurrencyAccountUseCase

    @Autowired
    private lateinit var getWalletUseCase: GetWalletUseCase

    @Autowired
    private lateinit var placeHoldUseCase: PlaceHoldUseCase

    @Autowired
    private lateinit var releaseHoldUseCase: ReleaseHoldUseCase

    @Autowired
    private lateinit var checkSpendLimitUseCase: CheckSpendLimitUseCase

    @Autowired
    private lateinit var getLedgerAccountBalanceUseCase: GetAccountBalanceUseCase

    @Test
    fun `should successfully provision wallet and create corresponding double-entry ledger account`() {
        val walletId = WalletId.generate()
        val ownerId = OwnerId("usr_test_alpha_01")

        val command = CreateWalletCommand(
            id = walletId,
            ownerId = ownerId,
            baseCurrency = CurrencyCode.USD,
            tier = WalletTier.STANDARD
        )

        val result = createWalletUseCase.createWallet(command)
        assertTrue(result.isRight(), "Expected wallet creation to succeed")

        val wallet = result.getOrNull()!!
        assertEquals(walletId, wallet.id)
        assertEquals(ownerId, wallet.ownerId)
        assertEquals(1, wallet.accounts.size)

        val baseAccount = wallet.accounts[0]
        assertEquals(CurrencyCode.USD, baseAccount.currency)
        assertEquals(0L, baseAccount.availableBalance.amountMinorUnits)

        // Verify that the backing Double-Entry General Ledger Account was provisioned in the ledger module
        val ledgerAccountResult = getLedgerAccountBalanceUseCase.getAccountBalance(baseAccount.ledgerAccountId)
        assertTrue(ledgerAccountResult.isRight(), "Expected backing ledger account to exist")
        val ledgerAccount = ledgerAccountResult.getOrNull()!!
        assertEquals(CurrencyCode.USD, ledgerAccount.currency)
        assertEquals(0L, ledgerAccount.balance.amountMinorUnits)
    }

    @Test
    fun `should add multiple currency sub-accounts to an existing wallet`() {
        val walletId = WalletId.generate()
        val ownerId = OwnerId("usr_test_multi_curr_02")

        createWalletUseCase.createWallet(
            CreateWalletCommand(
                id = walletId,
                ownerId = ownerId,
                baseCurrency = CurrencyCode.USD
            )
        )

        // Add EUR sub-account
        val addEurResult = addCurrencyAccountUseCase.addCurrencyAccount(
            AddCurrencyAccountCommand(
                walletId = walletId,
                currency = CurrencyCode.EUR
            )
        )
        assertTrue(addEurResult.isRight(), "Expected EUR account addition to succeed")

        // Query wallet to verify both sub-accounts exist
        val wallet = getWalletUseCase.getWalletById(walletId).getOrNull()!!
        assertEquals(2, wallet.accounts.size)
        assertTrue(wallet.accounts.any { it.currency == CurrencyCode.USD })
        assertTrue(wallet.accounts.any { it.currency == CurrencyCode.EUR })

        // Attempting to add duplicate USD account must fail
        val duplicateResult = addCurrencyAccountUseCase.addCurrencyAccount(
            AddCurrencyAccountCommand(
                walletId = walletId,
                currency = CurrencyCode.USD
            )
        )
        assertTrue(duplicateResult.isLeft(), "Expected duplicate currency addition to fail")
        assertTrue(duplicateResult.swap().getOrNull() is AccountDomainError.CurrencyAccountAlreadyExists)
    }

    @Test
    fun `should enforce daily spend limit with atomic accumulator`() {
        val walletId = WalletId.generate()
        val ownerId = OwnerId("usr_test_limits_03")

        createWalletUseCase.createWallet(
            CreateWalletCommand(
                id = walletId,
                ownerId = ownerId,
                baseCurrency = CurrencyCode.USD,
                tier = WalletTier.TIER_1_BASIC // Daily limit: $2,000 (200,000 minor units), Single Tx: $1,000 (100,000 minor units)
            )
        )

        val today = LocalDate.now()

        // 1. Spend $800.00 (within limits)
        val spend1 = checkSpendLimitUseCase.checkAndAccumulateSpend(
            walletId = walletId,
            amount = Money.ofMinor(80000L, CurrencyCode.USD),
            date = today
        )
        assertTrue(spend1.isRight())

        // 2. Spend another $800.00 (accumulated = $1,600.00, within $2,000 daily limit)
        val spend2 = checkSpendLimitUseCase.checkAndAccumulateSpend(
            walletId = walletId,
            amount = Money.ofMinor(80000L, CurrencyCode.USD),
            date = today
        )
        assertTrue(spend2.isRight())

        // 3. Spend $600.00 (would bring total to $2,200.00 > $2,000 daily limit) -> Must fail
        val spend3 = checkSpendLimitUseCase.checkAndAccumulateSpend(
            walletId = walletId,
            amount = Money.ofMinor(60000L, CurrencyCode.USD),
            date = today
        )
        assertTrue(spend3.isLeft(), "Expected daily spend limit to be exceeded")
        assertTrue(spend3.swap().getOrNull() is AccountDomainError.DailySpendLimitExceeded)
    }
}
