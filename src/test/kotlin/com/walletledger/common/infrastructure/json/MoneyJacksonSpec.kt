package com.walletledger.common.infrastructure.json

import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class MoneyJacksonSpec : StringSpec({

    val objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .addModule(createMoneyModule())
        .build()

    "Money serializes to JSON with minor units, currency code and formatted string" {
        val money = Money.ofMinor(1050L, CurrencyCode.USD)
        val json = objectMapper.writeValueAsString(money)

        json shouldContain "\"amountMinorUnits\":1050"
        json shouldContain "\"currency\":\"USD\""
        json shouldContain "\"formatted\":\"10.50 USD\""
    }

    "Money deserializes from JSON with amountMinorUnits" {
        val json = """{"amountMinorUnits":2500,"currency":"EUR"}"""
        val money = objectMapper.readValue(json, Money::class.java)

        money.amountMinorUnits shouldBe 2500L
        money.currency shouldBe CurrencyCode.EUR
    }

    "Money deserializes from JSON with major decimal amount" {
        val json = """{"amount":10.50,"currency":"USD"}"""
        val money = objectMapper.readValue(json, Money::class.java)

        money.amountMinorUnits shouldBe 1050L
        money.currency shouldBe CurrencyCode.USD
    }
})
