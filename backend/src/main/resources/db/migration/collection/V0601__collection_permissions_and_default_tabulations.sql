-- =============================================================================
-- Collection: concessão das permissões novas do módulo (CASE_MANAGE e
-- TABULATION_MANAGE) aos perfis padrão das assessorias existentes e catálogo
-- padrão de tabulações. Novas assessorias recebem as permissões por DefaultRoles
-- e as tabulações pelo consumo de TenantProvisioned (DefaultTabulations), com o
-- mesmo conteúdo deste arquivo.
-- =============================================================================

do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);

        insert into identity.role_permission (role_id, permission)
        select r.id, p.permission
        from identity.role r
        join (values ('ADMIN', 'CASE_MANAGE'), ('ADMIN', 'TABULATION_MANAGE'),
                     ('GESTOR', 'CASE_MANAGE'), ('GESTOR', 'TABULATION_MANAGE'),
                     ('SUPERVISOR', 'CASE_MANAGE')) as p(code, permission) on p.code = r.code
        where r.system_role
        on conflict do nothing;

        insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                                           effective_contact, promise, callback, requires_note, display_order, active)
        select gen_random_uuid(), t.id, 0, now(), now(), d.code, d.name, d.effective_contact, d.promise, d.callback,
               d.requires_note, d.display_order, true
        from (values ('CPC_NEGOTIATING', 'Contato efetivo - em negociação', true, false, false, false, 10),
                     ('PROMISE', 'Promessa de pagamento', true, true, false, false, 20),
                     ('CALLBACK', 'Retorno agendado', true, false, true, false, 30),
                     ('REFUSED', 'Recusa pagar', true, false, false, true, 40),
                     ('ALREADY_PAID', 'Alega pagamento', true, false, false, true, 50),
                     ('DISPUTE', 'Contesta a dívida', true, false, false, true, 60),
                     ('THIRD_PARTY', 'Recado com terceiro', false, false, false, false, 70),
                     ('NO_ANSWER', 'Não atende', false, false, false, false, 80),
                     ('BUSY', 'Ocupado', false, false, false, false, 90),
                     ('VOICEMAIL', 'Caixa postal', false, false, false, false, 100),
                     ('WRONG_CONTACT', 'Contato errado / não conhece', false, false, false, true, 110),
                     ('DECEASED', 'Informado falecimento', false, false, false, true, 120))
                 as d(code, name, effective_contact, promise, callback, requires_note, display_order)
        on conflict (tenant_id, code) do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
