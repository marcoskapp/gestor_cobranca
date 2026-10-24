-- =============================================================================
-- Recuperado × esperado pelo score (docs/tecnico/desenho/remuneracao-variavel.md
-- §2.5 e §10.9): safras de score publicadas pelo módulo scoring
-- (ExpectedRecoveryMeasured) depois de encerrada a janela de desfecho, por data de
-- referência × credor × carteira × responsável no momento do cálculo.
--
-- Uma linha por safra/grupo (cohort_id determinístico do produtor); republicação
-- substitui os valores (correção por estorno tardio). Lido pelo indicador
-- financial.expectedRecoveryRatio = Σ realizado ÷ Σ esperado.
-- =============================================================================
create table incentive.expected_recovery_fact (
    tenant_id        uuid           not null references organization.tenant (id),
    cohort_id        uuid           not null,
    reference_date   date           not null,
    outcome_days     integer        not null,
    creditor_id      uuid           not null,
    portfolio_id     uuid,
    user_id          uuid,
    cases            bigint         not null,
    expected_amount  numeric(19, 2) not null,
    realized_amount  numeric(19, 2) not null,
    model_id         uuid           not null,
    model_version    integer        not null,
    source_event_id  uuid           not null,
    updated_at       timestamptz    not null,
    primary key (tenant_id, cohort_id),
    constraint ck_expected_recovery_values check (cases >= 0 and expected_amount >= 0 and realized_amount >= 0
                                                  and outcome_days > 0)
);
create index ix_expected_recovery_user on incentive.expected_recovery_fact (tenant_id, user_id, reference_date);
create index ix_expected_recovery_date on incentive.expected_recovery_fact (tenant_id, reference_date);
select shared.enable_tenant_rls('incentive.expected_recovery_fact');

grant select, insert, update on incentive.expected_recovery_fact to app_runtime;
revoke delete on incentive.expected_recovery_fact from app_runtime;
