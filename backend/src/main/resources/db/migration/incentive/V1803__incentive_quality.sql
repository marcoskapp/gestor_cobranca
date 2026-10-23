-- =============================================================================
-- incentive: monitoria de qualidade (formulários configuráveis, avaliações com
-- nota 0–10) e ocorrências de conduta (violações de compliance, reclamações,
-- contestações procedentes) usadas no portão de qualidade e no zerador.
-- =============================================================================

create table incentive.quality_form (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    name         varchar(120)  not null,
    description  varchar(1000),
    active       boolean       not null
);
select shared.enable_tenant_rls('incentive.quality_form');

-- Critérios de um formulário (imutáveis: um formulário em uso não muda; cria-se outro).
create table incentive.quality_form_criterion (
    form_id      uuid           not null references incentive.quality_form (id),
    position     integer        not null,
    code         varchar(40)    not null,
    description  varchar(300)   not null,
    weight       numeric(9, 4)  not null,
    critical     boolean        not null,
    primary key (form_id, position),
    constraint uk_quality_form_criterion unique (form_id, code),
    constraint ck_quality_criterion_weight check (weight > 0 and weight <= 100)
);
alter table incentive.quality_form_criterion enable row level security;
alter table incentive.quality_form_criterion force row level security;
create policy tenant_isolation on incentive.quality_form_criterion
    using (exists (select 1 from incentive.quality_form f where f.id = form_id))
    with check (exists (select 1 from incentive.quality_form f where f.id = form_id));
create trigger trg_quality_criterion_immutable before update or delete on incentive.quality_form_criterion
    for each row execute function shared.forbid_mutation();

create table incentive.quality_evaluation (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    form_id          uuid           not null references incentive.quality_form (id),
    evaluated_user_id uuid          not null,
    evaluator_id     uuid           not null,
    reference_type   varchar(20)    not null,
    reference_id     uuid,
    evaluated_on     date           not null,
    score            numeric(5, 2)  not null,
    critical_failure boolean        not null,
    comments         varchar(4000),
    constraint ck_quality_evaluation_score check (score between 0 and 10),
    constraint ck_quality_evaluation_reference check (reference_type in ('CALL', 'CONVERSATION', 'CASE', 'AGREEMENT',
                                                                         'OTHER')),
    constraint ck_quality_evaluation_segregation check (evaluator_id <> evaluated_user_id)
);
create index ix_quality_evaluation_user on incentive.quality_evaluation (tenant_id, evaluated_user_id, evaluated_on);
select shared.enable_tenant_rls('incentive.quality_evaluation');
create trigger trg_quality_evaluation_append_only before update or delete on incentive.quality_evaluation
    for each row execute function shared.forbid_mutation();

create table incentive.quality_evaluation_score (
    evaluation_id   uuid           not null references incentive.quality_evaluation (id),
    criterion_code  varchar(40)    not null,
    score           numeric(5, 2)  not null,
    primary key (evaluation_id, criterion_code),
    constraint ck_quality_score check (score between 0 and 10)
);
alter table incentive.quality_evaluation_score enable row level security;
alter table incentive.quality_evaluation_score force row level security;
create policy tenant_isolation on incentive.quality_evaluation_score
    using (exists (select 1 from incentive.quality_evaluation e where e.id = evaluation_id))
    with check (exists (select 1 from incentive.quality_evaluation e where e.id = evaluation_id));
create trigger trg_quality_score_append_only before update or delete on incentive.quality_evaluation_score
    for each row execute function shared.forbid_mutation();

create table incentive.conduct_occurrence (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    user_id         uuid           not null,
    type            varchar(40)    not null,
    occurred_on     date           not null,
    description     varchar(2000)  not null,
    reference_type  varchar(20),
    reference_id    uuid,
    status          varchar(20)    not null,
    registered_by   uuid           not null,
    dismissed_by    uuid,
    dismissed_at    timestamptz,
    dismissal_reason varchar(2000),
    constraint ck_conduct_type check (type in ('SEVERE_COMPLIANCE_VIOLATION', 'COMPLIANCE_VIOLATION', 'COMPLAINT',
                                               'UPHELD_DISPUTE')),
    constraint ck_conduct_status check (status in ('CONFIRMED', 'DISMISSED')),
    constraint ck_conduct_dismissed check ((status = 'DISMISSED') = (dismissed_at is not null and dismissal_reason is not null)),
    constraint ck_conduct_segregation check (registered_by <> user_id)
);
create index ix_conduct_user on incentive.conduct_occurrence (tenant_id, user_id, occurred_on);
select shared.enable_tenant_rls('incentive.conduct_occurrence');

grant select, insert, update, delete on incentive.quality_form, incentive.quality_form_criterion,
    incentive.quality_evaluation, incentive.quality_evaluation_score, incentive.conduct_occurrence to app_runtime;
revoke update, delete on incentive.quality_form_criterion, incentive.quality_evaluation,
    incentive.quality_evaluation_score from app_runtime;
revoke delete on incentive.quality_form, incentive.conduct_occurrence from app_runtime;
