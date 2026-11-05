-- =============================================================================
-- Busca de acordos (inclusive encerrados) por título: proposta sobre títulos de acordo quebrado
-- ainda não renegociado precisa renegociar esse acordo (violação proposal.renegotiation_required).
-- O índice único parcial uk_agreement_title_active só cobre os vínculos em vigor; a quebra libera
-- os vínculos (active = false), que esta consulta também precisa achar.
-- =============================================================================

create index ix_agreement_title_title on negotiation.agreement_title (tenant_id, title_id);
