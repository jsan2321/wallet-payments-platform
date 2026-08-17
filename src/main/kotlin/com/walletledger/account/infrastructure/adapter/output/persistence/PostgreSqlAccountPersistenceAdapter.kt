package com.walletledger.account.infrastructure.adapter.output.persistence

import com.walletledger.account.application.port.output.SpendAccumulatorRepositoryPort
import com.walletledger.account.application.port.output.WalletAccountRepositoryPort
import com.walletledger.account.application.port.output.WalletRepositoryPort
import com.walletledger.account.domain.model.Wallet
import com.walletledger.account.domain.model.WalletAccount
import com.walletledger.account.domain.model.WalletAccountStatus
import com.walletledger.account.domain.model.WalletStatus
import com.walletledger.account.domain.model.WalletTier
import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.OwnerId
import com.walletledger.common.domain.WalletId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID

@Repository
class PostgreSqlAccountPersistenceAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : WalletRepositoryPort, WalletAccountRepositoryPort, SpendAccumulatorRepositoryPort {

    private val walletRowMapper = RowMapper<Wallet> { rs: ResultSet, _ ->
        Wallet(
            id = WalletId(rs.getObject("id", UUID::class.java)),
            ownerId = OwnerId(rs.getString("owner_id")),
            status = WalletStatus.valueOf(rs.getString("status")),
            tier = WalletTier.valueOf(rs.getString("tier")),
            dailySpendLimitMinor = rs.getLong("daily_spend_limit_minor"),
            singleTxLimitMinor = rs.getLong("single_tx_limit_minor"),
            accounts = emptyMap(),
            version = rs.getLong("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private val walletAccountRowMapper = RowMapper<WalletAccount> { rs: ResultSet, _ ->
        val currency = CurrencyCode.unsafe(rs.getString("currency"))
        WalletAccount(
            id = rs.getObject("id", UUID::class.java),
            walletId = WalletId(rs.getObject("wallet_id", UUID::class.java)),
            ledgerAccountId = AccountId(rs.getObject("ledger_account_id", UUID::class.java)),
            currency = currency,
            availableBalance = Money.ofMinor(rs.getLong("available_balance_minor"), currency),
            heldBalance = Money.ofMinor(rs.getLong("held_balance_minor"), currency),
            totalBalance = Money.ofMinor(rs.getLong("total_balance_minor"), currency),
            status = WalletAccountStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    // ========================================================================
    // WalletRepositoryPort
    // ========================================================================

    override fun findById(id: WalletId): Wallet? {
        val sql = "SELECT * FROM account.wallets WHERE id = :id"
        return jdbcTemplate.query(sql, MapSqlParameterSource("id", id.value), walletRowMapper).firstOrNull()
    }

    override fun findByOwnerId(ownerId: OwnerId): Wallet? {
        val sql = "SELECT * FROM account.wallets WHERE owner_id = :ownerId"
        return jdbcTemplate.query(sql, MapSqlParameterSource("ownerId", ownerId.value), walletRowMapper).firstOrNull()
    }

    override fun save(wallet: Wallet): Wallet {
        val sql = """
            INSERT INTO account.wallets (
                id, owner_id, status, tier, daily_spend_limit_minor, single_tx_limit_minor, version, created_at, updated_at
            ) VALUES (
                :id, :ownerId, :status, :tier, :dailySpendLimitMinor, :singleTxLimitMinor, :version, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                tier = EXCLUDED.tier,
                daily_spend_limit_minor = EXCLUDED.daily_spend_limit_minor,
                single_tx_limit_minor = EXCLUDED.single_tx_limit_minor,
                version = EXCLUDED.version,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", wallet.id.value)
            .addValue("ownerId", wallet.ownerId.value)
            .addValue("status", wallet.status.name)
            .addValue("tier", wallet.tier.name)
            .addValue("dailySpendLimitMinor", wallet.dailySpendLimitMinor)
            .addValue("singleTxLimitMinor", wallet.singleTxLimitMinor)
            .addValue("version", wallet.version)
            .addValue("createdAt", Timestamp.from(wallet.createdAt))
            .addValue("updatedAt", Timestamp.from(wallet.updatedAt))

        jdbcTemplate.update(sql, params)
        return wallet
    }

    // ========================================================================
    // WalletAccountRepositoryPort
    // ========================================================================

    override fun findByWalletId(walletId: WalletId): List<WalletAccount> {
        val sql = "SELECT * FROM account.wallet_accounts WHERE wallet_id = :walletId ORDER BY created_at ASC"
        return jdbcTemplate.query(sql, MapSqlParameterSource("walletId", walletId.value), walletAccountRowMapper)
    }

    override fun findByWalletIdAndCurrency(walletId: WalletId, currency: CurrencyCode): WalletAccount? {
        val sql = "SELECT * FROM account.wallet_accounts WHERE wallet_id = :walletId AND currency = :currency"
        val params = MapSqlParameterSource()
            .addValue("walletId", walletId.value)
            .addValue("currency", currency.value)
        return jdbcTemplate.query(sql, params, walletAccountRowMapper).firstOrNull()
    }

    override fun save(account: WalletAccount): WalletAccount {
        saveAll(listOf(account))
        return account
    }

    override fun saveAll(accounts: List<WalletAccount>): List<WalletAccount> {
        if (accounts.isEmpty()) return emptyList()
        val sql = """
            INSERT INTO account.wallet_accounts (
                id, wallet_id, ledger_account_id, currency, available_balance_minor, held_balance_minor, total_balance_minor, status, version, created_at, updated_at
            ) VALUES (
                :id, :walletId, :ledgerAccountId, :currency, :availableBalanceMinor, :heldBalanceMinor, :totalBalanceMinor, :status, :version, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                available_balance_minor = EXCLUDED.available_balance_minor,
                held_balance_minor = EXCLUDED.held_balance_minor,
                total_balance_minor = EXCLUDED.total_balance_minor,
                status = EXCLUDED.status,
                version = EXCLUDED.version,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val batchParams = accounts.map { account ->
            MapSqlParameterSource()
                .addValue("id", account.id)
                .addValue("walletId", account.walletId.value)
                .addValue("ledgerAccountId", account.ledgerAccountId.value)
                .addValue("currency", account.currency.value)
                .addValue("availableBalanceMinor", account.availableBalance.amountMinorUnits)
                .addValue("heldBalanceMinor", account.heldBalance.amountMinorUnits)
                .addValue("totalBalanceMinor", account.totalBalance.amountMinorUnits)
                .addValue("status", account.status.name)
                .addValue("version", account.version)
                .addValue("createdAt", Timestamp.from(account.createdAt))
                .addValue("updatedAt", Timestamp.from(account.updatedAt))
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batchParams)
        return accounts
    }

    // ========================================================================
    // SpendAccumulatorRepositoryPort
    // ========================================================================

    override fun getAccumulatedSpend(walletId: WalletId, currency: CurrencyCode, date: LocalDate): Money {
        val sql = """
            SELECT accumulated_spend_minor FROM account.spend_accumulators 
            WHERE wallet_id = :walletId AND currency = :currency AND period_date = :periodDate
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("walletId", walletId.value)
            .addValue("currency", currency.value)
            .addValue("periodDate", Date.valueOf(date))

        val spendMinor = jdbcTemplate.query(sql, params) { rs, _ -> rs.getLong("accumulated_spend_minor") }.firstOrNull() ?: 0L
        return Money.ofMinor(spendMinor, currency)
    }

    override fun addSpend(walletId: WalletId, amount: Money, date: LocalDate): Money {
        val sql = """
            INSERT INTO account.spend_accumulators (
                id, wallet_id, currency, period_date, accumulated_spend_minor, created_at, updated_at
            ) VALUES (
                gen_random_uuid(), :walletId, :currency, :periodDate, :amountMinor, NOW(), NOW()
            )
            ON CONFLICT (wallet_id, currency, period_date) DO UPDATE SET
                accumulated_spend_minor = account.spend_accumulators.accumulated_spend_minor + EXCLUDED.accumulated_spend_minor,
                updated_at = NOW()
            RETURNING accumulated_spend_minor
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("walletId", walletId.value)
            .addValue("currency", amount.currency.value)
            .addValue("periodDate", Date.valueOf(date))
            .addValue("amountMinor", amount.amountMinorUnits)

        val totalSpend = jdbcTemplate.queryForObject(sql, params, Long::class.java) ?: amount.amountMinorUnits
        return Money.ofMinor(totalSpend, amount.currency)
    }
}
