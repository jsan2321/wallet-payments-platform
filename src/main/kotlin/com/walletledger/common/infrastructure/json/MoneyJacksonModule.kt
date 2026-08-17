package com.walletledger.common.infrastructure.json

import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * Custom Jackson 3 Serializer for Money value object.
 */
class MoneyJsonSerializer : ValueSerializer<Money>() {
    override fun serialize(value: Money, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeNumberProperty("amountMinorUnits", value.amountMinorUnits)
        gen.writeStringProperty("currency", value.currency.value)
        gen.writeStringProperty("formatted", value.toFormattedString())
        gen.writeEndObject()
    }
}

/**
 * Custom Jackson 3 Deserializer for Money value object.
 */
class MoneyJsonDeserializer : ValueDeserializer<Money>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Money {
        val node: JsonNode = p.readValueAsTree()
        val currencyCodeStr = node.get("currency")?.asString()
            ?: throw IllegalArgumentException("Missing 'currency' field in Money JSON payload")
        val currency = CurrencyCode.of(currencyCodeStr).fold(
            { throw IllegalArgumentException(it.message) },
            { it }
        )

        return if (node.has("amountMinorUnits")) {
            val amountMinor = node.get("amountMinorUnits").asLong()
            Money.ofMinor(amountMinor, currency)
        } else if (node.has("amount")) {
            val amountMajor = node.get("amount").decimalValue()
            Money.ofMajor(amountMajor, currency).fold(
                { throw IllegalArgumentException(it.message) },
                { it }
            )
        } else {
            throw IllegalArgumentException("Money JSON payload must contain either 'amountMinorUnits' or 'amount'")
        }
    }
}

fun createMoneyModule(): SimpleModule {
    return SimpleModule("MoneyModule").apply {
        addSerializer(Money::class.java, MoneyJsonSerializer())
        addDeserializer(Money::class.java, MoneyJsonDeserializer())
    }
}
