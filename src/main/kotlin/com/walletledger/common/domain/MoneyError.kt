package com.walletledger.common.domain

/**
 * Sealed hierarchy representing all domain failure cases during monetary calculations,
 * currency conversions, or allocation operations.
 */
sealed interface MoneyError {

    /**
     * Attempted arithmetic operation between incompatible currencies.
     */
    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode
    ) : MoneyError {
        val message: String = "Cannot operate between incompatible currencies: expected $expected, got $actual"
    }

    /**
     * Arithmetic operation resulted in an integer overflow or underflow.
     */
    data class ArithmeticOverflow(
        val message: String
    ) : MoneyError

    /**
     * A negative amount was provided where strictly non-negative values are required.
     */
    data class NegativeAmountNotAllowed(
        val amountMinorUnits: Long
    ) : MoneyError {
        val message: String = "Negative monetary amount is not allowed: $amountMinorUnits"
    }

    /**
     * A zero amount was provided where strictly positive values are required.
     */
    data class ZeroAmountNotAllowed(
        val message: String = "Monetary amount must be strictly greater than zero"
    ) : MoneyError

    /**
     * An allocation ratio array was invalid (e.g. empty, all zeros, or containing negative weights).
     */
    data class InvalidRatioAllocation(
        val message: String
    ) : MoneyError

    /**
     * An invalid ISO 4217 currency code format was supplied.
     */
    data class InvalidCurrencyCode(
        val code: String
    ) : MoneyError {
        val message: String = "Invalid ISO 4217 currency code: '$code'. Must be 3 uppercase alphabetic characters."
    }

    /**
     * Precision or scale conversion error.
     */
    data class InvalidPrecision(
        val message: String
    ) : MoneyError
}
