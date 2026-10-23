-- =============================================================================
-- Propostas por caso de cobrança (filtro caseId de GET /api/v1/negotiation/proposals,
-- aba Negociação da estação de trabalho).
-- =============================================================================
create index ix_proposal_case on negotiation.proposal (tenant_id, case_id, created_at desc) where case_id is not null;
