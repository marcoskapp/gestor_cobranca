-- =============================================================================
-- Permissões do módulo legal (LEGAL_VIEW, LEGAL_MANAGE, LEGAL_AUTHORIZE,
-- LEGAL_EXPENSE_MANAGE) para os perfis padrão das assessorias existentes e os
-- perfis padrão novos JURIDICO (jurídico interno) e ADVOGADO_PARCEIRO (advogado de
-- escritório parceiro, somente permissões do jurídico). Novas assessorias recebem
-- tudo por DefaultRoles no provisionamento, com o mesmo conteúdo deste arquivo.
-- Fica na faixa do legal por introduzir permissões do módulo; altera apenas dados.
-- =============================================================================

-- role/role_permission são protegidas por RLS: a concessão percorre as assessorias
-- definindo o tenant da transação, como a aplicação faria.
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);

        insert into identity.role (id, tenant_id, version, created_at, updated_at, code, name, description,
                                   system_role, active)
        select gen_random_uuid(), t.id, 0, now(), now(), d.code, d.name, d.description, true, true
        from (values ('JURIDICO', 'Jurídico', 'Condução dos casos jurídicos, autorização do credor e custas'),
                     ('ADVOGADO_PARCEIRO', 'Advogado parceiro',
                      'Advogado de escritório parceiro: somente os casos jurídicos atribuídos ao escritório'))
                 as d(code, name, description)
        where exists (select 1 from identity.role r where r.tenant_id = t.id and r.code = 'ADMIN')
        on conflict (tenant_id, code) do nothing;

        insert into identity.role_permission (role_id, permission)
        select r.id, p.permission
        from identity.role r
        join (values
                  ('ADMIN', 'LEGAL_VIEW'), ('ADMIN', 'LEGAL_MANAGE'), ('ADMIN', 'LEGAL_AUTHORIZE'),
                  ('ADMIN', 'LEGAL_EXPENSE_MANAGE'),
                  ('GESTOR', 'LEGAL_VIEW'), ('GESTOR', 'LEGAL_MANAGE'), ('GESTOR', 'LEGAL_AUTHORIZE'),
                  ('GESTOR', 'LEGAL_EXPENSE_MANAGE'),
                  ('SUPERVISOR', 'LEGAL_VIEW'),
                  ('FINANCEIRO', 'LEGAL_VIEW'), ('FINANCEIRO', 'LEGAL_EXPENSE_MANAGE'),
                  ('AUDITOR', 'LEGAL_VIEW'),
                  ('JURIDICO', 'CREDITOR_VIEW'), ('JURIDICO', 'PORTFOLIO_VIEW'), ('JURIDICO', 'POLICY_VIEW'),
                  ('JURIDICO', 'DEBTOR_VIEW'), ('JURIDICO', 'CASE_VIEW'), ('JURIDICO', 'AGREEMENT_VIEW'),
                  ('JURIDICO', 'COLLATERAL_MANAGE'), ('JURIDICO', 'PRESCRIPTION_MANAGE'), ('JURIDICO', 'DASHBOARD_VIEW'),
                  ('JURIDICO', 'LEGAL_VIEW'), ('JURIDICO', 'LEGAL_MANAGE'), ('JURIDICO', 'LEGAL_AUTHORIZE'),
                  ('JURIDICO', 'LEGAL_EXPENSE_MANAGE'),
                  ('ADVOGADO_PARCEIRO', 'LEGAL_VIEW'), ('ADVOGADO_PARCEIRO', 'LEGAL_MANAGE'),
                  ('ADVOGADO_PARCEIRO', 'LEGAL_EXPENSE_MANAGE')) as p(code, permission) on p.code = r.code
        where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
