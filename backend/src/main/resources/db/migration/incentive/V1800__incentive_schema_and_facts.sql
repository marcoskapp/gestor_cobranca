-- =============================================================================
-- Módulo incentive (métricas dos negociadores e remuneração variável) —
-- docs/design/remuneracao-variavel.md.
--
-- Este arquivo: schema e fatos operacionais materializados a partir de eventos de
-- integração (o módulo não lê tabelas de outros módulos). Todas as tabelas têm
-- RLS forçado por tenant. Os fatos são acumulados por consumidores idempotentes
-- (shared.consumed_event), por isso os contadores podem ser incrementais.
-- =============================================================================

create schema if not exists incentive;
grant usage on schema incentive to app_runtime;
alter default privileges in schema incentive grant select, insert, update, delete on tables to app_runtime;
alter default privileges in schema incentive grant usage, select on sequences to app_runtime;

-- -----------------------------------------------------------------------------
-- Fato diário de desempenho por negociador × segmento (credor, carteira, faixa
-- de atraso) × canal. Medidas aditivas; as fórmulas dos indicadores (§2) são
-- aplicadas na consulta. Lido pelo analytics por views. Correções entram como
-- incrementos compensatórios no dia em que ocorrem (ex.: 1ª parcela revertida = −1).
-- -----------------------------------------------------------------------------
create table incentive.performance_fact (
    tenant_id             uuid           not null references organization.tenant (id),
    fact_date             date           not null,
    user_id               uuid           not null,
    creditor_id           uuid,
    portfolio_id          uuid,
    delay_bucket          varchar(20)    not null,
    channel               varchar(20)    not null,
    attempts              integer        not null default 0,
    effective_contacts    integer        not null default 0,
    answered_calls        integer        not null default 0,
    talk_seconds          bigint         not null default 0,
    agreements            integer        not null default 0,
    negotiated_amount     numeric(19, 2) not null default 0,
    agreement_amount      numeric(19, 2) not null default 0,
    updated_balance       numeric(19, 2) not null default 0,
    discount_amount       numeric(19, 2) not null default 0,
    down_payment_amount   numeric(19, 2) not null default 0,
    envelope_usage_sum    numeric(19, 4) not null default 0,
    envelope_count        integer        not null default 0,
    concession_level_sum  integer        not null default 0,
    concession_count      integer        not null default 0,
    first_installments    integer        not null default 0,
    accrued_amount        numeric(19, 2) not null default 0,
    reversed_amount       numeric(19, 2) not null default 0,
    constraint ck_performance_fact_bucket check (delay_bucket in ('D0_30', 'D31_90', 'D91_180', 'D181_360',
                                                                  'D361_PLUS', 'UNKNOWN')),
    constraint ck_performance_fact_counts check (attempts >= 0 and effective_contacts >= 0 and answered_calls >= 0
                                                 and talk_seconds >= 0 and agreements >= 0 and envelope_count >= 0
                                                 and concession_count >= 0 and reversed_amount >= 0)
);
create unique index uk_performance_fact on incentive.performance_fact
    (tenant_id, fact_date, user_id, creditor_id, portfolio_id, delay_bucket, channel) nulls not distinct;
create index ix_performance_fact_user on incentive.performance_fact (tenant_id, user_id, fact_date);
create index ix_performance_fact_creditor on incentive.performance_fact (tenant_id, creditor_id, fact_date);
select shared.enable_tenant_rls('incentive.performance_fact');

-- Casos trabalhados (contagem distinta no período): caso com ≥ 1 interação do negociador no dia.
create table incentive.worked_case_day (
    tenant_id     uuid         not null references organization.tenant (id),
    fact_date     date         not null,
    user_id       uuid         not null,
    case_id       uuid         not null,
    creditor_id   uuid,
    portfolio_id  uuid,
    channel       varchar(20)  not null,
    primary key (tenant_id, fact_date, user_id, case_id, channel)
);
create index ix_worked_case_day_user on incentive.worked_case_day (tenant_id, user_id, fact_date);
select shared.enable_tenant_rls('incentive.worked_case_day');

-- Tempo de resposta em conversa: espera aberta por conversa (primeira mensagem do
-- devedor ainda não respondida) e amostras (mediana calculada na consulta).
create table incentive.conversation_wait (
    tenant_id        uuid          not null references organization.tenant (id),
    conversation_id  uuid          not null,
    waiting_since    timestamptz   not null,
    primary key (tenant_id, conversation_id)
);
select shared.enable_tenant_rls('incentive.conversation_wait');

create table incentive.response_sample (
    tenant_id         uuid          not null references organization.tenant (id),
    message_id        uuid          not null,
    fact_date         date          not null,
    user_id           uuid          not null,
    creditor_id       uuid,
    portfolio_id      uuid,
    channel           varchar(20)   not null,
    response_seconds  bigint        not null,
    primary key (tenant_id, message_id),
    constraint ck_response_sample_seconds check (response_seconds >= 0)
);
create index ix_response_sample_user on incentive.response_sample (tenant_id, user_id, fact_date);
select shared.enable_tenant_rls('incentive.response_sample');

-- Tempo logado por dia, obtido das sessões do identity (job; substitui o valor do dia).
create table incentive.logged_time_daily (
    tenant_id   uuid    not null references organization.tenant (id),
    fact_date   date    not null,
    user_id     uuid    not null,
    seconds     bigint  not null,
    primary key (tenant_id, fact_date, user_id),
    constraint ck_logged_time_seconds check (seconds >= 0 and seconds <= 86400)
);
select shared.enable_tenant_rls('incentive.logged_time_daily');

-- Contatos efetivos humanos por caso (janela de influência de portal/campanha).
create table incentive.effective_contact (
    tenant_id       uuid          not null references organization.tenant (id),
    interaction_id  uuid          not null,
    case_id         uuid          not null,
    operator_id     uuid          not null,
    occurred_at     timestamptz   not null,
    primary key (tenant_id, interaction_id)
);
create index ix_effective_contact_case on incentive.effective_contact (tenant_id, case_id, occurred_at desc);
select shared.enable_tenant_rls('incentive.effective_contact');

-- Histórico de responsável/saldo elegível do caso (append-only): modelo de crédito
-- por carteira e denominador da taxa de recuperação.
create table incentive.case_snapshot (
    tenant_id         uuid           not null references organization.tenant (id),
    source_event_id   uuid           not null,
    case_id           uuid           not null,
    effective_at      timestamptz    not null,
    creditor_id       uuid,
    portfolio_id      uuid,
    assignee_id       uuid,
    team_id           uuid,
    status            varchar(30),
    eligible          boolean,
    eligible_balance  numeric(19, 2),
    primary key (tenant_id, source_event_id)
);
create index ix_case_snapshot_case on incentive.case_snapshot (tenant_id, case_id, effective_at desc);
create index ix_case_snapshot_assignee on incentive.case_snapshot (tenant_id, assignee_id, effective_at);
select shared.enable_tenant_rls('incentive.case_snapshot');
create trigger trg_case_snapshot_append_only before update or delete on incentive.case_snapshot
    for each row execute function shared.forbid_mutation();

-- Ciclo de vida do acordo (status, quebra, 1ª parcela). Os eventos de acordo, de
-- parcela e de atribuição chegam em qualquer ordem: a linha é mesclada (upsert).
create table incentive.agreement_lifecycle (
    tenant_id                  uuid           not null references organization.tenant (id),
    agreement_id               uuid           not null,
    status                     varchar(20),
    status_at                  timestamptz,
    formalized_at              timestamptz,
    broken_at                  timestamptz,
    renegotiated_at            timestamptz,
    replaced_agreement_id      uuid,
    total_amount               numeric(19, 2),
    first_installment_paid_at  timestamptz,
    primary key (tenant_id, agreement_id),
    constraint ck_agreement_lifecycle_status check (status in ('FORMALIZED', 'IN_PAYMENT', 'SETTLED', 'BROKEN',
                                                               'CANCELLED', 'RENEGOTIATED'))
);
create index ix_agreement_lifecycle_replaced on incentive.agreement_lifecycle (tenant_id, replaced_agreement_id)
    where replaced_agreement_id is not null;
select shared.enable_tenant_rls('incentive.agreement_lifecycle');

grant select, insert, update, delete on all tables in schema incentive to app_runtime;
revoke update, delete on incentive.case_snapshot, incentive.effective_contact, incentive.response_sample
    from app_runtime;
