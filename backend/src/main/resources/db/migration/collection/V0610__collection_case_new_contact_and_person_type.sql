-- =============================================================================
-- Collection — critérios de fila por contato novo e por tipo de pessoa:
--  * contato novo recebido na carga (docs/tecnico/desenho/contatos-recebidos.md §4): fim da
--    novidade dos contatos do devedor no caso, mantido pelo evento DebtorContactNoveltyChanged
--    do debt (só a data, sem o contato), e critério "só devedores com contato novo";
--  * tipo de pessoa do devedor (docs/tecnico/desenho/cobranca-b2b.md §9): preenchido na
--    abertura do caso (DebtorSegmentationQueries) e critério de fila por PF/PJ.
-- Os casos existentes recebem o tipo de pessoa do cadastro do devedor. collection_case tem RLS
-- forçada: o preenchimento percorre as assessorias definindo o tenant da transação, como a
-- aplicação faria (decisão 849).
-- =============================================================================

alter table collection.collection_case
    add column new_contact_until  timestamptz,
    add column debtor_person_type varchar(20),
    add constraint ck_case_debtor_person_type check (debtor_person_type in ('INDIVIDUAL', 'COMPANY'));

alter table collection.queue
    add column new_contact_only boolean not null default false,
    add column person_type      varchar(20),
    add constraint ck_queue_person_type check (person_type in ('INDIVIDUAL', 'COMPANY'));

do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update collection.collection_case c
           set debtor_person_type = d.person_type
          from debt.debtor d
         where d.id = c.debtor_id and c.debtor_person_type is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
