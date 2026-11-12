-- =============================================================================
-- Critérios de segmento por contato novo e por tipo de pessoa (docs/tecnico/desenho/campanhas.md §2):
--  * new_contact_until: fim da novidade dos contatos do devedor recebidos na carga
--    (contatos-recebidos.md §4), gravado ao consumir DebtorContactNoveltyChanged do debt para
--    todos os casos do devedor; na inclusão do caso na projeção, o valor vem do caso do collection.
--    new_contact_changed_at guarda o instante do evento aplicado: entrega fora de ordem é descartada.
--  * debtor_person_type: tipo de pessoa do devedor (cobranca-b2b.md §9), lido do caso do collection.
-- A reconciliação diária (AudienceRebuildJob) preenche o tipo de pessoa dos casos que já estão na
-- projeção; nenhum contato é novo antes desta versão (sem backfill da novidade).
-- =============================================================================
alter table campaign.audience_case
    add column new_contact_until      timestamptz,
    add column new_contact_changed_at timestamptz,
    add column debtor_person_type     varchar(20),
    add constraint ck_audience_debtor_person_type check (debtor_person_type in ('INDIVIDUAL', 'COMPANY'));

create index ix_audience_new_contact on campaign.audience_case (tenant_id, creditor_id, new_contact_until)
    where new_contact_until is not null;
