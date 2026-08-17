-- ============================================================================
-- V3__create_account_schema.sql
-- Production Wallet & Financial Ledger Platform: Account & Wallet Schema
-- ============================================================================

-- 1. Wallets Table
CREATE TABLE IF NOT EXISTS account.wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    tier VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    daily_spend_limit_minor BIGINT NOT NULL DEFAULT 1000000,
    single_tx_limit_minor BIGINT NOT NULL DEFAULT 500000,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_wallet_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_wallet_tier CHECK (tier IN ('STANDARD', 'TIER_1_BASIC', 'TIER_2_VERIFIED', 'TIER_3_ENTERPRISE'))
);

CREATE INDEX IF NOT EXISTS idx_wallets_owner_id ON account.wallets(owner_id);
CREATE INDEX IF NOT EXISTS idx_wallets_status ON account.wallets(status);

-- 2. Wallet Multi-Currency Sub-Accounts Table
CREATE TABLE IF NOT EXISTS account.wallet_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id UUID NOT NULL REFERENCES account.wallets(id) ON DELETE RESTRICT,
    ledger_account_id UUID NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL,
    available_balance_minor BIGINT NOT NULL DEFAULT 0,
    held_balance_minor BIGINT NOT NULL DEFAULT 0,
    total_balance_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallet_currency UNIQUE (wallet_id, currency),
    CONSTRAINT chk_wallet_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_balance_invariant CHECK (available_balance_minor + held_balance_minor = total_balance_minor),
    CONSTRAINT chk_available_non_negative CHECK (available_balance_minor >= 0),
    CONSTRAINT chk_held_non_negative CHECK (held_balance_minor >= 0)
);

CREATE INDEX IF NOT EXISTS idx_wallet_accounts_wallet_id ON account.wallet_accounts(wallet_id);
CREATE INDEX IF NOT EXISTS idx_wallet_accounts_ledger_account ON account.wallet_accounts(ledger_account_id);

-- 3. Daily Spend Accumulators Table
CREATE TABLE IF NOT EXISTS account.spend_accumulators (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id UUID NOT NULL REFERENCES account.wallets(id) ON DELETE CASCADE,
    currency VARCHAR(3) NOT NULL,
    period_date DATE NOT NULL,
    accumulated_spend_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_spend_accumulator UNIQUE (wallet_id, currency, period_date),
    CONSTRAINT chk_spend_non_negative CHECK (accumulated_spend_minor >= 0)
);

CREATE INDEX IF NOT EXISTS idx_spend_accumulators_wallet_period ON account.spend_accumulators(wallet_id, period_date);
