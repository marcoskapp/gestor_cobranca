-- =============================================================================
-- "Minhas pendências" (docs/tecnico/desenho/pendencias.md): índices parciais para
-- as contagens de extratos a aprovar, contestações abertas e ajustes aguardando
-- segunda aprovação, sem varrer o histórico de períodos.
-- =============================================================================
create index ix_commission_statement_pending on incentive.commission_statement (tenant_id, status, calculated_at)
    where status in ('CALCULATED', 'DISPUTED', 'ADJUSTED');
create index ix_statement_dispute_open on incentive.statement_dispute (tenant_id, opened_at) where status = 'OPEN';
create index ix_statement_adjustment_pending on incentive.statement_adjustment (tenant_id, requested_at)
    where status = 'PENDING_SECOND_APPROVAL';
