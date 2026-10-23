-- =============================================================================
-- Audiências de todos os processos de um caso jurídico (ficha do caso,
-- GET /api/v1/legal/matters/{id}/hearings).
-- =============================================================================
create index ix_hearing_matter on legal.hearing (tenant_id, matter_id, scheduled_at);
