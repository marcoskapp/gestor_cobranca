-- =============================================================================
-- Telephony: permissões novas do módulo (TELEPHONY_SUPERVISE e
-- CALL_RECORDING_LISTEN) e TELEPHONY_MANAGE para o gestor, nos perfis padrão das
-- assessorias existentes, e as tabulações usadas pelo registro automático de
-- chamadas no caso (collection, origem TELEPHONY). Novas assessorias recebem as
-- permissões por DefaultRoles e as tabulações pelo catálogo padrão
-- (DefaultTabulations, consumo de TenantProvisioned), com o mesmo conteúdo.
-- NO_ANSWER, BUSY e VOICEMAIL já fazem parte do catálogo (V0601). Nenhuma é
-- contato efetivo: o operador tabula o contato com a pessoa certa.
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
        join (values ('ADMIN', 'TELEPHONY_MANAGE'), ('ADMIN', 'TELEPHONY_SUPERVISE'), ('ADMIN', 'CALL_RECORDING_LISTEN'),
                     ('GESTOR', 'TELEPHONY_USE'), ('GESTOR', 'TELEPHONY_MANAGE'), ('GESTOR', 'TELEPHONY_SUPERVISE'),
                     ('GESTOR', 'CALL_RECORDING_LISTEN'),
                     ('SUPERVISOR', 'TELEPHONY_USE'), ('SUPERVISOR', 'TELEPHONY_SUPERVISE'),
                     ('SUPERVISOR', 'CALL_RECORDING_LISTEN')) as p(code, permission) on p.code = r.code
        where r.system_role
        on conflict do nothing;

        insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                                           effective_contact, promise, callback, requires_note, display_order, active)
        select gen_random_uuid(), t.id, 0, now(), now(), d.code, d.name, false, false, false, false, d.display_order, true
        from (values ('CALL_ANSWERED', 'Chamada atendida (sem tabulação do operador)', 150),
                     ('CALL_ABANDONED', 'Chamada abandonada pelo discador', 160),
                     ('CALL_FAILED', 'Chamada não completada', 170))
                 as d(code, name, display_order)
        on conflict (tenant_id, code) do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
