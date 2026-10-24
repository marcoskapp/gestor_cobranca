-- =============================================================================
-- Módulo analytics (M08; F11 painel operacional, F18 relatórios de recuperação e
-- produtividade) — docs/tecnico/desenho/analytics.md.
--
-- O analytics não chama casos de uso de outros módulos: lê o banco por views
-- próprias (contrato em R__analytics_contract.sql, executado depois de todas as
-- migrations versionadas, porque lê também tabelas do incentive — V18xx).
--
-- Este arquivo: funções auxiliares puras e as tabelas materializadas por assessoria
-- ("snapshots"). Não se usa MATERIALIZED VIEW do PostgreSQL porque (1) ela não aceita
-- RLS e guardaria todas as assessorias num objeto sem isolamento; (2) o REFRESH roda
-- como dono e, com FORCE ROW LEVEL SECURITY nas tabelas de origem, não enxerga linha
-- alguma sem app.tenant_id; (3) o refresh é de todas as assessorias de uma vez. Aqui
-- cada snapshot é uma tabela comum com RLS forçado, reconstruída por assessoria pelo
-- job (delete + insert na mesma transação: leitores veem a versão anterior até o
-- commit), lendo as views security_invoker — o isolamento vale na leitura e na gravação.
-- =============================================================================

create schema if not exists analytics;
grant usage on schema analytics to app_runtime;

-- -----------------------------------------------------------------------------
-- Faixa de atraso (dias de atraso na data de referência). NOT_DUE = em dia/a vencer
-- (dias <= 0); demais faixas com os códigos do incentive (DelayBucket) para que os
-- painéis e os fatos dos negociadores sejam comparáveis.
-- -----------------------------------------------------------------------------
create or replace function analytics.delay_bucket(days integer) returns varchar
    language sql immutable parallel safe
as $$
    select case
        when days is null then 'UNKNOWN'
        when days <= 0 then 'NOT_DUE'
        when days <= 30 then 'D0_30'
        when days <= 90 then 'D31_90'
        when days <= 180 then 'D91_180'
        when days <= 360 then 'D181_360'
        else 'D361_PLUS'
    end
$$;

-- -----------------------------------------------------------------------------
-- Grupo de origem do acordo: humano × assistente × portal × campanha. Quem fecha
-- com o assistente como ferramenta (copiloto) ou negocia de fato depois da
-- transferência é humano; acordo que o assistente deixou pronto e o humano só
-- confirmou conta para o assistente (mesma leitura dos pesos do incentive, §3.2).
-- Recebimento sem acordo (pagamento direto de título) = NO_AGREEMENT. Origem nova
-- no negotiation cai em OTHER e quebra o teste de contrato (AnalyticsContractIT).
-- -----------------------------------------------------------------------------
create or replace function analytics.origin_group(origin varchar) returns varchar
    language sql immutable parallel safe
as $$
    select case
        when origin is null then 'NO_AGREEMENT'
        when origin in ('HUMAN', 'HUMAN_COPILOT', 'ASSISTANT_HANDOFF_NEGOTIATED') then 'HUMAN'
        when origin in ('ASSISTANT_AUTONOMOUS', 'ASSISTANT_HANDOFF_CONFIRMED') then 'ASSISTANT'
        when origin = 'PORTAL_SELF_SERVICE' then 'PORTAL'
        when origin = 'CAMPAIGN' then 'CAMPAIGN'
        else 'OTHER'
    end
$$;

-- -----------------------------------------------------------------------------
-- Recuperação diária: recebimentos confirmados (settlement.receipt — a mesma base do
-- demonstrativo ao credor), líquidos de estornos, na data do fato (pagamento: data do
-- pagamento; estorno: data do estorno). Acordo não pago não aparece aqui.
-- -----------------------------------------------------------------------------
create table analytics.recovery_daily (
    tenant_id        uuid           not null references organization.tenant (id),
    fact_date        date           not null,
    creditor_id      uuid           not null,
    portfolio_id     uuid,
    operator_id      uuid,
    team_id          uuid,
    channel          varchar(20)    not null,
    origin           varchar(40)    not null,
    origin_group     varchar(20)    not null,
    delay_bucket     varchar(20)    not null,
    recovered_gross  numeric(19, 2) not null,
    reversed         numeric(19, 2) not null,
    receipts         integer        not null,
    constraint ck_recovery_daily_amounts check (recovered_gross >= 0 and reversed >= 0 and receipts >= 0)
);
create index ix_recovery_daily_date on analytics.recovery_daily (tenant_id, fact_date);
create index ix_recovery_daily_creditor on analytics.recovery_daily (tenant_id, creditor_id, fact_date);
create index ix_recovery_daily_operator on analytics.recovery_daily (tenant_id, operator_id, fact_date)
    where operator_id is not null;
select shared.enable_tenant_rls('analytics.recovery_daily');

-- -----------------------------------------------------------------------------
-- Safra de acordos: um registro por acordo, com o que foi negociado e o que foi
-- efetivamente pago (recebimentos líquidos), 1ª parcela, quebra e renegociação.
-- -----------------------------------------------------------------------------
create table analytics.agreement_cohort (
    tenant_id                  uuid           not null references organization.tenant (id),
    agreement_id               uuid           not null,
    creditor_id                uuid           not null,
    portfolio_id               uuid,
    case_id                    uuid,
    operator_id                uuid,
    team_id                    uuid,
    channel                    varchar(20)    not null,
    origin                     varchar(40)    not null,
    origin_group               varchar(20)    not null,
    formalized_at              timestamptz    not null,
    formalized_on              date           not null,
    status                     varchar(20)    not null,
    ended_at                   timestamptz,
    broken_at                  timestamptz,
    renegotiation              boolean        not null,
    total_amount               numeric(19, 2) not null,
    negotiated_amount          numeric(19, 2) not null,
    discount_amount            numeric(19, 2) not null,
    installment_count          integer        not null,
    first_installment_paid_at  timestamptz,
    first_receipt_on           date,
    paid_net                   numeric(19, 2) not null,
    primary key (tenant_id, agreement_id)
);
create index ix_agreement_cohort_formalized on analytics.agreement_cohort (tenant_id, formalized_on);
select shared.enable_tenant_rls('analytics.agreement_cohort');

-- -----------------------------------------------------------------------------
-- Posição da carteira (títulos em cobrança) na data de referência do refresh.
-- -----------------------------------------------------------------------------
create table analytics.portfolio_position (
    tenant_id         uuid           not null references organization.tenant (id),
    reference_date    date           not null,
    creditor_id       uuid           not null,
    portfolio_id      uuid           not null,
    delay_bucket      varchar(20)    not null,
    open_titles       integer        not null,
    portfolio_amount  numeric(19, 2) not null,
    overdue_titles    integer        not null,
    overdue_amount    numeric(19, 2) not null
);
create index ix_portfolio_position on analytics.portfolio_position (tenant_id, creditor_id, portfolio_id);
select shared.enable_tenant_rls('analytics.portfolio_position');

-- Casos não encerrados por estado, elegibilidade, dono e fila (saldo elegível e fila pendente).
create table analytics.case_position (
    tenant_id         uuid           not null references organization.tenant (id),
    reference_date    date           not null,
    creditor_id       uuid           not null,
    portfolio_id      uuid           not null,
    delay_bucket      varchar(20)    not null,
    status            varchar(20)    not null,
    eligible          boolean        not null,
    owner_type        varchar(20)    not null,
    queue_id          uuid,
    cases             integer        not null,
    open_balance      numeric(19, 2) not null,
    eligible_balance  numeric(19, 2) not null
);
create index ix_case_position on analytics.case_position (tenant_id, creditor_id, portfolio_id);
select shared.enable_tenant_rls('analytics.case_position');

-- -----------------------------------------------------------------------------
-- Carimbo "dados até" por assessoria e conjunto de dados.
-- -----------------------------------------------------------------------------
create table analytics.refresh_state (
    tenant_id     uuid          not null references organization.tenant (id),
    dataset       varchar(40)   not null,
    data_until    timestamptz   not null,
    refreshed_at  timestamptz   not null,
    duration_ms   bigint        not null,
    row_count     bigint        not null,
    primary key (tenant_id, dataset),
    constraint ck_refresh_state_dataset check (dataset in ('recovery_daily', 'agreement_cohort', 'portfolio_position',
                                                           'case_position')),
    constraint ck_refresh_state_values check (duration_ms >= 0 and row_count >= 0)
);
select shared.enable_tenant_rls('analytics.refresh_state');

-- privilégio mínimo (o schema concede select/insert/update/delete por padrão): snapshots
-- só são reconstruídos (delete + insert) e o carimbo só é gravado/atualizado.
revoke update on analytics.recovery_daily, analytics.agreement_cohort, analytics.portfolio_position,
    analytics.case_position from app_runtime;
revoke delete on analytics.refresh_state from app_runtime;
grant select, insert, delete on analytics.recovery_daily, analytics.agreement_cohort, analytics.portfolio_position,
    analytics.case_position to app_runtime;
grant select, insert, update on analytics.refresh_state to app_runtime;
grant execute on function analytics.delay_bucket(integer), analytics.origin_group(varchar) to app_runtime;
