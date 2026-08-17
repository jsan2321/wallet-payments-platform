package com.walletledger.common.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.math.RoundingMode

class MoneySpec : StringSpec({

    "addition of same currency produces exact sum" {
        val a = Money.ofMinor(1050L, CurrencyCode.USD) // $10.50
        val b = Money.ofMinor(450L, CurrencyCode.USD)  // $4.50

        val result = a + b
        result.isRight() shouldBe true
        result.getOrNull()?.amountMinorUnits shouldBe 1500L
        result.getOrNull()?.toFormattedString() shouldBe "15.00 USD"
    }

    "addition of different currencies fails with CurrencyMismatch" {
        val usd = Money.ofMinor(1000L, CurrencyCode.USD)
        val eur = Money.ofMinor(1000L, CurrencyCode.EUR)

        val result = usd + eur
        result.isLeft() shouldBe true
        val error = result.swap().getOrNull()
        error.shouldBeInstanceOf<MoneyError.CurrencyMismatch>()
        error.expected shouldBe CurrencyCode.USD
        error.actual shouldBe CurrencyCode.EUR
    }

    "subtraction of same currency produces exact difference" {
        val a = Money.ofMinor(1000L, CurrencyCode.USD)
        val b = Money.ofMinor(350L, CurrencyCode.USD)

        val result = a - b
        result.isRight() shouldBe true
        result.getOrNull()?.amountMinorUnits shouldBe 650L
    }

    "subtraction resulting in negative balance is permitted for intermediate calculations" {
        val a = Money.ofMinor(300L, CurrencyCode.USD)
        val b = Money.ofMinor(500L, CurrencyCode.USD)

        val result = a - b
        result.isRight() shouldBe true
        result.getOrNull()?.amountMinorUnits shouldBe -200L
        result.getOrNull()?.isNegative shouldBe true
    }

    "arithmetic overflow during addition is caught safely" {
        val maxMoney = Money.ofMinor(Long.MAX_VALUE, CurrencyCode.USD)
        val oneCent = Money.ofMinor(1L, CurrencyCode.USD)

        val result = maxMoney + oneCent
        result.isLeft() shouldBe true
        result.swap().getOrNull().shouldBeInstanceOf<MoneyError.ArithmeticOverflow>()
    }

    "integer multiplication calculates exact amount" {
        val money = Money.ofMinor(250L, CurrencyCode.USD) // $2.50
        val result = (money * 4L)
        result.isRight() shouldBe true
        result.getOrNull()?.amountMinorUnits shouldBe 1000L
    }

    "percentage multiplication with BigDecimal rounds according to specified RoundingMode" {
        val money = Money.ofMinor(1000L, CurrencyCode.USD) // $10.00
        val taxRate = BigDecimal("0.0825") // 8.25% (1000 * 0.0825 = 82.5)

        // HALF_EVEN (Banker's rounding) rounds 82.5 to nearest even integer (82)
        val resultHalfEven = money.times(taxRate, RoundingMode.HALF_EVEN)
        resultHalfEven.isRight() shouldBe true
        resultHalfEven.getOrNull()?.amountMinorUnits shouldBe 82L

        // HALF_UP rounds 82.5 up to 83
        val resultHalfUp = money.times(taxRate, RoundingMode.HALF_UP)
        resultHalfUp.isRight() shouldBe true
        resultHalfUp.getOrNull()?.amountMinorUnits shouldBe 83L
    }

    "equal parts allocation distributes cents without losing remainders" {
        // $1.00 (100 cents) divided into 3 equal parts
        val dollar = Money.ofMinor(100L, CurrencyCode.USD)
        val allocation = dollar.allocate(3)

        allocation.isRight() shouldBe true
        val parts = allocation.getOrNull()!!
        parts.size shouldBe 3
        parts.map { it.amountMinorUnits } shouldContainExactly listOf(34L, 33L, 33L)
        parts.sumOf { it.amountMinorUnits } shouldBe 100L
    }

    "weighted ratios allocation distributes cents proportionally with zero remainder loss" {
        // $10.00 (1000 cents) allocated with weights [1, 2, 3] (Total 6 parts)
        // 1000 * 1/6 = 166.666... -> 167
        // 1000 * 2/6 = 333.333... -> 333
        // 1000 * 3/6 = 500.000... -> 500
        val tenDollars = Money.ofMinor(1000L, CurrencyCode.USD)
        val allocation = tenDollars.allocate(listOf(1, 2, 3))

        allocation.isRight() shouldBe true
        val parts = allocation.getOrNull()!!
        parts.map { it.amountMinorUnits } shouldContainExactly listOf(167L, 333L, 500L)
        parts.sumOf { it.amountMinorUnits } shouldBe 1000L
    }

    "creation from major unit BigDecimal enforces precision rules" {
        val valid = Money.ofMajor(BigDecimal("10.50"), CurrencyCode.USD)
        valid.isRight() shouldBe true
        valid.getOrNull()?.amountMinorUnits shouldBe 1050L

        // JPY allows 0 decimals
        val validJpy = Money.ofMajor(BigDecimal("500"), CurrencyCode.JPY)
        validJpy.isRight() shouldBe true
        validJpy.getOrNull()?.amountMinorUnits shouldBe 500L

        // JPY with decimals must fail
        val invalidJpy = Money.ofMajor(BigDecimal("500.50"), CurrencyCode.JPY)
        invalidJpy.isLeft() shouldBe true
        invalidJpy.swap().getOrNull().shouldBeInstanceOf<MoneyError.InvalidPrecision>()

        // USD with 3 decimals must fail
        val invalidUsd = Money.ofMajor(BigDecimal("10.505"), CurrencyCode.USD)
        invalidUsd.isLeft() shouldBe true
    }

    "absolute and negation operations function accurately" {
        val negative = Money.ofMinor(-500L, CurrencyCode.USD)
        negative.abs().getOrNull()?.amountMinorUnits shouldBe 500L
        negative.negate().getOrNull()?.amountMinorUnits shouldBe 500L

        val positive = Money.ofMinor(500L, CurrencyCode.USD)
        positive.negate().getOrNull()?.amountMinorUnits shouldBe -500L
    }

    "comparison between same currency respects amount magnitude" {
        val a = Money.ofMinor(100L, CurrencyCode.USD)
        val b = Money.ofMinor(200L, CurrencyCode.USD)
        val c = Money.ofMinor(100L, CurrencyCode.USD)

        (a < b) shouldBe true
        (b > a) shouldBe true
        (a == c) shouldBe true
    }
})
