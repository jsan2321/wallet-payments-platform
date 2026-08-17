-- ============================================================================
-- V2__create_ledger_schema.sql
-- Production Wallet & Financial Ledger Platform: Double-Entry Ledger Schema
-- ============================================================================

-- 1. Ledger Accounts Table
CREATE TABLE IF NOT EXISTS ledger.ledger_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(64) NOT NULL UNIQUE,
    account_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance_minor_units BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ledger_account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT chk_ledger_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_ledger_accounts_number ON ledger.ledger_accounts(account_number);
CREATE INDEX IF NOT EXISTS idx_ledger_accounts_currency ON ledger.ledger_accounts(currency);

-- 2. Ledger Entries (Journal Transaction Headers) Table
CREATE TABLE IF NOT EXISTS ledger.ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    description TEXT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',
    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'SETTLEMENT', 'FEE', 'ADJUSTMENT', 'REVERSAL')),
    CONSTRAINT chk_ledger_entry_status CHECK (status IN ('COMMITTED', 'REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction_id ON ledger.ledger_entries(transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_posted_at ON ledger.ledger_entries(posted_at DESC);

-- 3. Postings (Atomic Debit / Credit Legs) Table
CREATE TABLE IF NOT EXISTS ledger.postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id UUID NOT NULL REFERENCES ledger.ledger_entries(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES ledger.ledger_accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(6) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    sequence_num INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_posting_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_posting_amount_positive CHECK (amount_minor_units > 0)
);

CREATE INDEX IF NOT EXISTS idx_postings_entry_id ON ledger.postings(entry_id);
CREATE INDEX IF NOT EXISTS idx_postings_account_created ON ledger.postings(account_id, created_at DESC);
