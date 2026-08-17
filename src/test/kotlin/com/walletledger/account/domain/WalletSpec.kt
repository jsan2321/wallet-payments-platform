package com.walletledger.account.domain

import com.walletledger.account.domain.error.AccountDomainError
import com.walletledger.account.domain.model.Wallet
import com.walletledger.account.domain.model.WalletAccount
import com.walletledger.account.domain.model.WalletTier
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

class WalletSpec : StringSpec({

    val walletId = WalletId.generate()
    val ledgerAccId = AccountId.generate()
    val ownerId = OwnerId("usr_test_123")

    "WalletAccount initializes with strict balance invariant" {
        val account = WalletAccount.create(
            walletId = walletId,
            ledgerAccountId = ledgerAccId,
            currency = CurrencyCode.USD,
            initialAvailableMinor = 10000L,
            initialHeldMinor = 2000L
        )

        account.availableBalance.amountMinorUnits shouldBe 10000L
        account.heldBalance.amountMinorUnits shouldBe 2000L
        account.totalBalance.amountMinorUnits shouldBe 12000L
    }

    "placeHold shifts funds from available to held keeping total constant" {
        val account = WalletAccount.create(
            walletId = walletId,
            ledgerAccountId = ledgerAccId,
            currency = CurrencyCode.USD,
            initialAvailableMinor = 10000L // $100.00
        )

        val holdAmount = Money.ofMinor(3000L, CurrencyCode.USD) // $30.00
        val holdResult = account.placeHold(holdAmount)

        holdResult.isRight() shouldBe true
        val updated = holdResult.getOrNull()!!
        updated.availableBalance.amountMinorUnits shouldBe 7000L // $70.00
        updated.heldBalance.amountMinorUnits shouldBe 3000L      // $30.00
        updated.totalBalance.amountMinorUnits shouldBe 10000L    // $100.00 (invariant preserved)
    }

    "placeHold fails if available balance is insufficient" {
        val account = WalletAccount.create(
            walletId = walletId,
            ledgerAccountId = ledgerAccId,
            currency = CurrencyCode.USD,
            initialAvailableMinor = 2000L // $20.00
        )

        val holdAmount = Money.ofMinor(5000L, CurrencyCode.USD) // $50.00
        val result = account.placeHold(holdAmount)

        result.isLeft() shouldBe true
        result.swap().getOrNull().shouldBeInstanceOf<AccountDomainError.InsufficientAvailableFunds>()
    }

    "releaseHold shifts funds from held back to available" {
        val account = WalletAccount.create(
            walletId = walletId,
            ledgerAccountId = ledgerAccId,
            currency = CurrencyCode.USD,
            initialAvailableMinor = 7000L,
            initialHeldMinor = 3000L
        )

        val releaseAmount = Money.ofMinor(1000L, CurrencyCode.USD)
        val releaseResult = account.releaseHold(releaseAmount)

        releaseResult.isRight() shouldBe true
        val updated = releaseResult.getOrNull()!!
        updated.availableBalance.amountMinorUnits shouldBe 8000L
        updated.heldBalance.amountMinorUnits shouldBe 2000L
        updated.totalBalance.amountMinorUnits shouldBe 10000L
    }

    "Wallet enforces single transaction spend limit" {
        val wallet = Wallet.create(
            id = walletId,
            ownerId = ownerId,
            tier = WalletTier.TIER_1_BASIC // Single tx limit: $1,000 (100,000 minor units)
        )

        val allowedTx = Money.ofMinor(80000L, CurrencyCode.USD) // $800.00
        val allowedResult = wallet.validateSpendLimits(allowedTx, Money.zero(CurrencyCode.USD))
        allowedResult.isRight() shouldBe true

        val excessiveTx = Money.ofMinor(150000L, CurrencyCode.USD) // $1,500.00
        val excessiveResult = wallet.validateSpendLimits(excessiveTx, Money.zero(CurrencyCode.USD))
        excessiveResult.isLeft() shouldBe true
        excessiveResult.swap().getOrNull().shouldBeInstanceOf<AccountDomainError.SingleTransactionLimitExceeded>()
    }

    "Wallet enforces daily cumulative spend limit" {
        val wallet = Wallet.create(
            id = walletId,
            ownerId = ownerId,
            tier = WalletTier.TIER_1_BASIC // Daily limit: $2,000 (200,000 minor units)
        )

        val currentSpend = Money.ofMinor(150000L, CurrencyCode.USD) // $1,500 spent today
        val newTx = Money.ofMinor(60000L, CurrencyCode.USD)         // $600 attempt (Total $2,100 > $2,000)

        val result = wallet.validateSpendLimits(newTx, currentSpend, LocalDate.now())
        result.isLeft() shouldBe true
        result.swap().getOrNull().shouldBeInstanceOf<AccountDomainError.DailySpendLimitExceeded>()
    }
})
