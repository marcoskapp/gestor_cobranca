-- =============================================================================
-- Concede as permissões das garantias e da prescrição (COLLATERAL_MANAGE,
-- PRESCRIPTION_MANAGE) aos perfis padrão ADMIN e GESTOR das assessorias existentes.
-- Novas assessorias recebem-nas por DefaultRoles no provisionamento. A migration fica
-- na faixa do debt por introduzir permissões do módulo; altera apenas dados.
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
        join (values ('ADMIN', 'COLLATERAL_MANAGE'), ('ADMIN', 'PRESCRIPTION_MANAGE'),
                     ('GESTOR', 'COLLATERAL_MANAGE'), ('GESTOR', 'PRESCRIPTION_MANAGE')) as p(code, permission)
            on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
