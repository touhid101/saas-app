-- V1: Baseline schema — run once to establish Flyway history
-- Assumes Pharma_Database.sql DDL is already applied to the Oracle schema.
-- This baseline records the pre-existing schema state.
-- Actual table creation is handled by db/reconciled-schema.sql (run separately by DBA).
SELECT 'Flyway baseline established' FROM DUAL;
