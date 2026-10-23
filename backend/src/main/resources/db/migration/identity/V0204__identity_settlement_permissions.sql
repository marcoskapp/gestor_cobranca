-- =============================================================================
-- Identity: concessão das permissões novas da prestação de contas (módulo
-- settlement, docs/tecnico/desenho/prestacao-de-contas.md §8) aos perfis padrão das
-- assessorias existentes: SETTLEMENT_APPROVE (aprovação segregada do demonstrativo
-- e dos ajustes acima do limite) ao ADMIN e ao GESTOR; SETTLEMENT_TRANSFER
-- (registro de repasses) ao ADMIN e ao FINANCEIRO. COMMISSION_VIEW,
-- COMMISSION_MANAGE, SETTLEMENT_VIEW e SETTLEMENT_CLOSE já existiam desde V0200.
-- Novas assessorias recebem o mesmo conteúdo por DefaultRoles no provisionamento.
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
        join (values ('ADMIN', 'SETTLEMENT_APPROVE'), ('GESTOR', 'SETTLEMENT_APPROVE'),
                     ('ADMIN', 'SETTLEMENT_TRANSFER'), ('FINANCEIRO', 'SETTLEMENT_TRANSFER')) as p(code, permission)
            on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
