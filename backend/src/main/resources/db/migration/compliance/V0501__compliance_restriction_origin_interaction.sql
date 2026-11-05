-- =============================================================================
-- Restrição de contato originada num atendimento de cobrança: falecimento do
-- devedor informado com a tabulação de ação automática REPORT_DECEASED
-- (docs/tecnico/desenho/collection.md §4.1, decisão 777). Guarda a interação de
-- origem (collection.interaction) para o rastreio nos dois sentidos. Sem chave
-- estrangeira: numa base nova esta migration roda antes da V0600, que cria a
-- tabela de interações.
-- =============================================================================
alter table compliance.contact_restriction
    add column origin_interaction_id uuid;
create index ix_contact_restriction_origin_interaction on compliance.contact_restriction (tenant_id, origin_interaction_id)
    where origin_interaction_id is not null;
