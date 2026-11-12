-- =============================================================================
-- Collection — distribuição priorizada por desempenho do negociador, fase 1
-- (docs/tecnico/desenho/distribuicao-por-desempenho.md §4 e §11.3):
--  * projeção collection.negotiator_tier do evento NegotiatorTierChanged (incentive): uma
--    linha imutável por pessoa e publicação; só o nível (nunca nota, quartil ou valores);
--  * estratégia de fila TIERED ("Por nível de desempenho"), que exige capacidade por operador;
--  * distribuição automática por fila (horários HH:mm no fuso de operação, limite por rodada,
--    instante até o qual os horários já foram tratados);
--  * parâmetros da assessoria: multiplicador de capacidade por nível (50–200%), fatia de
--    desenvolvimento (0–50%) e nível padrão de quem ainda não tem nível;
--  * motivo da distribuição por nível no histórico de atribuição: nível de quem recebeu e
--    faixa de potencial do caso. As colunas são preenchidas só na inserção: o gatilho
--    case_assignment_close_only continua recusando qualquer alteração além do fim de vigência
--    e o privilégio de coluna de UPDATE continua só em valid_to/version/updated_*.
-- =============================================================================

create table collection.negotiator_tier (
    id              uuid         not null primary key,
    tenant_id       uuid         not null references organization.tenant (id),
    version         bigint       not null,
    created_at      timestamptz  not null,
    created_by      uuid,
    updated_at      timestamptz  not null,
    updated_by      uuid,
    user_id         uuid         not null,
    tier            varchar(1),
    valid_from      date         not null,
    publication_id  uuid         not null,
    published_at    timestamptz  not null,
    constraint uk_negotiator_tier_publication unique (tenant_id, user_id, publication_id),
    constraint ck_negotiator_tier_tier check (tier in ('A', 'B', 'C', 'D'))
);
create index ix_negotiator_tier_user on collection.negotiator_tier (tenant_id, user_id, valid_from desc, published_at desc);
select shared.enable_tenant_rls('collection.negotiator_tier');
create trigger trg_negotiator_tier_append_only before update on collection.negotiator_tier
    for each row execute function shared.forbid_mutation();
-- imutável; a remoção fica para a retenção (a mesma dos fatos de desempenho do incentive)
grant select, insert, delete on collection.negotiator_tier to app_runtime;
revoke update on collection.negotiator_tier from app_runtime;

alter table collection.queue
    drop constraint ck_queue_strategy,
    add constraint ck_queue_strategy check (strategy in ('PULL', 'ROUND_ROBIN', 'LEAST_LOADED', 'TIERED')),
    add constraint ck_queue_tiered_capacity check (strategy <> 'TIERED' or max_cases_per_user is not null),
    add column auto_distribution             boolean      not null default false,
    add column auto_distribution_times       varchar(60),
    add column auto_distribution_limit       integer,
    add column auto_distribution_last_run_at timestamptz,
    add constraint ck_queue_auto_distribution_times check (auto_distribution_times is null
        or auto_distribution_times ~ '^([01][0-9]|2[0-3]):[0-5][0-9](,([01][0-9]|2[0-3]):[0-5][0-9]){0,5}$'),
    add constraint ck_queue_auto_distribution_limit check (auto_distribution_limit is null
        or auto_distribution_limit between 1 and 1000),
    add constraint ck_queue_auto_distribution check (not auto_distribution
        or (strategy <> 'PULL' and auto_distribution_times is not null and auto_distribution_limit is not null));
create index ix_queue_auto_distribution on collection.queue (tenant_id) where active and auto_distribution;

alter table collection.settings
    add column tier_capacity          jsonb       not null default '{"A": 120, "B": 110, "C": 100, "D": 80}',
    add column tier_development_share integer     not null default 15,
    add column tier_default           varchar(1)  not null default 'B',
    add constraint ck_settings_tier_capacity check (jsonb_typeof(tier_capacity) = 'object'
        and (tier_capacity ->> 'A')::integer between 50 and 200
        and (tier_capacity ->> 'B')::integer between 50 and 200
        and (tier_capacity ->> 'C')::integer between 50 and 200
        and (tier_capacity ->> 'D')::integer between 50 and 200),
    add constraint ck_settings_tier_development_share check (tier_development_share between 0 and 50),
    add constraint ck_settings_tier_default check (tier_default in ('A', 'B', 'C', 'D'));

-- checks com "not valid": case_assignment é grande e cresce a cada atribuição; validar aqui faria uma varredura com
-- bloqueio da tabela durante o deploy. As linhas novas já são conferidas; as existentes têm as colunas nulas (passam).
-- O "validate constraint" fica para uma migration posterior, rodada fora do pico.
alter table collection.case_assignment
    add column tier           varchar(1),
    add column potential_band varchar(20),
    add constraint ck_case_assignment_tier check (tier in ('A', 'B', 'C', 'D')) not valid,
    add constraint ck_case_assignment_potential_band check (potential_band in ('HIGH', 'MEDIUM', 'LOW', 'DEVELOPMENT'))
        not valid;
