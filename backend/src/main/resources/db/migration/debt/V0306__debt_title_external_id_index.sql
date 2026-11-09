-- Busca rápida (Ctrl+K) pelo código do título no credor (revisão de produto 11/2026, item 1.3): a consulta é exata
-- pelo código e, para quem tem todos os credores, sem credor. O índice único uk_debt_title_external
-- (tenant_id, creditor_id, external_id) não serve sem o credor (o PostgreSQL 16 não faz skip scan).
create index ix_debt_title_external_id on debt.debt_title (tenant_id, external_id);
