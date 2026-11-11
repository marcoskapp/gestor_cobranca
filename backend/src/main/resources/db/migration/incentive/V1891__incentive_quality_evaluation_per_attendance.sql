-- =============================================================================
-- incentive: monitoria por atendimento.
--  * Novo tipo de referência INTERACTION: a avaliação feita no histórico do caso aponta para a interação avaliada
--    (antes apontava para o caso, e todas as interações do caso ficavam com a mesma referência).
--  * Um avaliador avalia cada atendimento uma vez: segunda avaliação do mesmo avaliador para a mesma referência é
--    recusada (outro avaliador pode avaliar o mesmo atendimento). Avaliação geral (sem referência) não entra na regra;
--    CASE (legado) é conferido só no serviço.
-- =============================================================================

alter table incentive.quality_evaluation drop constraint ck_quality_evaluation_reference;
alter table incentive.quality_evaluation add constraint ck_quality_evaluation_reference
    check (reference_type in ('CALL', 'CONVERSATION', 'CASE', 'AGREEMENT', 'INTERACTION', 'OTHER'));

-- Avaliações repetidas de antes desta regra não podem ser apagadas (tabela append-only). CASE com id é legado (a
-- avaliação do histórico passou a apontar para a interação) e fica fora do índice; nos demais tipos, uma duplicata
-- existente aborta a migration com mensagem clara (só a contagem), para decisão sobre o dado legado.
-- quality_evaluation tem RLS forçada: a conferência percorre as assessorias (como na V0304, decisão 849).
do $$
declare
    t          record;
    n          bigint;
    duplicated bigint := 0;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        select count(*) into n
          from (select 1
                  from incentive.quality_evaluation
                 where reference_id is not null and reference_type <> 'CASE'
                 group by evaluator_id, reference_type, reference_id
                having count(*) > 1) d;
        duplicated := duplicated + n;
    end loop;
    perform set_config('app.tenant_id', '', true);
    if duplicated > 0 then
        raise exception 'V1891: % atendimento(s) com mais de uma avaliação do mesmo avaliador; decida o tratamento do legado antes de aplicar', duplicated;
    end if;
end
$$;

create unique index ux_quality_evaluation_evaluator_reference
    on incentive.quality_evaluation (tenant_id, evaluator_id, reference_type, reference_id)
    where reference_id is not null and reference_type <> 'CASE';
