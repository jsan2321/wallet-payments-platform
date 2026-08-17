-- ============================================================================
-- V1__init_logical_schemas.sql
-- Production Wallet & Financial Ledger Platform: Schema Initialization
-- ============================================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Logical Schemas for Bounded Contexts
CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS transaction;
CREATE SCHEMA IF NOT EXISTS settlement;
CREATE SCHEMA IF NOT EXISTS reconciliation;
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS outbox;

-- Comment on schemas for auditability
COMMENT ON SCHEMA account IS 'Wallet accounts, balances, status, and spend limits';
COMMENT ON SCHEMA ledger IS 'Authoritative immutable double-entry journal entries and postings';
COMMENT ON SCHEMA transaction IS 'Inbound command idempotency, transaction orchestration, and audit metadata';
COMMENT ON SCHEMA settlement IS 'Multi-phase settlement batches, holds, releases, and partner clearing';
COMMENT ON SCHEMA reconciliation IS 'External gateway/bank statement ingestion, matching runs, and exception cases';
COMMENT ON SCHEMA integration IS 'Anti-Corruption Layer records, idempotency keys, and gateway webhook logs';
COMMENT ON SCHEMA audit IS 'Immutable append-only audit trail for financial events and administrative actions';
COMMENT ON SCHEMA outbox IS 'Transactional outbox table for Debezium CDC and asynchronous Kafka event streaming';
