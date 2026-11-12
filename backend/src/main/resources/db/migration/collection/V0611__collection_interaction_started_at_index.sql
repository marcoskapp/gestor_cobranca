-- =============================================================================
-- Leitura por período das interações da assessoria inteira: apuração diária dos custos (tempo direto de pessoas e
-- casos trabalhados por dia — costing.src_interaction, docs/tecnico/desenho/margem-por-carteira.md §4). Os índices
-- existentes começam por caso, devedor ou operador; sem este, a apuração varre a tabela inteira da assessoria.
-- =============================================================================
create index ix_interaction_tenant_started on collection.interaction (tenant_id, started_at);
