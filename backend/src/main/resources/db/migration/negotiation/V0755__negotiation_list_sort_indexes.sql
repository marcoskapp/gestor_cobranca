-- =============================================================================
-- Ordenação das listas de propostas e acordos sobre a assessoria inteira (GET /api/v1/negotiation/proposals e
-- /agreements, decisão 954). Os índices existentes começam por credor, devedor, caso ou autor. Medido em 105 mil
-- propostas e 120 mil acordos de uma assessoria (docs/tecnico/desenho/ux.md §16.1): seq scan + top-N heapsort
-- (17–23 ms, ~11,6 mil buffers) → 0,1–0,2 ms com coluna + id. O valor total não muda depois de criado.
-- =============================================================================
create index ix_proposal_tenant_created on negotiation.proposal (tenant_id, created_at, id);
create index ix_proposal_tenant_amount on negotiation.proposal (tenant_id, total_amount, id);
create index ix_agreement_tenant_formalized on negotiation.agreement (tenant_id, formalized_at, id);
create index ix_agreement_tenant_amount on negotiation.agreement (tenant_id, total_amount, id);
