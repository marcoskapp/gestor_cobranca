-- =============================================================================
-- Identity: concessão da permissão AUTHORITY_MANAGE (perfis de alçada do módulo
-- negotiation, docs/design/negociacao-e-alcadas.md §4) aos perfis padrão ADMIN e
-- GESTOR das assessorias existentes. Novas assessorias recebem-na por
-- DefaultRoles no provisionamento. POLICY_VIEW, POLICY_MANAGE e
-- NEGOTIATION_SIMULATE já existiam no catálogo desde V0200.
-- =============================================================================

-- role_permission é protegida por RLS (via perfil): a concessão percorre as
-- assessorias definindo o tenant da transação, como a aplicação faria.
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into identity.role_permission (role_id, permission)
        select r.id, p.permission
        from identity.role r
        join (values ('ADMIN', 'AUTHORITY_MANAGE'), ('GESTOR', 'AUTHORITY_MANAGE')) as p(code, permission)
            on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
