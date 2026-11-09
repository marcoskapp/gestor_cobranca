-- =============================================================================
-- Ordenação da lista de casos sobre a assessoria inteira (GET /api/v1/collection/cases, decisão 954).
-- Medido com EXPLAIN (ANALYZE, BUFFERS) em 300 mil casos de uma assessoria (docs/tecnico/desenho/ux.md §16.1):
-- sem índice, cada página fazia seq scan + top-N heapsort (38–72 ms, ~11,9 mil buffers); com coluna + id, a
-- página lê ~25–35 buffers (0,1–0,2 ms). A direção decrescente usa o índice de trás para a frente, com
-- Incremental Sort no desempate.
-- =============================================================================
create index ix_case_tenant_opened on collection.collection_case (tenant_id, opened_at, id);
create index ix_case_tenant_balance on collection.collection_case (tenant_id, open_balance, id);
create index ix_case_tenant_oldest_due on collection.collection_case (tenant_id, oldest_due_date, id);
create index ix_case_tenant_next_action on collection.collection_case (tenant_id, next_action_at, id);
