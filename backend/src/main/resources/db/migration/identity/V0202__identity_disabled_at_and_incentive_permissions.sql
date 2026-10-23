-- =============================================================================
-- Identity: instante de desativação do usuário (política de desligamento da
-- remuneração variável, docs/design/remuneracao-variavel.md §3.3) e concessão das
-- permissões do módulo incentive aos perfis padrão das assessorias existentes
-- (novas assessorias recebem-nas por DefaultRoles no provisionamento).
-- =============================================================================

alter table identity.app_user add column disabled_at timestamptz;
update identity.app_user set disabled_at = updated_at where status = 'DISABLED' and disabled_at is null;
alter table identity.app_user add constraint ck_app_user_disabled_at
    check ((status = 'DISABLED') = (disabled_at is not null));

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
        join (values
                  ('ADMIN', 'PERFORMANCE_VIEW_OWN'), ('ADMIN', 'PERFORMANCE_VIEW_TEAM'), ('ADMIN', 'PERFORMANCE_VIEW_ALL'),
                  ('ADMIN', 'COMMISSION_PLAN_MANAGE'), ('ADMIN', 'COMMISSION_STATEMENT_MANAGE'),
                  ('ADMIN', 'COMMISSION_STATEMENT_APPROVE'), ('ADMIN', 'QUALITY_EVALUATE'),
                  ('GESTOR', 'PERFORMANCE_VIEW_OWN'), ('GESTOR', 'PERFORMANCE_VIEW_TEAM'),
                  ('GESTOR', 'PERFORMANCE_VIEW_ALL'), ('GESTOR', 'QUALITY_EVALUATE'),
                  ('GESTOR', 'COMMISSION_PLAN_MANAGE'), ('GESTOR', 'COMMISSION_STATEMENT_MANAGE'),
                  ('SUPERVISOR', 'PERFORMANCE_VIEW_OWN'), ('SUPERVISOR', 'PERFORMANCE_VIEW_TEAM'),
                  ('SUPERVISOR', 'QUALITY_EVALUATE'),
                  ('OPERADOR', 'PERFORMANCE_VIEW_OWN'),
                  ('FINANCEIRO', 'PERFORMANCE_VIEW_ALL'), ('FINANCEIRO', 'COMMISSION_STATEMENT_APPROVE'),
                  ('AUDITOR', 'PERFORMANCE_VIEW_ALL')) as p(code, permission) on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
