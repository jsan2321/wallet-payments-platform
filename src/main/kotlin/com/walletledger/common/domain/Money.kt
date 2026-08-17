package com.walletledger.common.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Immutable Monetary Value Object representing an exact monetary value.
 *
 * Stored internally as an integer amount of minor units (e.g. cents for USD, pence for GBP, yen for JPY).
 * Floating point types (Float, Double) are strictly prohibited to prevent financial rounding errors.
 */
@ConsistentCopyVisibility
data class Money private constructor(
    val amountMinorUnits: Long,
    val currency: CurrencyCode
) : Comparable<Money> {

    /**
     * Checks if this money amount is zero.
     */
    val isZero: Boolean
        get() = amountMinorUnits == 0L

    /**
     * Checks if this money amount is strictly positive (> 0).
     */
    val isPositive: Boolean
        get() = amountMinorUnits > 0L

    /**
     * Checks if this money amount is strictly negative (< 0).
     */
    val isNegative: Boolean
        get() = amountMinorUnits < 0L

    /**
     * Returns the absolute value of this money amount.
     */
    fun abs(): Either<MoneyError.ArithmeticOverflow, Money> {
        return try {
            Money(Math.abs(amountMinorUnits), currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Cannot compute absolute value of Long.MIN_VALUE").left()
        }
    }

    /**
     * Negates the monetary amount.
     */
    fun negate(): Either<MoneyError.ArithmeticOverflow, Money> {
        return try {
            Money(Math.negateExact(amountMinorUnits), currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Arithmetic overflow while negating $amountMinorUnits").left()
        }
    }

    /**
     * Adds another monetary amount of the same currency.
     * Fails with CurrencyMismatch if currencies differ, or ArithmeticOverflow on overflow.
     */
    operator fun plus(other: Money): Either<MoneyError, Money> {
        if (this.currency != other.currency) {
            return MoneyError.CurrencyMismatch(expected = this.currency, actual = other.currency).left()
        }
        return try {
            val result = Math.addExact(this.amountMinorUnits, other.amountMinorUnits)
            Money(result, this.currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Arithmetic overflow during addition: $amountMinorUnits + ${other.amountMinorUnits}").left()
        }
    }

    /**
     * Subtracts another monetary amount of the same currency.
     * Fails with CurrencyMismatch if currencies differ, or ArithmeticOverflow on overflow.
     */
    operator fun minus(other: Money): Either<MoneyError, Money> {
        if (this.currency != other.currency) {
            return MoneyError.CurrencyMismatch(expected = this.currency, actual = other.currency).left()
        }
        return try {
            val result = Math.subtractExact(this.amountMinorUnits, other.amountMinorUnits)
            Money(result, this.currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Arithmetic overflow during subtraction: $amountMinorUnits - ${other.amountMinorUnits}").left()
        }
    }

    /**
     * Multiplies this monetary amount by an integer multiplier.
     */
    operator fun times(multiplier: Long): Either<MoneyError.ArithmeticOverflow, Money> {
        return try {
            val result = Math.multiplyExact(this.amountMinorUnits, multiplier)
            Money(result, this.currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Arithmetic overflow during multiplication: $amountMinorUnits * $multiplier").left()
        }
    }

    /**
     * Multiplies this monetary amount by a fractional BigDecimal factor (e.g. tax, interest, or fee percentage).
     */
    fun times(factor: BigDecimal, roundingMode: RoundingMode = RoundingMode.HALF_EVEN): Either<MoneyError.ArithmeticOverflow, Money> {
        return try {
            val current = BigDecimal.valueOf(amountMinorUnits)
            val multiplied = current.multiply(factor).setScale(0, roundingMode)
            val resultLong = multiplied.longValueExact()
            Money(resultLong, this.currency).right()
        } catch (e: ArithmeticException) {
            MoneyError.ArithmeticOverflow("Overflow or invalid precision when multiplying $amountMinorUnits by $factor").left()
        }
    }

    /**
     * Allocates this monetary amount into N equal parts, distributing remainder cents deterministically
     * so that sum(allocated parts) exactly equals the original amount.
     *
     * Example: $1.00 (100 cents) allocated into 3 parts -> [$0.34, $0.33, $0.33] (sum = $1.00).
     */
    fun allocate(parts: Int): Either<MoneyError, List<Money>> {
        if (parts <= 0) {
            return MoneyError.InvalidRatioAllocation("Number of allocation parts must be greater than zero, got $parts").left()
        }
        val ratios = List(parts) { 1 }
        return allocate(ratios)
    }

    /**
     * Allocates this monetary amount according to a list of integer ratios.
     * Preserves financial exactness: sum(allocated) == this.amountMinorUnits (no lost cents).
     *
     * Algorithm (Foote-Fowler Allocation):
     * 1. Calculate each share = floor(total * ratio[i] / sum(ratios))
     * 2. Calculate remainder = total - sum(shares)
     * 3. Distribute 1 minor unit to the first `remainder` shares.
     */
    fun allocate(ratios: List<Int>): Either<MoneyError, List<Money>> {
        if (ratios.isEmpty()) {
            return MoneyError.InvalidRatioAllocation("Allocation ratios cannot be empty").left()
        }
        if (ratios.any { it < 0 }) {
            return MoneyError.InvalidRatioAllocation("Allocation ratios cannot contain negative weights").left()
        }
        val totalWeight = ratios.sumOf { it.toLong() }
        if (totalWeight == 0L) {
            return MoneyError.InvalidRatioAllocation("Total sum of allocation ratios must be greater than zero").left()
        }

        val totalAmount = this.amountMinorUnits
        val results = LongArray(ratios.size)
        var allocatedSum = 0L

        for (i in ratios.indices) {
            val share = (totalAmount * ratios[i].toLong()) / totalWeight
            results[i] = share
            allocatedSum += share
        }

        var remainder = totalAmount - allocatedSum
        val step = if (remainder >= 0) 1L else -1L

        var idx = 0
        while (remainder != 0L) {
            results[idx % results.size] += step
            remainder -= step
            idx++
        }

        return results.map { Money(it, this.currency) }.right()
    }

    /**
     * Converts to human-readable BigDecimal in major units (e.g. 1050 cents -> 10.50).
     */
    fun toMajorUnits(): BigDecimal {
        return BigDecimal.valueOf(amountMinorUnits)
            .divide(BigDecimal.valueOf(currency.minorUnitScaleFactor), currency.minorUnitExponent, RoundingMode.UNNECESSARY)
    }

    /**
     * Formats the monetary amount as a localized currency string.
     */
    fun toFormattedString(): String {
        val majorUnits = toMajorUnits()
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            decimalSeparator = '.'
            groupingSeparator = ','
        }
        val pattern = if (currency.minorUnitExponent == 0) {
            "#,##0"
        } else {
            "#,##0." + "0".repeat(currency.minorUnitExponent)
        }
        val formatter = DecimalFormat(pattern, symbols)
        return "${formatter.format(majorUnits)} ${currency.value}"
    }

    override fun compareTo(other: Money): Int {
        if (this.currency != other.currency) {
            throw IllegalArgumentException("Cannot compare Money with different currencies: ${this.currency} vs ${other.currency}")
        }
        return this.amountMinorUnits.compareTo(other.amountMinorUnits)
    }

    override fun toString(): String = toFormattedString()

    companion object {
        /**
         * Creates a Money instance from raw minor units and currency code.
         */
        fun ofMinor(amountMinorUnits: Long, currency: CurrencyCode): Money {
            return Money(amountMinorUnits, currency)
        }

        /**
         * Creates a zero-value Money instance for a given currency.
         */
        fun zero(currency: CurrencyCode): Money {
            return Money(0L, currency)
        }

        /**
         * Creates Money from a major unit BigDecimal (e.g. 10.50 USD -> 1050 minor units).
         * Fails if the BigDecimal has more decimal places than allowed by ISO 4217 for the currency.
         */
        fun ofMajor(amount: BigDecimal, currency: CurrencyCode): Either<MoneyError.InvalidPrecision, Money> {
            val scaleFactor = BigDecimal.valueOf(currency.minorUnitScaleFactor)
            return try {
                val scaled = amount.multiply(scaleFactor)
                if (scaled.stripTrailingZeros().scale() > 0) {
                    return MoneyError.InvalidPrecision(
                        "Amount $amount has fractional units exceeding ${currency.minorUnitExponent} decimal places for ${currency.value}"
                    ).left()
                }
                val minorUnits = scaled.setScale(0, RoundingMode.UNNECESSARY).longValueExact()
                Money(minorUnits, currency).right()
            } catch (e: ArithmeticException) {
                MoneyError.InvalidPrecision("Cannot accurately convert $amount to minor units for ${currency.value}: ${e.message}").left()
            }
        }
    }
}
