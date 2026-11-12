-- =============================================================================
-- Leitura por período dos acordos quitados: atividade diária da apuração dos custos (custo por acordo cumprido —
-- costing.src_agreement, docs/tecnico/desenho/margem-por-carteira.md §4). Parcial: só acordos quitados.
-- =============================================================================
create index ix_agreement_settled on negotiation.agreement (tenant_id, settled_at)
    where status = 'SETTLED';
