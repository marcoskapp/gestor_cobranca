-- =============================================================================
-- incentive: atribuição de crédito dos acordos, fatos de pagamento/estorno e
-- planos de comissão versionados (com metas por período).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Atribuição do acordo (AgreementAttributionRecorded) e créditos resolvidos pelo
-- plano vigente na formalização. Registrada uma vez; nunca reconstruída.
-- -----------------------------------------------------------------------------
create table incentive.agreement_attribution (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    source_event_id        uuid           not null,
    agreement_id           uuid           not null,
    case_id                uuid,
    debtor_id              uuid,
    creditor_id            uuid           not null,
    portfolio_id           uuid,
    team_id                uuid,
    origin                 varchar(40)    not null,
    channel                varchar(20)    not null,
    concession_level       integer,
    envelope_usage         numeric(9, 4),
    days_past_due          integer,
    delay_bucket           varchar(20)    not null,
    agreement_amount       numeric(19, 2) not null,
    updated_balance        numeric(19, 2),
    discount_amount        numeric(19, 2),
    down_payment_amount    numeric(19, 2),
    replaced_agreement_id  uuid,
    incremental_amount     numeric(19, 2) not null,
    formalized_at          timestamptz    not null,
    plan_id                uuid,
    forecast_commission    numeric(19, 2),
    constraint uk_agreement_attribution unique (tenant_id, agreement_id),
    constraint ck_attribution_origin check (origin in ('HUMAN', 'HUMAN_COPILOT', 'ASSISTANT_HANDOFF_NEGOTIATED',
                                                       'ASSISTANT_HANDOFF_CONFIRMED', 'ASSISTANT_AUTONOMOUS',
                                                       'PORTAL_SELF_SERVICE', 'CAMPAIGN')),
    constraint ck_attribution_bucket check (delay_bucket in ('D0_30', 'D31_90', 'D91_180', 'D181_360', 'D361_PLUS',
                                                             'UNKNOWN')),
    constraint ck_attribution_amounts check (agreement_amount >= 0 and incremental_amount >= 0
                                             and incremental_amount <= agreement_amount),
    constraint ck_attribution_envelope check (envelope_usage is null or envelope_usage <= 100)
);
create index ix_attribution_formalized on incentive.agreement_attribution (tenant_id, formalized_at);
create index ix_attribution_case on incentive.agreement_attribution (tenant_id, case_id);
select shared.enable_tenant_rls('incentive.agreement_attribution');

create table incentive.attribution_participant (
    attribution_id  uuid          not null references incentive.agreement_attribution (id),
    position        integer       not null,
    actor_type      varchar(20)   not null,
    actor_id        uuid          not null,
    role            varchar(20)   not null,
    primary key (attribution_id, position),
    constraint ck_participant_actor check (actor_type in ('USER', 'ASSISTANT', 'DEBTOR', 'SYSTEM')),
    constraint ck_participant_role check (role in ('CLOSER', 'ORIGINATOR', 'PRE_NEGOTIATOR', 'APPROVER', 'INFLUENCER'))
);
alter table incentive.attribution_participant enable row level security;
alter table incentive.attribution_participant force row level security;
create policy tenant_isolation on incentive.attribution_participant
    using (exists (select 1 from incentive.agreement_attribution a where a.id = attribution_id))
    with check (exists (select 1 from incentive.agreement_attribution a where a.id = attribution_id));

create table incentive.attribution_credit (
    attribution_id        uuid           not null references incentive.agreement_attribution (id),
    position              integer        not null,
    beneficiary_type      varchar(10)    not null,
    beneficiary_id        uuid           not null,
    role                  varchar(20),
    weight                numeric(9, 4)  not null,
    renegotiation_factor  numeric(9, 4)  not null,
    reason                varchar(60)    not null,
    primary key (attribution_id, position),
    constraint ck_credit_beneficiary check (beneficiary_type in ('USER', 'TEAM')),
    constraint ck_credit_weight check (weight between 0 and 100 and renegotiation_factor between 0 and 100)
);
create index ix_attribution_credit_beneficiary on incentive.attribution_credit (beneficiary_id);
alter table incentive.attribution_credit enable row level security;
alter table incentive.attribution_credit force row level security;
create policy tenant_isolation on incentive.attribution_credit
    using (exists (select 1 from incentive.agreement_attribution a where a.id = attribution_id))
    with check (exists (select 1 from incentive.agreement_attribution a where a.id = attribution_id));

-- atribuição é imutável depois de registrada (só a previsão informativa é preenchida no registro)
create trigger trg_attribution_participant_immutable before update or delete on incentive.attribution_participant
    for each row execute function shared.forbid_mutation();
create trigger trg_attribution_credit_immutable before update or delete on incentive.attribution_credit
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Pagamentos confirmados e estornos recebidos (fatos comissionáveis). Lançados no
-- razão quando a atribuição do acordo existe (os eventos chegam em qualquer ordem).
-- -----------------------------------------------------------------------------
create table incentive.payment_fact (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    source_event_id        uuid           not null,
    payment_id             uuid           not null,
    agreement_id           uuid           not null,
    creditor_id            uuid           not null,
    portfolio_id           uuid,
    total_amount           numeric(19, 2) not null,
    commissionable_amount  numeric(19, 2) not null,
    paid_at                timestamptz    not null,
    confirmed_at           timestamptz    not null,
    posted_at              timestamptz,
    constraint uk_payment_fact unique (tenant_id, payment_id),
    constraint ck_payment_fact_amounts check (total_amount > 0 and commissionable_amount >= 0
                                              and commissionable_amount <= total_amount)
);
create index ix_payment_fact_unposted on incentive.payment_fact (tenant_id, agreement_id) where posted_at is null;
select shared.enable_tenant_rls('incentive.payment_fact');

create table incentive.payment_reversal_fact (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    source_event_id  uuid           not null,
    reversal_id      uuid           not null,
    payment_id       uuid           not null,
    agreement_id     uuid,
    amount           numeric(19, 2) not null,
    reason           varchar(200),
    reversed_at      timestamptz    not null,
    posted_at        timestamptz,
    constraint uk_payment_reversal_fact unique (tenant_id, reversal_id),
    constraint ck_payment_reversal_amount check (amount > 0)
);
create index ix_payment_reversal_payment on incentive.payment_reversal_fact (tenant_id, payment_id);
select shared.enable_tenant_rls('incentive.payment_reversal_fact');

-- -----------------------------------------------------------------------------
-- Planos de comissão: cada linha é uma versão (code + plan_version). Regras
-- tipadas serializadas em jsonb com versão de esquema (validadas pela aplicação
-- antes de gravar). Versões publicadas têm regras imutáveis (gatilho).
-- -----------------------------------------------------------------------------
create table incentive.commission_plan (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    code                  varchar(40)    not null,
    plan_version          integer        not null,
    name                  varchar(120)   not null,
    description           varchar(1000),
    status                varchar(20)    not null,
    priority              integer        not null,
    effective_from        date,
    effective_to          date,
    rules                 jsonb          not null,
    rules_schema_version  integer        not null,
    published_at          timestamptz,
    published_by          uuid,
    retired_at            timestamptz,
    retired_by            uuid,
    constraint uk_commission_plan_version unique (tenant_id, code, plan_version),
    constraint ck_commission_plan_status check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    constraint ck_commission_plan_code check (code ~ '^[A-Z0-9_]{2,40}$'),
    constraint ck_commission_plan_version check (plan_version > 0 and rules_schema_version > 0),
    constraint ck_commission_plan_period check (effective_to is null or effective_from is null
                                                or effective_to >= effective_from - 1),
    constraint ck_commission_plan_published check (status = 'DRAFT' or (effective_from is not null
                                                   and published_at is not null)),
    constraint ck_commission_plan_month_start check (effective_from is null or extract(day from effective_from) = 1)
);
create unique index uk_commission_plan_draft on incentive.commission_plan (tenant_id, code) where status = 'DRAFT';
create index ix_commission_plan_effective on incentive.commission_plan (tenant_id, status, effective_from);
select shared.enable_tenant_rls('incentive.commission_plan');

create or replace function incentive.commission_plan_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then
            raise exception 'Plano de comissão publicado não pode ser removido' using errcode = 'insufficient_privilege';
        end if;
        return old;
    end if;
    if old.status <> 'DRAFT' and (new.rules is distinct from old.rules or new.code <> old.code
                                  or new.plan_version <> old.plan_version
                                  or new.effective_from is distinct from old.effective_from
                                  or new.rules_schema_version <> old.rules_schema_version) then
        raise exception 'Versão publicada de plano de comissão é imutável' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_commission_plan_guard before update or delete on incentive.commission_plan
    for each row execute function incentive.commission_plan_guard();

-- Metas por período (individual ou de equipe); sobrepõem a meta padrão do plano.
create table incentive.goal_target (
    id             uuid           not null primary key,
    tenant_id      uuid           not null references organization.tenant (id),
    version        bigint         not null,
    created_at     timestamptz    not null,
    created_by     uuid,
    updated_at     timestamptz    not null,
    updated_by     uuid,
    period_start   date           not null,
    subject_type   varchar(10)    not null,
    subject_id     uuid           not null,
    target_value   numeric(19, 4) not null,
    constraint uk_goal_target unique (tenant_id, period_start, subject_type, subject_id),
    constraint ck_goal_target_subject check (subject_type in ('USER', 'TEAM')),
    constraint ck_goal_target_value check (target_value > 0),
    constraint ck_goal_target_period check (extract(day from period_start) = 1)
);
select shared.enable_tenant_rls('incentive.goal_target');

grant select, insert, update, delete on incentive.agreement_attribution, incentive.attribution_participant,
    incentive.attribution_credit, incentive.payment_fact, incentive.payment_reversal_fact,
    incentive.commission_plan, incentive.goal_target to app_runtime;
revoke delete on incentive.agreement_attribution, incentive.payment_fact, incentive.payment_reversal_fact
    from app_runtime;
revoke update, delete on incentive.attribution_participant, incentive.attribution_credit from app_runtime;
