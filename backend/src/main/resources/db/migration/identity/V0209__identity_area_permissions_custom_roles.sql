-- =============================================================================
-- Identity: permissões de área do menu por tarefa (decisão D6 da revisão de produto de
-- 11/2026 — docs/negocio/perfis-de-acesso.md, "Áreas de consulta"):
--   SCORING_AREA_VIEW       abrir a área do score (antes bastava SCORING_VIEW)
--   POLICY_AREA_VIEW        abrir políticas de cobrança e índices de correção (antes POLICY_VIEW)
--   ORGANIZATION_AREA_VIEW  abrir credores e carteiras (antes CREDITOR_VIEW / PORTFOLIO_VIEW)
--
-- Perfis de sistema recebem as permissões pela sincronização de perfis padrão (DefaultRoles +
-- role_default_baseline), que respeita o que o administrador retirou. Esta migration cuida só
-- dos perfis criados pela assessoria, que a sincronização nunca altera: quem já abria a área
-- pela permissão de consulta continua abrindo (nenhum perfil perde acesso); o administrador
-- retira a permissão nova dos perfis em que a área não é tarefa.
-- =============================================================================

-- role_permission é protegida por RLS (via perfil): percorre as assessorias definindo o tenant
-- da transação, como a aplicação faria.
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into identity.role_permission (role_id, permission)
        select distinct rp.role_id, m.area
          from identity.role_permission rp
          join identity.role r on r.id = rp.role_id
          join (values ('SCORING_VIEW', 'SCORING_AREA_VIEW'),
                       ('POLICY_VIEW', 'POLICY_AREA_VIEW'),
                       ('CREDITOR_VIEW', 'ORGANIZATION_AREA_VIEW'),
                       ('PORTFOLIO_VIEW', 'ORGANIZATION_AREA_VIEW')) as m(source, area)
            on m.source = rp.permission
         where not r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
