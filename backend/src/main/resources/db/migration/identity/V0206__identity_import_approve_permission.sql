-- =============================================================================
-- Identity: concessão da permissão nova IMPORT_APPROVE (aprovação de segunda
-- pessoa da efetivação de importações acima do limite, módulo ingestion —
-- docs/tecnico/desenho/importacao.md §5) aos perfis padrão ADMIN e GESTOR das
-- assessorias existentes. Novas assessorias recebem o mesmo conteúdo por
-- DefaultRoles no provisionamento.
-- Numeração: V0205 fica reservada para a frente do assistant (permissões do
-- assistente), em desenvolvimento em paralelo.
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
        join (values ('ADMIN', 'IMPORT_APPROVE'), ('GESTOR', 'IMPORT_APPROVE')) as p(code, permission)
            on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
