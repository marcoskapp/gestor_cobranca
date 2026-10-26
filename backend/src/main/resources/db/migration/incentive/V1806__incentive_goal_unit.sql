-- =============================================================================
-- Unidade explícita da meta do período (docs/tecnico/desenho/remuneracao-variavel.md
-- §10.10; decisões 387 e 490–497): CURRENCY (R$, métrica "valor recuperado") ou
-- PERCENT (%, métrica "taxa de recuperação"). O servidor aceita só a unidade da
-- métrica do plano que vai usar a meta; no fechamento, meta em unidade diferente
-- é desconsiderada (vale a meta padrão do plano), nunca reinterpretada.
--
-- Compatibilidade: metas já gravadas recebem a unidade quando todos os planos
-- publicados/aposentados vigentes no período com meta do mesmo escopo (individual
-- para USER, equipe para TEAM) concordam na unidade. As demais ficam com unidade
-- nula e continuam seguindo a métrica do plano, como antes; ao serem alteradas,
-- passam a exigir a unidade.
-- =============================================================================
alter table incentive.goal_target add column unit varchar(10);
alter table incentive.goal_target add constraint ck_goal_target_unit
    check (unit is null or unit in ('CURRENCY', 'PERCENT'));

comment on column incentive.goal_target.unit is
    'CURRENCY (R$) ou PERCENT (%); nula só em meta anterior à V1806 sem unidade dedutível (segue a métrica do plano)';

-- goal_target e commission_plan são protegidas por RLS (forçada): o preenchimento
-- percorre as assessorias definindo o tenant da transação, como a aplicação faria.
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update incentive.goal_target g
        set unit = u.unit
        from (select g2.id,
                     min(case p.rules -> 'goal' ->> 'metric' when 'RECOVERED' then 'CURRENCY' else 'PERCENT' end) as unit
              from incentive.goal_target g2
              join incentive.commission_plan p
                on p.status in ('PUBLISHED', 'RETIRED')
               and p.effective_from is not null
               and p.effective_from <= g2.period_start
               and (p.effective_to is null or p.effective_to >= g2.period_start)
               and jsonb_typeof(p.rules -> 'goal') = 'object'
               and p.rules -> 'goal' ->> 'scope' = case g2.subject_type when 'USER' then 'INDIVIDUAL' else 'TEAM' end
              where g2.unit is null
              group by g2.id
              having count(distinct case p.rules -> 'goal' ->> 'metric'
                                         when 'RECOVERED' then 'CURRENCY' else 'PERCENT' end) = 1) u
        where g.id = u.id
          and (u.unit = 'CURRENCY' or g.target_value <= 100);
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
