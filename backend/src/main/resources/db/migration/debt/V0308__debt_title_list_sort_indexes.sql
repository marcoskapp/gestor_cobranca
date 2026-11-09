-- =============================================================================
-- Ordenação da lista de títulos sobre a assessoria inteira (GET /api/v1/titles, decisão 954). Os índices
-- existentes começam pelo credor ou pela carteira e não servem a quem vê todos os credores. Medido em 500 mil
-- títulos de uma assessoria (docs/tecnico/desenho/ux.md §16.1): seq scan + top-N heapsort (40–80 ms, ~17 mil
-- buffers) → 0,1–0,2 ms com coluna + id. O atraso ordena pelo vencimento (campo invertido).
-- =============================================================================
create index ix_debt_title_tenant_due on debt.debt_title (tenant_id, due_date, id);
create index ix_debt_title_tenant_balance on debt.debt_title (tenant_id, open_balance, id);
