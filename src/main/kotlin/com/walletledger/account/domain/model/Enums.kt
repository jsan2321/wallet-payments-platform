package com.walletledger.account.domain.model

/**
 * Status of a customer wallet.
 */
enum class WalletStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}

/**
 * KYC / verification tiers controlling default transaction and daily velocity limits.
 */
enum class WalletTier(
    val defaultDailySpendLimitMinor: Long,
    val defaultSingleTxLimitMinor: Long
) {
    STANDARD(1000000L, 500000L),         // $10,000 daily, $5,000 per tx
    TIER_1_BASIC(200000L, 100000L),      // $2,000 daily, $1,000 per tx
    TIER_2_VERIFIED(5000000L, 2500000L),  // $50,000 daily, $25,000 per tx
    TIER_3_ENTERPRISE(50000000L, 20000000L) // $500,000 daily, $200,000 per tx
}

/**
 * Lifecycle state of a currency-specific sub-account.
 */
enum class WalletAccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
