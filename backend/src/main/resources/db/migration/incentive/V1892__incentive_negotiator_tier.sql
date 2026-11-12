-- =============================================================================
-- incentive: classificação de desempenho do negociador (docs/tecnico/desenho/
-- distribuicao-por-desempenho.md §3 e §11.2, fase 1).
--
--  * tier_settings   parâmetros da assessoria (sem linha = padrões do desenho)
--  * tier_round      rodada apurada (janela até ontem), revista por pessoas e
--                    publicada no prazo ou expirada (vale a publicação anterior)
--  * tier_assignment pessoa × rodada: quartis, base (amostras) e nível proposto e
--                    final (ajuste humano com motivo)
--
-- Versão 1892: segue a última da faixa do módulo (V1891). Uma versão menor que a
-- última aplicada (ex.: V1807) rodaria fora de ordem nos ambientes já migrados.
-- Retenção: a mesma dos fatos de desempenho do incentive (sem expurgo automático).
-- =============================================================================

create table incentive.tier_settings (
    tenant_id             uuid          not null primary key references organization.tenant (id),
    window_days           integer       not null,
    min_evaluations       integer       not null,
    min_cases             integer       not null,
    quality_gate          numeric(4, 2) not null,
    matrix                jsonb         not null,
    default_tier          varchar(1)    not null,
    periodicity           varchar(10)   not null,
    review_business_days  integer       not null,
    updated_at            timestamptz   not null,
    updated_by            uuid          not null,
    constraint ck_tier_settings_window check (window_days between 30 and 365),
    constraint ck_tier_settings_min_evaluations check (min_evaluations between 1 and 100),
    constraint ck_tier_settings_min_cases check (min_cases between 1 and 10000),
    constraint ck_tier_settings_quality_gate check (quality_gate between 0 and 10),
    constraint ck_tier_settings_matrix check (jsonb_typeof(matrix) = 'object'),
    constraint ck_tier_settings_default_tier check (default_tier in ('A', 'B', 'C', 'D')),
    constraint ck_tier_settings_periodicity check (periodicity in ('WEEKLY', 'MONTHLY')),
    constraint ck_tier_settings_review_days check (review_business_days between 1 and 20)
);
select shared.enable_tenant_rls('incentive.tier_settings');

create table incentive.tier_round (
    id               uuid         not null primary key,
    tenant_id        uuid         not null references organization.tenant (id),
    period_from      date         not null,
    period_to        date         not null,
    origin           varchar(10)  not null,
    settings         jsonb        not null,
    review_deadline  date         not null,
    status           varchar(10)  not null,
    created_at       timestamptz  not null,
    created_by       uuid,
    published_at     timestamptz,
    published_by     uuid,
    valid_from       date,
    expired_at       timestamptz,
    constraint ck_tier_round_period check (period_from <= period_to),
    constraint ck_tier_round_origin check (origin in ('SCHEDULED', 'MANUAL')),
    constraint ck_tier_round_status check (status in ('PROPOSED', 'PUBLISHED', 'EXPIRED')),
    constraint ck_tier_round_published check ((status = 'PUBLISHED')
        = (published_at is not null and published_by is not null and valid_from is not null)),
    constraint ck_tier_round_expired check ((status = 'EXPIRED') = (expired_at is not null)),
    constraint ck_tier_round_manual check (origin = 'SCHEDULED' or created_by is not null)
);
-- no máximo uma rodada em revisão por assessoria
create unique index ux_tier_round_proposed on incentive.tier_round (tenant_id) where status = 'PROPOSED';
create index ix_tier_round_created on incentive.tier_round (tenant_id, created_at desc);
create index ix_tier_round_published on incentive.tier_round (tenant_id, published_at desc) where status = 'PUBLISHED';
select shared.enable_tenant_rls('incentive.tier_round');

create table incentive.tier_assignment (
    round_id             uuid           not null references incentive.tier_round (id),
    user_id              uuid           not null,
    tenant_id            uuid           not null references organization.tenant (id),
    quality_quartile     smallint,
    efficiency_quartile  smallint,
    quality_gate_failed  boolean        not null,
    evaluations          integer        not null,
    quality_average      numeric(5, 2),
    cases                bigint         not null,
    recovery_ratio       numeric(12, 4),
    proposed_tier        varchar(1)     not null,
    final_tier           varchar(1)     not null,
    adjusted_by          uuid,
    adjusted_at          timestamptz,
    adjustment_reason    varchar(2000),
    primary key (round_id, user_id),
    constraint ck_tier_assignment_quartiles check (coalesce(quality_quartile, 1) between 1 and 4
                                                   and coalesce(efficiency_quartile, 1) between 1 and 4),
    constraint ck_tier_assignment_gate check (not quality_gate_failed or coalesce(quality_quartile, 1) <= 2),
    constraint ck_tier_assignment_samples check (evaluations >= 0 and cases >= 0
                                                 and (quality_average is null or quality_average between 0 and 10)
                                                 and (recovery_ratio is null or recovery_ratio >= 0)),
    constraint ck_tier_assignment_tiers check (proposed_tier in ('A', 'B', 'C', 'D') and final_tier in ('A', 'B', 'C', 'D')),
    constraint ck_tier_assignment_adjustment check ((adjusted_by is null) = (adjusted_at is null)
                                                    and (adjusted_by is null) = (adjustment_reason is null)),
    constraint ck_tier_assignment_final check (adjusted_by is not null or final_tier = proposed_tier)
);
create index ix_tier_assignment_user on incentive.tier_assignment (tenant_id, user_id);
select shared.enable_tenant_rls('incentive.tier_assignment');

-- ajuste só com a rodada em revisão: publicada ou expirada, a pessoa × rodada fica congelada (o nível publicado é o
-- que foi emitido no evento). A aplicação lê a rodada com "for share" antes de ajustar; o gatilho é a defesa no banco
-- contra corrida com a publicação/expiração ou alteração fora do caso de uso.
create or replace function incentive.tier_assignment_round_open() returns trigger
    language plpgsql as
$$
declare
    round_status varchar(10);
begin
    select status into round_status from incentive.tier_round where id = old.round_id for share;
    if round_status is distinct from 'PROPOSED' then
        raise exception 'Classificação de desempenho: a rodada não está mais em revisão (publicada ou expirada); o ajuste não é permitido'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_tier_assignment_round_open before update on incentive.tier_assignment
    for each row execute function incentive.tier_assignment_round_open();

-- classificação é trilha: nada é apagado pela aplicação
revoke delete on incentive.tier_settings, incentive.tier_round, incentive.tier_assignment from app_runtime;
