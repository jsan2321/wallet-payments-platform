package com.walletledger.transaction.infrastructure.adapter.output.persistence

import com.walletledger.common.domain.CurrencyCode
import com.walletledger.common.domain.IdempotencyKey
import com.walletledger.common.domain.Money
import com.walletledger.common.domain.TransactionId
import com.walletledger.common.domain.WalletId
import com.walletledger.transaction.application.port.output.HoldRepositoryPort
import com.walletledger.transaction.application.port.output.IdempotencyRepositoryPort
import com.walletledger.transaction.application.port.output.TransactionRepositoryPort
import com.walletledger.transaction.domain.model.HoldRecord
import com.walletledger.transaction.domain.model.HoldStatus
import com.walletledger.transaction.domain.model.IdempotencyRecord
import com.walletledger.transaction.domain.model.IdempotencyStatus
import com.walletledger.transaction.domain.model.Transaction
import com.walletledger.transaction.domain.model.TransactionStatus
import com.walletledger.transaction.domain.model.TransactionType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class PostgreSqlTransactionPersistenceAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) : TransactionRepositoryPort, IdempotencyRepositoryPort, HoldRepositoryPort {

    private val transactionRowMapper = RowMapper<Transaction> { rs: ResultSet, _ ->
        val currency = CurrencyCode.unsafe(rs.getString("currency"))
        val sourceWalletUuid = rs.getObject("source_wallet_id", UUID::class.java)
        val destWalletUuid = rs.getObject("destination_wallet_id", UUID::class.java)
        Transaction(
            id = TransactionId(rs.getObject("id", UUID::class.java)),
            idempotencyKey = IdempotencyKey(rs.getString("idempotency_key")),
            transactionType = TransactionType.valueOf(rs.getString("transaction_type")),
            status = TransactionStatus.valueOf(rs.getString("status")),
            sourceWalletId = sourceWalletUuid?.let { WalletId(it) },
            destinationWalletId = destWalletUuid?.let { WalletId(it) },
            amount = Money.ofMinor(rs.getLong("amount_minor_units"), currency),
            fee = Money.ofMinor(rs.getLong("fee_minor_units"), currency),
            description = rs.getString("description"),
            failureReason = rs.getString("failure_reason"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    private val idempotencyRowMapper = RowMapper<IdempotencyRecord> { rs: ResultSet, _ ->
        IdempotencyRecord(
            id = rs.getObject("id", UUID::class.java),
            idempotencyKey = IdempotencyKey(rs.getString("idempotency_key")),
            requestHash = rs.getString("request_hash"),
            status = IdempotencyStatus.valueOf(rs.getString("status")),
            responsePayload = rs.getString("response_payload"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant()
        )
    }

    private val holdRowMapper = RowMapper<HoldRecord> { rs: ResultSet, _ ->
        val currency = CurrencyCode.unsafe(rs.getString("currency"))
        HoldRecord(
            id = rs.getObject("id", UUID::class.java),
            walletId = WalletId(rs.getObject("wallet_id", UUID::class.java)),
            transactionId = TransactionId(rs.getObject("transaction_id", UUID::class.java)),
            amount = Money.ofMinor(rs.getLong("amount_minor_units"), currency),
            status = HoldStatus.valueOf(rs.getString("status")),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    // ========================================================================
    // TransactionRepositoryPort
    // ========================================================================

    override fun findById(id: TransactionId): Transaction? {
        val sql = "SELECT * FROM transaction.transactions WHERE id = :id"
        return jdbcTemplate.query(sql, MapSqlParameterSource("id", id.value), transactionRowMapper).firstOrNull()
    }

    override fun findByIdempotencyKey(key: IdempotencyKey): Transaction? {
        val sql = "SELECT * FROM transaction.transactions WHERE idempotency_key = :key"
        return jdbcTemplate.query(sql, MapSqlParameterSource("key", key.value), transactionRowMapper).firstOrNull()
    }

    override fun save(transaction: Transaction): Transaction {
        val sql = """
            INSERT INTO transaction.transactions (
                id, idempotency_key, transaction_type, status, source_wallet_id, destination_wallet_id,
                amount_minor_units, currency, fee_minor_units, description, failure_reason, created_at, updated_at
            ) VALUES (
                :id, :idempotencyKey, :transactionType, :status, :sourceWalletId, :destinationWalletId,
                :amountMinorUnits, :currency, :feeMinorUnits, :description, :failureReason, :createdAt, :updatedAt
            )
            ON CONFLICT (idempotency_key) DO UPDATE SET
                status = EXCLUDED.status,
                failure_reason = EXCLUDED.failure_reason,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", transaction.id.value)
            .addValue("idempotencyKey", transaction.idempotencyKey.value)
            .addValue("transactionType", transaction.transactionType.name)
            .addValue("status", transaction.status.name)
            .addValue("sourceWalletId", transaction.sourceWalletId?.value)
            .addValue("destinationWalletId", transaction.destinationWalletId?.value)
            .addValue("amountMinorUnits", transaction.amount.amountMinorUnits)
            .addValue("currency", transaction.amount.currency.value)
            .addValue("feeMinorUnits", transaction.fee.amountMinorUnits)
            .addValue("description", transaction.description)
            .addValue("failureReason", transaction.failureReason)
            .addValue("createdAt", Timestamp.from(transaction.createdAt))
            .addValue("updatedAt", Timestamp.from(transaction.updatedAt))

        jdbcTemplate.update(sql, params)
        return transaction
    }

    // ========================================================================
    // IdempotencyRepositoryPort
    // ========================================================================

    override fun tryAcquireLock(key: IdempotencyKey, requestHash: String): Boolean {
        val sql = """
            INSERT INTO transaction.idempotency_records (
                id, idempotency_key, request_hash, status, created_at, expires_at
            ) VALUES (
                gen_random_uuid(), :idempotencyKey, :requestHash, 'IN_FLIGHT', NOW(), NOW() + INTERVAL '24 hours'
            )
            ON CONFLICT (idempotency_key) DO NOTHING
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("idempotencyKey", key.value)
            .addValue("requestHash", requestHash)

        val rowsAffected = jdbcTemplate.update(sql, params)
        return rowsAffected > 0
    }

    override fun findByKey(key: IdempotencyKey): IdempotencyRecord? {
        val sql = "SELECT * FROM transaction.idempotency_records WHERE idempotency_key = :key"
        return jdbcTemplate.query(sql, MapSqlParameterSource("key", key.value), idempotencyRowMapper).firstOrNull()
    }

    override fun save(record: IdempotencyRecord): IdempotencyRecord {
        val sql = """
            INSERT INTO transaction.idempotency_records (
                id, idempotency_key, request_hash, status, response_payload, created_at, expires_at
            ) VALUES (
                :id, :idempotencyKey, :requestHash, :status, :responsePayload, :createdAt, :expiresAt
            )
            ON CONFLICT (idempotency_key) DO UPDATE SET
                status = EXCLUDED.status,
                response_payload = EXCLUDED.response_payload
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", record.id)
            .addValue("idempotencyKey", record.idempotencyKey.value)
            .addValue("requestHash", record.requestHash)
            .addValue("status", record.status.name)
            .addValue("responsePayload", record.responsePayload)
            .addValue("createdAt", Timestamp.from(record.createdAt))
            .addValue("expiresAt", Timestamp.from(record.expiresAt))

        jdbcTemplate.update(sql, params)
        return record
    }

    // ========================================================================
    // HoldRepositoryPort
    // ========================================================================

    override fun findById(id: UUID): HoldRecord? {
        val sql = "SELECT * FROM settlement.holds WHERE id = :id"
        return jdbcTemplate.query(sql, MapSqlParameterSource("id", id), holdRowMapper).firstOrNull()
    }

    override fun save(hold: HoldRecord): HoldRecord {
        val sql = """
            INSERT INTO settlement.holds (
                id, wallet_id, transaction_id, amount_minor_units, currency, status, expires_at, created_at, updated_at
            ) VALUES (
                :id, :walletId, :transactionId, :amountMinorUnits, :currency, :status, :expiresAt, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("id", hold.id)
            .addValue("walletId", hold.walletId.value)
            .addValue("transactionId", hold.transactionId.value)
            .addValue("amountMinorUnits", hold.amount.amountMinorUnits)
            .addValue("currency", hold.amount.currency.value)
            .addValue("status", hold.status.name)
            .addValue("expiresAt", Timestamp.from(hold.expiresAt))
            .addValue("createdAt", Timestamp.from(hold.createdAt))
            .addValue("updatedAt", Timestamp.from(hold.updatedAt))

        jdbcTemplate.update(sql, params)
        return hold
    }
}
