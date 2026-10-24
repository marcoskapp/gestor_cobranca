-- =============================================================================
-- Identity: permissões do módulo assistant (docs/tecnico/desenho/assistente.md
-- §8) nos perfis padrão das assessorias existentes. Novas assessorias recebem-nas
-- por DefaultRoles no provisionamento.
--   ASSISTANT_MANAGE (já existia no catálogo, só no ADMIN) → GESTOR
--   ASSISTANT_REVIEW (nova) → ADMIN, GESTOR, SUPERVISOR
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
        join (values ('ADMIN', 'ASSISTANT_MANAGE'), ('ADMIN', 'ASSISTANT_REVIEW'),
                     ('GESTOR', 'ASSISTANT_MANAGE'), ('GESTOR', 'ASSISTANT_REVIEW'),
                     ('SUPERVISOR', 'ASSISTANT_REVIEW')) as p(code, permission)
            on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
