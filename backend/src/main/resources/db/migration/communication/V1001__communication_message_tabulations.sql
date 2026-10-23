-- =============================================================================
-- Communication: tabulações usadas pelo registro automático de interações de
-- mensagens no caso (collection, origem COMMUNICATION) nas assessorias existentes.
-- Novas assessorias recebem as mesmas pelo catálogo padrão (DefaultTabulations,
-- consumo de TenantProvisioned). Não são contato efetivo: o operador tabula o
-- contato efetivo quando identifica o titular.
-- =============================================================================

do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);

        insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                                           effective_contact, promise, callback, requires_note, display_order, active)
        select gen_random_uuid(), t.id, 0, now(), now(), d.code, d.name, false, false, false, false, d.display_order, true
        from (values ('MESSAGE_SENT', 'Mensagem enviada', 130),
                     ('MESSAGE_RECEIVED', 'Mensagem recebida', 140))
                 as d(code, name, display_order)
        on conflict (tenant_id, code) do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
