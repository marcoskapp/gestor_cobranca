-- =============================================================================
-- Critério "régua extrajudicial esgotada" (docs/tecnico/desenho/juridico.md §B.4):
-- réguas de campanhas com finalidade EXTRAJUDICIAL_COLLECTION esgotadas por caso de
-- cobrança, alimentadas pelo evento shared.events.campaign.CadenceExhausted (o legal
-- não depende do campaign), e a opção correspondente nas regras de elegibilidade.
-- =============================================================================

alter table legal.eligibility_rule add column require_cadence_exhausted boolean not null default false;
alter table legal.eligibility_rule drop constraint ck_eligibility_rule_criteria;
alter table legal.eligibility_rule add constraint ck_eligibility_rule_criteria
    check (min_days_overdue is not null or min_open_balance is not null or require_collateral
           or require_executive_title or min_broken_agreements is not null or prescription_within_days is not null
           or require_cadence_exhausted);

alter table legal.legal_matter drop constraint ck_legal_matter_origin;
alter table legal.legal_matter add constraint ck_legal_matter_origin
    check (origin in ('RULE', 'PRESCRIPTION_ALERT', 'CADENCE_EXHAUSTED', 'MANUAL'));

-- Uma linha por inscrição esgotada (idempotência além do consumed_event); append-only.
create table legal.cadence_exhaustion (
    id                  uuid          not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    version             bigint        not null,
    created_at          timestamptz   not null,
    created_by          uuid,
    updated_at          timestamptz   not null,
    updated_by          uuid,
    enrollment_id       uuid          not null,
    campaign_id         uuid          not null,
    cadence_version     integer       not null,
    collection_case_id  uuid          not null references collection.collection_case (id),
    creditor_id         uuid          not null references organization.creditor (id),
    portfolio_id        uuid          references organization.portfolio (id),
    debtor_id           uuid          not null references debt.debtor (id),
    steps_executed      integer       not null,
    messages_sent       integer       not null,
    exhausted_at        timestamptz   not null,
    constraint uk_cadence_exhaustion unique (tenant_id, enrollment_id),
    constraint ck_cadence_exhaustion_values check (cadence_version >= 1 and steps_executed >= 0 and messages_sent >= 0)
);
create index ix_cadence_exhaustion_case on legal.cadence_exhaustion (tenant_id, collection_case_id, exhausted_at desc);
select shared.enable_tenant_rls('legal.cadence_exhaustion');

grant select, insert on legal.cadence_exhaustion to app_runtime;
revoke update, delete on legal.cadence_exhaustion from app_runtime;
