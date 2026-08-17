package com.walletledger.common.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class CurrencyCodeSpec : StringSpec({

    "valid 3-letter uppercase currency codes are accepted" {
        val usdResult = CurrencyCode.of("USD")
        usdResult.isRight() shouldBe true
        usdResult.getOrNull()?.value shouldBe "USD"

        val eurResult = CurrencyCode.of("eur") // Lowercase should be normalized
        eurResult.isRight() shouldBe true
        eurResult.getOrNull()?.value shouldBe "EUR"
    }

    "invalid currency codes are rejected with InvalidCurrencyCode error" {
        CurrencyCode.of("US").isLeft() shouldBe true
        CurrencyCode.of("USDT").isLeft() shouldBe true
        CurrencyCode.of("123").isLeft() shouldBe true
        CurrencyCode.of("").isLeft() shouldBe true

        val error = CurrencyCode.of("US1").swap().getOrNull()
        error.shouldBeInstanceOf<MoneyError.InvalidCurrencyCode>()
    }

    "minor unit exponents match ISO 4217 specifications" {
        CurrencyCode.USD.minorUnitExponent shouldBe 2
        CurrencyCode.USD.minorUnitScaleFactor shouldBe 100L

        CurrencyCode.EUR.minorUnitExponent shouldBe 2
        CurrencyCode.EUR.minorUnitScaleFactor shouldBe 100L

        CurrencyCode.JPY.minorUnitExponent shouldBe 0
        CurrencyCode.JPY.minorUnitScaleFactor shouldBe 1L

        val bhd = CurrencyCode.of("BHD").getOrNull()!!
        bhd.minorUnitExponent shouldBe 3
        bhd.minorUnitScaleFactor shouldBe 1000L
    }
})
