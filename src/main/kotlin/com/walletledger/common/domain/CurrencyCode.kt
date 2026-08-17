package com.walletledger.common.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * ISO 4217 Currency Representation.
 * Encapsulates the currency code and its standard fractional minor unit exponent (decimal places).
 */
@JvmInline
value class CurrencyCode private constructor(val value: String) {

    /**
     * Number of decimal places (minor unit exponent) for this currency.
     * Standard ISO 4217:
     * - Most fiat currencies (USD, EUR, GBP, BRL, AUD, CAD) have 2 decimal places (exponent = 2).
     * - Zero-decimal currencies (JPY, KRW, CLP, VND) have 0 decimal places (exponent = 0).
     * - Three-decimal currencies (BHD, KWD, OMR, JOD) have 3 decimal places (exponent = 3).
     */
    val minorUnitExponent: Int
        get() = when (value) {
            "JPY", "KRW", "CLP", "VND", "UGX", "RWF", "PYG" -> 0
            "BHD", "KWD", "OMR", "JOD", "TND", "LYD", "IQD" -> 3
            else -> 2 // Default standard ISO 4217 exponent (USD, EUR, GBP, etc.)
        }

    /**
     * Returns 10^minorUnitExponent as a scaling multiplier.
     */
    val minorUnitScaleFactor: Long
        get() = when (minorUnitExponent) {
            0 -> 1L
            1 -> 10L
            2 -> 100L
            3 -> 1000L
            4 -> 10000L
            else -> Math.pow(10.0, minorUnitExponent.toDouble()).toLong()
        }

    override fun toString(): String = value

    companion object {
        private val ISO_CODE_REGEX = Regex("^[A-Z]{3}$")

        // Predefined common currencies for convenient access
        val USD = CurrencyCode("USD")
        val EUR = CurrencyCode("EUR")
        val GBP = CurrencyCode("GBP")
        val JPY = CurrencyCode("JPY")
        val BRL = CurrencyCode("BRL")
        val CAD = CurrencyCode("CAD")
        val AUD = CurrencyCode("AUD")
        val CHF = CurrencyCode("CHF")

        /**
         * Validates and creates a CurrencyCode from an input string.
         * Returns Either.Left with InvalidCurrencyCode if the code is not a valid 3-letter ISO code.
         */
        fun of(code: String): Either<MoneyError.InvalidCurrencyCode, CurrencyCode> {
            val normalized = code.trim().uppercase()
            return if (ISO_CODE_REGEX.matches(normalized)) {
                CurrencyCode(normalized).right()
            } else {
                MoneyError.InvalidCurrencyCode(code).left()
            }
        }

        /**
         * Unsafe factory for trusted constant definitions or migrations.
         * Throws IllegalArgumentException on invalid input.
         */
        fun unsafe(code: String): CurrencyCode {
            return of(code).fold(
                { throw IllegalArgumentException(it.message) },
                { it }
            )
        }
    }
}
