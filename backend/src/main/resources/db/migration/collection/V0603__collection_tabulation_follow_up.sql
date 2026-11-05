-- =============================================================================
-- Ação automática da tabulação (docs/tecnico/desenho/collection.md §4.1, decisões
-- 776–780): o fato relatado no atendimento produz o efeito devido na mesma
-- transação do registro da interação, sem depender de um segundo passo manual.
--   OPEN_DISPUTE        abre contestação (origem OPERATOR) com os títulos em cobrança
--   REPORT_DECEASED     restrição de contato DECEASED do devedor (compliance)
--   INVALIDATE_CONTACT  marca como inválido o contato usado na interação (debt)
-- A interação guarda a ação vigente no registro (como o código e as flags) e a
-- contestação aberta guarda a interação de origem (rastreio nos dois sentidos).
-- O catálogo padrão das assessorias existentes recebe as ações (o mesmo conteúdo
-- de DefaultTabulations), só onde a tabulação ainda atende às regras da ação.
-- =============================================================================

alter table collection.tabulation
    add column follow_up    varchar(30) not null default 'NONE',
    add column dispute_type varchar(30),
    add constraint ck_tabulation_follow_up
        check (follow_up in ('NONE', 'OPEN_DISPUTE', 'REPORT_DECEASED', 'INVALIDATE_CONTACT')),
    add constraint ck_tabulation_follow_up_rules
        check (follow_up = 'NONE' or (requires_note and not promise and not callback)),
    add constraint ck_tabulation_dispute_contact
        check (follow_up <> 'OPEN_DISPUTE' or effective_contact),
    add constraint ck_tabulation_dispute_type
        check (dispute_type is null
               or (follow_up = 'OPEN_DISPUTE'
                   and dispute_type in ('NOT_RECOGNIZED', 'ALREADY_PAID', 'WRONG_AMOUNT', 'FRAUD', 'PRESCRIBED',
                                        'PRODUCT_OR_SERVICE', 'OTHER')));
alter table collection.tabulation alter column follow_up drop default;

alter table collection.interaction
    add column follow_up varchar(30) not null default 'NONE',
    add constraint ck_interaction_follow_up
        check (follow_up in ('NONE', 'OPEN_DISPUTE', 'REPORT_DECEASED', 'INVALIDATE_CONTACT'));
alter table collection.interaction alter column follow_up drop default;

alter table collection.dispute
    add column origin_interaction_id uuid references collection.interaction (id);
create index ix_dispute_origin_interaction on collection.dispute (tenant_id, origin_interaction_id)
    where origin_interaction_id is not null;

do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);

        update collection.tabulation tab
        set follow_up = d.follow_up, dispute_type = d.dispute_type, version = tab.version + 1, updated_at = now()
        from (values ('ALREADY_PAID', 'OPEN_DISPUTE', 'ALREADY_PAID'),
                     ('DISPUTE', 'OPEN_DISPUTE', null),
                     ('WRONG_CONTACT', 'INVALIDATE_CONTACT', null),
                     ('DECEASED', 'REPORT_DECEASED', null))
                 as d(code, follow_up, dispute_type)
        where tab.code = d.code
          and tab.follow_up = 'NONE'
          and tab.requires_note and not tab.promise and not tab.callback
          and (d.follow_up <> 'OPEN_DISPUTE' or tab.effective_contact);
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
