package com.walletledger.ledger.infrastructure.adapter.output.persistence

import com.walletledger.common.domain.AccountId
import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.EntryId
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.PostingId
import com.walletledger.common.domain.TransactionId
import com.walletledger.ledger.application.port.output.LedgerAccountRepositoryPort
import com.walletledger.ledger.application.port.output.LedgerEntryRepositoryPort
import com.walletledger.ledger.application.port.output.PostingRepositoryPort
import com.walletledger.ledger.domain.model.AccountStatus
import com.walletledger.ledger.domain.model.AccountType
import com.walletledger.ledger.domain.model.EntryStatus
import com.walletledger.ledger.domain.model.EntryType
import com.walletledger.ledger.domain.model.LedgerAccount
import com.walletledger.ledger.domain.model.LedgerEntry
import com.walletledger.ledger.domain.model.Posting
import com.walletledger.ledger.domain.model.PostingDirection
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class PostgreSqlLedgerPersistenceAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : LedgerAccountRepositoryPort, LedgerEntryRepositoryPort, PostingRepositoryPort {

    private val accountRowMapper = RowMapper<LedgerAccount> { rs: ResultSet, _ ->
        val currency = CurrencyCode.unsafe(rs.getString("currency"))
        LedgerAccount(
            id = AccountId(rs.getObject("id", UUID::class.java)),
            accountNumber = rs.getString("account_number"),
            accountType = AccountType.valueOf(rs.getString("account_type")),
            currency = currency,
            balance = Money.ofMinor(rs.getLong("balance_minor_units"), currency),
            status = AccountStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private val entryRowMapper = RowMapper<LedgerEntryRow> { rs: ResultSet, _ ->
        LedgerEntryRow(
            id = EntryId(rs.getObject("id", UUID::class.java)),
            transactionId = TransactionId(rs.getObject("transaction_id", UUID::class.java)),
            description = rs.getString("description"),
            entryType = EntryType.valueOf(rs.getString("entry_type")),
            status = EntryStatus.valueOf(rs.getString("status")),
            postedAt = rs.getTimestamp("posted_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    private val postingRowMapper = RowMapper<Posting> { rs: ResultSet, _ ->
        val currency = CurrencyCode.unsafe(rs.getString("currency"))
        Posting(
            id = PostingId(rs.getObject("id", UUID::class.java)),
            entryId = EntryId(rs.getObject("entry_id", UUID::class.java)),
            accountId = AccountId(rs.getObject("account_id", UUID::class.java)),
            direction = PostingDirection.valueOf(rs.getString("direction")),
            amount = Money.ofMinor(rs.getLong("amount_minor_units"), currency),
            sequenceNum = rs.getInt("sequence_num"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    // ========================================================================
    // LedgerAccountRepositoryPort
    // ========================================================================

    override fun findById(id: AccountId): LedgerAccount? {
        val sql = "SELECT * FROM ledger.ledger_accounts WHERE id = :id"
        val params = MapSqlParameterSource("id", id.value)
        return jdbcTemplate.query(sql, params, accountRowMapper).firstOrNull()
    }

    override fun findByAccountNumber(accountNumber: String): LedgerAccount? {
        val sql = "SELECT * FROM ledger.ledger_accounts WHERE account_number = :accountNumber"
        val params = MapSqlParameterSource("accountNumber", accountNumber)
        return jdbcTemplate.query(sql, params, accountRowMapper).firstOrNull()
    }

    override fun findAllByIdInForUpdate(ids: List<AccountId>): List<LedgerAccount> {
        if (ids.isEmpty()) return emptyList()
        // Note: ORDER BY id ASC is strictly enforced to guarantee zero deadlock conditions
        val sql = """
            SELECT * FROM ledger.ledger_accounts 
            WHERE id IN (:ids) 
            ORDER BY id ASC 
            FOR UPDATE
        """.trimIndent()
        val params = MapSqlParameterSource("ids", ids.map { it.value })
        return jdbcTemplate.query(sql, params, accountRowMapper)
    }

    override fun save(account: LedgerAccount): LedgerAccount {
        saveAccounts(listOf(account))
        return account
    }

    override fun saveAccounts(accounts: List<LedgerAccount>): List<LedgerAccount> {
        if (accounts.isEmpty()) return emptyList()
        val sql = """
            INSERT INTO ledger.ledger_accounts (
                id, account_number, account_type, currency, balance_minor_units, status, version, created_at, updated_at
            ) VALUES (
                :id, :accountNumber, :accountType, :currency, :balanceMinorUnits, :status, :version, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                balance_minor_units = EXCLUDED.balance_minor_units,
                status = EXCLUDED.status,
                version = EXCLUDED.version,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val batchParams = accounts.map { account ->
            MapSqlParameterSource()
                .addValue("id", account.id.value)
                .addValue("accountNumber", account.accountNumber)
                .addValue("accountType", account.accountType.name)
                .addValue("currency", account.currency.value)
                .addValue("balanceMinorUnits", account.balance.amountMinorUnits)
                .addValue("status", account.status.name)
                .addValue("version", account.version)
                .addValue("createdAt", Timestamp.from(account.createdAt))
                .addValue("updatedAt", Timestamp.from(account.updatedAt))
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batchParams)
        return accounts
    }

    // ========================================================================
    // LedgerEntryRepositoryPort
    // ========================================================================

    override fun save(entry: LedgerEntry): LedgerEntry {
        val sql = """
            INSERT INTO ledger.ledger_entries (
                id, transaction_id, description, entry_type, status, posted_at, created_at
            ) VALUES (
                :id, :transactionId, :description, :entryType, :status, :postedAt, :createdAt
            )
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", entry.id.value)
            .addValue("transactionId", entry.transactionId.value)
            .addValue("description", entry.description)
            .addValue("entryType", entry.entryType.name)
            .addValue("status", entry.status.name)
            .addValue("postedAt", Timestamp.from(entry.postedAt))
            .addValue("createdAt", Timestamp.from(entry.createdAt))

        jdbcTemplate.update(sql, params)
        return entry
    }

    override fun findById(id: EntryId): LedgerEntry? {
        val entrySql = "SELECT * FROM ledger.ledger_entries WHERE id = :id"
        val entryRow = jdbcTemplate.query(entrySql, MapSqlParameterSource("id", id.value), entryRowMapper).firstOrNull()
            ?: return null

        val postings = findAllByEntryId(id)
        return LedgerEntry(
            id = entryRow.id,
            transactionId = entryRow.transactionId,
            description = entryRow.description,
            entryType = entryRow.entryType,
            status = entryRow.status,
            postings = postings,
            postedAt = entryRow.postedAt,
            createdAt = entryRow.createdAt
        )
    }

    override fun findByTransactionId(transactionId: TransactionId): LedgerEntry? {
        val entrySql = "SELECT * FROM ledger.ledger_entries WHERE transaction_id = :transactionId"
        val entryRow = jdbcTemplate.query(entrySql, MapSqlParameterSource("transactionId", transactionId.value), entryRowMapper).firstOrNull()
            ?: return null

        val postings = findAllByEntryId(entryRow.id)
        return LedgerEntry(
            id = entryRow.id,
            transactionId = entryRow.transactionId,
            description = entryRow.description,
            entryType = entryRow.entryType,
            status = entryRow.status,
            postings = postings,
            postedAt = entryRow.postedAt,
            createdAt = entryRow.createdAt
        )
    }

    // ========================================================================
    // PostingRepositoryPort
    // ========================================================================

    override fun savePostings(postings: List<Posting>): List<Posting> {
        if (postings.isEmpty()) return emptyList()
        val sql = """
            INSERT INTO ledger.postings (
                id, entry_id, account_id, direction, amount_minor_units, currency, sequence_num, created_at
            ) VALUES (
                :id, :entryId, :accountId, :direction, :amountMinorUnits, :currency, :sequenceNum, :createdAt
            )
        """.trimIndent()

        val batchParams = postings.map { posting ->
            MapSqlParameterSource()
                .addValue("id", posting.id.value)
                .addValue("entryId", posting.entryId.value)
                .addValue("accountId", posting.accountId.value)
                .addValue("direction", posting.direction.name)
                .addValue("amountMinorUnits", posting.amount.amountMinorUnits)
                .addValue("currency", posting.amount.currency.value)
                .addValue("sequenceNum", posting.sequenceNum)
                .addValue("createdAt", Timestamp.from(posting.createdAt))
        }.toTypedArray()

        jdbcTemplate.batchUpdate(sql, batchParams)
        return postings
    }

    override fun findAllByEntryId(entryId: EntryId): List<Posting> {
        val sql = "SELECT * FROM ledger.postings WHERE entry_id = :entryId ORDER BY sequence_num ASC"
        return jdbcTemplate.query(sql, MapSqlParameterSource("entryId", entryId.value), postingRowMapper)
    }

    override fun findAllByAccountId(accountId: AccountId, limit: Int): List<Posting> {
        val sql = "SELECT * FROM ledger.postings WHERE account_id = :accountId ORDER BY created_at DESC LIMIT :limit"
        val params = MapSqlParameterSource()
            .addValue("accountId", accountId.value)
            .addValue("limit", limit)
        return jdbcTemplate.query(sql, params, postingRowMapper)
    }

    private data class LedgerEntryRow(
        val id: EntryId,
        val transactionId: TransactionId,
        val description: String,
        val entryType: EntryType,
        val status: EntryStatus,
        val postedAt: java.time.Instant,
        val createdAt: java.time.Instant
    )
}
