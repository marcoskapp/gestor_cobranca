-- Vínculos da ficha do devedor (revisão de produto 11/2026, item 1.4): histórico de interações do devedor em todos
-- os casos (GET /collection/debtors/{id}/interactions) e contestações do devedor (GET /collection/disputes?debtorId=),
-- em vez de uma consulta por caso.
create index ix_interaction_debtor on collection.interaction (tenant_id, debtor_id, started_at desc);
create index ix_dispute_debtor on collection.dispute (tenant_id, debtor_id, opened_at desc);
