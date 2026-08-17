-- ============================================================================
-- V4__create_transaction_and_settlement_schema.sql
-- Production Wallet & Financial Ledger Platform: Transactions & Settlement Schema
-- ============================================================================

-- 1. Transactions Table
CREATE TABLE IF NOT EXISTS transaction.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(256) NOT NULL UNIQUE,
    transaction_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    source_wallet_id UUID,
    destination_wallet_id UUID,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee_minor_units BIGINT NOT NULL DEFAULT 0,
    description TEXT NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_transaction_type CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'SPLIT_PAYMENT', 'HOLD_AUTHORIZE', 'HOLD_CAPTURE', 'HOLD_VOID')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT chk_amount_positive CHECK (amount_minor_units > 0),
    CONSTRAINT chk_fee_non_negative CHECK (fee_minor_units >= 0)
);

CREATE INDEX IF NOT EXISTS idx_transactions_idempotency_key ON transaction.transactions(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_transactions_source_wallet ON transaction.transactions(source_wallet_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_dest_wallet ON transaction.transactions(destination_wallet_id, created_at DESC);

-- 2. Idempotency Records Table
CREATE TABLE IF NOT EXISTS transaction.idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(256) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_FLIGHT',
    response_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_FLIGHT', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_idempotency_key ON transaction.idempotency_records(idempotency_key);

-- 3. Holds Table (in settlement schema)
CREATE TABLE IF NOT EXISTS settlement.holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id UUID NOT NULL,
    transaction_id UUID NOT NULL REFERENCES transaction.transactions(id),
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'HELD',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_hold_status CHECK (status IN ('HELD', 'CAPTURED', 'VOIDED', 'EXPIRED')),
    CONSTRAINT chk_hold_amount_positive CHECK (amount_minor_units > 0)
);

CREATE INDEX IF NOT EXISTS idx_holds_wallet_id ON settlement.holds(wallet_id, status);
CREATE INDEX IF NOT EXISTS idx_holds_transaction_id ON settlement.holds(transaction_id);
