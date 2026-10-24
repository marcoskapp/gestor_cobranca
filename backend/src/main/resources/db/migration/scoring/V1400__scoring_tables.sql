-- =============================================================================
-- Módulo scoring — score de propensão à recuperação e priorização (F23).
-- Desenho: docs/tecnico/desenho/scoring.md; regra de negócio: docs/negocio/score-propensao.md.
--
-- Nenhuma tabela guarda dado pessoal direto: ids, datas, valores, canais, situações
-- e features numéricas. RLS forçado por tenant em todas. O módulo não lê tabelas de
-- outros módulos (fatos chegam por eventos; a posição do caso, pelo collection).
-- =============================================================================

grant usage on schema scoring to app_runtime;

-- -----------------------------------------------------------------------------
-- Parâmetros da assessoria e registro da base legal (LGPD art. 7º IX ou X).
-- -----------------------------------------------------------------------------
create table scoring.settings (
    id                          uuid          primary key,
    tenant_id                   uuid          not null references organization.tenant (id),
    version                     bigint        not null,
    created_at                  timestamptz   not null,
    created_by                  uuid,
    updated_at                  timestamptz   not null,
    updated_by                  uuid,
    outcome_days                integer       not null,
    min_cases                   integer       not null,
    min_positives               integer       not null,
    min_partition_positives     integer       not null,
    validation_share            numeric(5, 4) not null,
    l2_lambda                   numeric(12, 6) not null,
    min_auc                     numeric(5, 4) not null,
    training_window_days        integer       not null,
    snapshot_interval_days      integer       not null,
    publish_min_delta           integer       not null,
    psi_warning                 numeric(6, 4) not null,
    psi_alert                   numeric(6, 4) not null,
    band_a                      integer       not null,
    band_b                      integer       not null,
    band_c                      integer       not null,
    band_d                      integer       not null,
    default_paid_share          numeric(5, 4) not null,
    auto_train                  boolean       not null,
    legal_basis                 varchar(30),
    legal_basis_reference       varchar(500),
    legal_basis_registered_at   timestamptz,
    legal_basis_registered_by   uuid,
    constraint uk_scoring_settings unique (tenant_id),
    constraint ck_scoring_settings_basis check (legal_basis is null or legal_basis in ('LEGITIMATE_INTEREST',
                                                                                       'CREDIT_PROTECTION')),
    constraint ck_scoring_settings_bands check (band_a <= 1000 and band_a > band_b and band_b > band_c
                                                and band_c > band_d and band_d > 0),
    constraint ck_scoring_settings_outcome check (outcome_days between 7 and 180)
);
select shared.enable_tenant_rls('scoring.settings');

-- -----------------------------------------------------------------------------
-- Versões de modelo. Definição, dados de treino, métricas e cortes são imutáveis
-- (gatilho); um campeão e um desafiante no máximo por assessoria.
-- -----------------------------------------------------------------------------
create table scoring.model (
    id                      uuid          primary key,
    tenant_id               uuid          not null references organization.tenant (id),
    version                 bigint        not null,
    created_at              timestamptz   not null,
    created_by              uuid,
    updated_at              timestamptz   not null,
    updated_by              uuid,
    model_version           integer       not null,
    kind                    varchar(30)   not null,
    status                  varchar(20)   not null,
    description             varchar(200),
    outcome_days            integer       not null,
    definition              jsonb         not null,
    band_a                  integer       not null,
    band_b                  integer       not null,
    band_c                  integer       not null,
    band_d                  integer       not null,
    paid_share              numeric(5, 4) not null,
    training_from           date,
    training_to             date,
    validation_from         date,
    validation_to           date,
    training_rows           integer       not null default 0,
    training_positives      integer       not null default 0,
    validation_rows         integer       not null default 0,
    validation_positives    integer       not null default 0,
    metrics                 jsonb,
    failure_reasons         varchar(500),
    reference_distribution  jsonb,
    trained_by              uuid,
    trained_at              timestamptz   not null,
    promoted_at             timestamptz,
    promoted_by             uuid,
    ended_at                timestamptz,
    constraint uk_scoring_model_version unique (tenant_id, model_version),
    constraint ck_scoring_model_kind check (kind in ('SCORECARD', 'LOGISTIC_REGRESSION')),
    constraint ck_scoring_model_status check (status in ('CHALLENGER', 'CHAMPION', 'RETIRED', 'REJECTED',
                                                         'SUPERSEDED', 'FAILED_VALIDATION'))
);
create unique index uk_scoring_model_champion on scoring.model (tenant_id) where status = 'CHAMPION';
create unique index uk_scoring_model_challenger on scoring.model (tenant_id) where status = 'CHALLENGER';
select shared.enable_tenant_rls('scoring.model');

create function scoring.model_immutable() returns trigger language plpgsql as $$
begin
    if new.definition is distinct from old.definition or new.kind is distinct from old.kind
       or new.model_version is distinct from old.model_version or new.metrics is distinct from old.metrics
       or new.band_a is distinct from old.band_a or new.band_b is distinct from old.band_b
       or new.band_c is distinct from old.band_c or new.band_d is distinct from old.band_d
       or new.paid_share is distinct from old.paid_share or new.outcome_days is distinct from old.outcome_days
       or new.trained_at is distinct from old.trained_at or new.trained_by is distinct from old.trained_by
       or (old.reference_distribution is not null
           and new.reference_distribution is distinct from old.reference_distribution) then
        raise exception 'scoring.model: definição, métricas e treino são imutáveis' using errcode = '42501';
    end if;
    return new;
end;
$$;
create trigger model_immutable before update on scoring.model
    for each row execute function scoring.model_immutable();

-- Decisões de governança (promoção/rejeição): append-only.
create table scoring.model_decision (
    id                    uuid          primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    model_id              uuid          not null references scoring.model (id),
    decision              varchar(20)   not null,
    previous_champion_id  uuid          references scoring.model (id),
    justification         varchar(1000) not null,
    comparison            jsonb         not null,
    decided_by            uuid          not null,
    decided_at            timestamptz   not null,
    constraint ck_scoring_decision check (decision in ('PROMOTE', 'REJECT'))
);
create index ix_scoring_decision_model on scoring.model_decision (tenant_id, model_id);
select shared.enable_tenant_rls('scoring.model_decision');

-- -----------------------------------------------------------------------------
-- Score vigente por caso (explicação: features e contribuições do último cálculo).
-- -----------------------------------------------------------------------------
create table scoring.case_score (
    id                   uuid          primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    case_id              uuid          not null,
    debtor_id            uuid          not null,
    creditor_id          uuid          not null,
    portfolio_id         uuid          not null,
    model_id             uuid          not null references scoring.model (id),
    model_version        integer       not null,
    model_kind           varchar(30)   not null,
    score                integer       not null,
    band                 varchar(1)    not null,
    probability          numeric(9, 6) not null,
    features             jsonb         not null,
    contributions        jsonb         not null,
    reference_date       date          not null,
    calculated_at        timestamptz   not null,
    calculation_trigger  varchar(20)   not null,
    published_score      integer,
    published_band       varchar(1),
    published_at         timestamptz,
    constraint uk_scoring_case_score unique (tenant_id, case_id),
    constraint ck_scoring_case_score check (score between 0 and 1000 and band in ('A', 'B', 'C', 'D', 'E')),
    constraint ck_scoring_case_score_trigger check (calculation_trigger in ('BATCH', 'ON_DEMAND', 'EVENT', 'REVIEW'))
);
create index ix_scoring_case_score_debtor on scoring.case_score (tenant_id, debtor_id);
create index ix_scoring_case_score_creditor on scoring.case_score (tenant_id, creditor_id, band);
select shared.enable_tenant_rls('scoring.case_score');

-- Revisões de score (LGPD art. 20): append-only.
create table scoring.score_review (
    id                  uuid          primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    version             bigint        not null,
    created_at          timestamptz   not null,
    created_by          uuid,
    updated_at          timestamptz   not null,
    updated_by          uuid,
    case_id             uuid          not null,
    creditor_id         uuid          not null,
    origin              varchar(30)   not null,
    request_reference   uuid,
    previous_score      integer,
    previous_band       varchar(1),
    previous_model_id   uuid,
    new_score           integer       not null,
    new_band            varchar(1)    not null,
    new_model_id        uuid          not null,
    reviewed_by         uuid          not null,
    reviewed_at         timestamptz   not null,
    constraint ck_scoring_review_origin check (origin in ('DATA_SUBJECT_REQUEST', 'OPERATION_REQUEST', 'QUALITY_CHECK'))
);
create index ix_scoring_review_case on scoring.score_review (tenant_id, case_id, reviewed_at);
select shared.enable_tenant_rls('scoring.score_review');

-- -----------------------------------------------------------------------------
-- Execuções (lote diário por data de referência; treino).
-- -----------------------------------------------------------------------------
create table scoring.run (
    id                   uuid          primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    kind                 varchar(20)   not null,
    run_trigger          varchar(20)   not null,
    status               varchar(20)   not null,
    reference_date       date          not null,
    reference_at         timestamptz   not null,
    snapshot             boolean       not null,
    outcome_days         integer       not null,
    champion_model_id    uuid,
    challenger_model_id  uuid,
    result_model_id      uuid,
    cursor_case_id       uuid,
    scored               integer       not null default 0,
    skipped              integer       not null default 0,
    published            integer       not null default 0,
    band_changes         integer       not null default 0,
    failures             integer       not null default 0,
    details              jsonb,
    requested_by         uuid,
    started_at           timestamptz   not null,
    finished_at          timestamptz,
    cohort_measured_at   timestamptz,
    constraint ck_scoring_run_kind check (kind in ('BATCH', 'TRAINING')),
    constraint ck_scoring_run_trigger check (run_trigger in ('SCHEDULED', 'MANUAL')),
    constraint ck_scoring_run_status check (status in ('RUNNING', 'COMPLETED', 'FAILED', 'INSUFFICIENT_DATA'))
);
create unique index uk_scoring_run_batch_date on scoring.run (tenant_id, reference_date) where kind = 'BATCH';
create index ix_scoring_run_list on scoring.run (tenant_id, kind, started_at desc);
select shared.enable_tenant_rls('scoring.run');

-- Monitoramento (estabilidade e desempenho realizado) por snapshot.
create table scoring.monitoring_report (
    id              uuid          primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    run_id          uuid          not null references scoring.run (id),
    model_id        uuid          not null references scoring.model (id),
    reference_date  date          not null,
    status          varchar(20)   not null,
    population      integer       not null,
    score_psi       numeric(9, 6),
    feature_psi     jsonb         not null,
    realized        jsonb         not null,
    alerts          jsonb         not null,
    constraint uk_scoring_monitoring_run unique (tenant_id, run_id),
    constraint ck_scoring_monitoring_status check (status in ('STABLE', 'WARNING', 'ALERT'))
);
create index ix_scoring_monitoring_date on scoring.monitoring_report (tenant_id, reference_date desc);
select shared.enable_tenant_rls('scoring.monitoring_report');

-- -----------------------------------------------------------------------------
-- Fatos do relacionamento de cobrança (a partir de eventos de integração),
-- append-only; apagados só por retenção ou anonimização do devedor.
--   occurred_at  quando o fato passou a ser conhecido (corte das features)
--   effective_at quando teve efeito (data do pagamento)
-- -----------------------------------------------------------------------------
create table scoring.case_fact (
    tenant_id        uuid           not null references organization.tenant (id),
    source_event_id  uuid           not null,
    kind             varchar(30)    not null,
    status           varchar(20),
    case_id          uuid,
    debtor_id        uuid           not null,
    creditor_id      uuid,
    portfolio_id     uuid,
    reference_id     uuid,
    channel          varchar(20),
    amount           numeric(19, 2),
    occurred_at      timestamptz    not null,
    effective_at     timestamptz    not null,
    recorded_at      timestamptz    not null default now(),
    primary key (tenant_id, source_event_id, kind),
    constraint ck_scoring_fact_kind check (kind in ('CONTACT_ATTEMPT', 'EFFECTIVE_CONTACT', 'DEBTOR_RESPONSE',
                                                    'PROMISE', 'AGREEMENT', 'PAYMENT', 'PAYMENT_REVERSAL'))
);
create index ix_scoring_fact_debtor on scoring.case_fact (tenant_id, debtor_id, occurred_at);
create index ix_scoring_fact_payment on scoring.case_fact (tenant_id, debtor_id, portfolio_id, effective_at)
    where kind = 'PAYMENT';
create index ix_scoring_fact_reversal on scoring.case_fact (tenant_id, reference_id) where kind = 'PAYMENT_REVERSAL';
create index ix_scoring_fact_retention on scoring.case_fact (tenant_id, occurred_at);
select shared.enable_tenant_rls('scoring.case_fact');

-- -----------------------------------------------------------------------------
-- Snapshot de features por data de referência (base do treino e do monitoramento).
-- Só as colunas do desfecho (e o devedor, na anonimização) são atualizáveis.
-- -----------------------------------------------------------------------------
create table scoring.feature_snapshot (
    tenant_id               uuid           not null references organization.tenant (id),
    id                      uuid           not null,
    run_id                  uuid           not null references scoring.run (id),
    reference_date          date           not null,
    reference_at            timestamptz    not null,
    case_id                 uuid           not null,
    debtor_id               uuid,
    creditor_id             uuid           not null,
    portfolio_id            uuid           not null,
    assignee_user_id        uuid,
    case_status             varchar(20)    not null,
    open_balance            numeric(19, 2) not null,
    features                jsonb          not null,
    champion_model_id       uuid           not null references scoring.model (id),
    champion_probability    numeric(9, 6)  not null,
    champion_score          integer        not null,
    band                    varchar(1)     not null,
    challenger_model_id     uuid           references scoring.model (id),
    challenger_probability  numeric(9, 6),
    outcome_due_at          timestamptz    not null,
    outcome_resolved        boolean        not null default false,
    outcome_paid            boolean,
    outcome_amount          numeric(19, 2),
    outcome_resolved_at     timestamptz,
    primary key (tenant_id, id),
    constraint uk_scoring_snapshot_case_date unique (tenant_id, case_id, reference_date)
);
create index ix_scoring_snapshot_pending on scoring.feature_snapshot (tenant_id, outcome_due_at)
    where not outcome_resolved;
create index ix_scoring_snapshot_date on scoring.feature_snapshot (tenant_id, reference_date);
create index ix_scoring_snapshot_run on scoring.feature_snapshot (tenant_id, run_id);
create index ix_scoring_snapshot_debtor on scoring.feature_snapshot (tenant_id, debtor_id);
select shared.enable_tenant_rls('scoring.feature_snapshot');

-- Recuperado × esperado por safra × credor × carteira × responsável.
create table scoring.cohort_outcome (
    tenant_id         uuid           not null references organization.tenant (id),
    cohort_id         uuid           not null,
    run_id            uuid           not null references scoring.run (id),
    reference_date    date           not null,
    outcome_days      integer        not null,
    creditor_id       uuid           not null,
    portfolio_id      uuid           not null,
    assignee_user_id  uuid,
    model_id          uuid           not null references scoring.model (id),
    model_version     integer        not null,
    cases             bigint         not null,
    expected_payers   numeric(19, 6) not null,
    realized_payers   bigint         not null,
    expected_amount   numeric(19, 2) not null,
    realized_amount   numeric(19, 2) not null,
    measured_at       timestamptz    not null,
    primary key (tenant_id, cohort_id)
);
create index ix_scoring_cohort_date on scoring.cohort_outcome (tenant_id, reference_date);
select shared.enable_tenant_rls('scoring.cohort_outcome');

-- Registros append-only (além dos privilégios): decisões, revisões e monitoramento.
create trigger model_decision_append_only before update or delete on scoring.model_decision
    for each row execute function shared.forbid_mutation();
create trigger score_review_append_only before update or delete on scoring.score_review
    for each row execute function shared.forbid_mutation();
create trigger monitoring_report_append_only before update or delete on scoring.monitoring_report
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Privilégios do papel da aplicação.
-- -----------------------------------------------------------------------------
grant select, insert, update on scoring.settings, scoring.model, scoring.case_score, scoring.run,
    scoring.cohort_outcome to app_runtime;
grant delete on scoring.case_score to app_runtime;
grant select, insert on scoring.model_decision, scoring.score_review, scoring.monitoring_report to app_runtime;
grant select, insert, delete on scoring.case_fact to app_runtime;
grant select, insert, delete on scoring.feature_snapshot to app_runtime;
grant update (outcome_resolved, outcome_paid, outcome_amount, outcome_resolved_at, debtor_id)
    on scoring.feature_snapshot to app_runtime;
