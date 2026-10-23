-- =============================================================================
-- negotiation (parte 2): propostas com aprovação por alçada (F16) e transferência
-- do assistente; acordos (RB05) com cronograma de parcelas, títulos cobertos,
-- distribuição por componente do razão, evidência de aceite e termo; pagamentos
-- aplicados (PaymentConfirmed/PaymentReversed). Ver docs/design/negociacao-e-alcadas.md.
-- =============================================================================

create table negotiation.proposal (
    id                        uuid           not null primary key,
    tenant_id                 uuid           not null references organization.tenant (id),
    version                   bigint         not null,
    created_at                timestamptz    not null,
    created_by                uuid,
    updated_at                timestamptz    not null,
    updated_by                uuid,
    creditor_id               uuid           not null references organization.creditor (id),
    debtor_id                 uuid           not null references debt.debtor (id),
    portfolio_id              uuid           not null references organization.portfolio (id),
    case_id                   uuid           references collection.collection_case (id),
    team_id                   uuid,
    simulation_id             uuid           not null references negotiation.simulation (id),
    policy_id                 uuid           not null references negotiation.collection_policy (id),
    policy_version            integer        not null,
    override_code             varchar(40),
    reference_date            date           not null,
    actor_type                varchar(20)    not null,
    actor_id                  uuid           not null,
    authority_profile_code    varchar(40)    not null,
    concession_level          integer        not null,
    justification_reason      varchar(30),
    justification_note        varchar(500),
    copilot                   boolean        not null,
    channel                   varchar(20),
    discount_rates            jsonb          not null,
    installments              integer        not null,
    requested_down_payment    numeric(19, 2),
    first_due_date            date           not null,
    updated_amount            numeric(19, 2) not null,
    discount_amount           numeric(19, 2) not null,
    negotiated_amount         numeric(19, 2) not null,
    down_payment              numeric(19, 2) not null,
    financing_interest        numeric(19, 2) not null,
    total_amount              numeric(19, 2) not null,
    envelope_usage            numeric(9, 4)  not null,
    max_days_overdue          bigint         not null,
    terms_fingerprint         varchar(64)    not null,
    required_discount_share   numeric(9, 4)  not null,
    down_payment_share        numeric(9, 4)  not null,
    authority_violations      jsonb          not null,
    calculation               jsonb          not null,
    status                    varchar(20)    not null,
    valid_until               date           not null,
    replaced_agreement_id     uuid,
    renegotiation_number      integer        not null,
    handoff_proposal_id       uuid           references negotiation.proposal (id),
    decided_by                uuid,
    decided_by_profile        varchar(40),
    decided_at                timestamptz,
    decision_note_encrypted   varchar(8000),
    acceptance_channel        varchar(20),
    accepted_at               timestamptz,
    accepted_by_type          varchar(20),
    accepted_by_id            uuid,
    acceptance_reference      varchar(120),
    acceptance_ip             varchar(64),
    acceptance_user_agent     varchar(300),
    term_file_id              uuid,
    term_file_key             varchar(500),
    term_sha256               varchar(64),
    term_size                 bigint,
    agreement_id              uuid,
    formalized_at             timestamptz,
    expired_at                timestamptz,
    constraint ck_proposal_status check (status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'ACCEPTED',
                                                    'FORMALIZED', 'EXPIRED', 'HANDED_OFF')),
    constraint ck_proposal_actor check (actor_type in ('USER', 'ASSISTANT')),
    constraint ck_proposal_level check (concession_level >= 0 and (concession_level = 0 or justification_reason is not null)),
    constraint ck_proposal_reason check (justification_reason is null or justification_reason in (
        'PAYMENT_CAPACITY', 'INCOME_LOSS', 'HEALTH_OR_FAMILY', 'COMPETING_OFFER', 'LOW_RECOVERY_PROSPECT',
        'IMMEDIATE_PAYMENT', 'OTHER')),
    constraint ck_proposal_amounts check (installments >= 1 and total_amount > 0 and negotiated_amount > 0
                                          and discount_amount >= 0 and down_payment > 0),
    constraint ck_proposal_renegotiation check (renegotiation_number >= 0
                                                and (renegotiation_number = 0) = (replaced_agreement_id is null)),
    constraint ck_proposal_acceptance check (status not in ('ACCEPTED', 'FORMALIZED')
                                             or (accepted_at is not null and term_sha256 is not null)),
    constraint ck_proposal_acceptance_channel check (acceptance_channel is null or acceptance_channel in (
        'PHONE', 'WHATSAPP', 'EMAIL', 'SMS', 'CHAT', 'PORTAL', 'IN_PERSON')),
    constraint ck_proposal_decision check (status not in ('APPROVED', 'REJECTED') or decided_by is not null)
);
create index ix_proposal_queue on negotiation.proposal (tenant_id, created_at) where status = 'PENDING_APPROVAL';
create index ix_proposal_open_validity on negotiation.proposal (tenant_id, valid_until)
    where status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACCEPTED');
create index ix_proposal_debtor on negotiation.proposal (tenant_id, debtor_id, created_at desc);
create index ix_proposal_creditor on negotiation.proposal (tenant_id, creditor_id, status, created_at desc);
create index ix_proposal_actor on negotiation.proposal (tenant_id, actor_type, actor_id, created_at desc);
select shared.enable_tenant_rls('negotiation.proposal');

create table negotiation.proposal_title (
    proposal_id  uuid     not null references negotiation.proposal (id),
    position     integer  not null,
    title_id     uuid     not null references debt.debt_title (id),
    primary key (proposal_id, position),
    constraint uk_proposal_title unique (proposal_id, title_id)
);
alter table negotiation.proposal_title enable row level security;
alter table negotiation.proposal_title force row level security;
create policy tenant_isolation on negotiation.proposal_title
    using (exists (select 1 from negotiation.proposal p where p.id = proposal_id))
    with check (exists (select 1 from negotiation.proposal p where p.id = proposal_id));

create table negotiation.agreement (
    id                          uuid           not null primary key,
    tenant_id                   uuid           not null references organization.tenant (id),
    version                     bigint         not null,
    created_at                  timestamptz    not null,
    created_by                  uuid,
    updated_at                  timestamptz    not null,
    updated_by                  uuid,
    proposal_id                 uuid           not null references negotiation.proposal (id),
    simulation_id               uuid           not null references negotiation.simulation (id),
    creditor_id                 uuid           not null references organization.creditor (id),
    debtor_id                   uuid           not null references debt.debtor (id),
    portfolio_id                uuid           not null references organization.portfolio (id),
    case_id                     uuid           references collection.collection_case (id),
    team_id                     uuid,
    policy_id                   uuid           not null references negotiation.collection_policy (id),
    policy_version              integer        not null,
    override_code               varchar(40),
    reference_date              date           not null,
    concession_level            integer        not null,
    envelope_usage              numeric(9, 4)  not null,
    max_days_overdue            bigint         not null,
    channel                     varchar(20),
    origin                      varchar(40)    not null,
    originator_type             varchar(20)    not null,
    originator_id               uuid           not null,
    closer_type                 varchar(20)    not null,
    closer_id                   uuid           not null,
    approver_id                 uuid,
    pre_negotiator_id           uuid,
    updated_amount              numeric(19, 2) not null,
    discount_amount             numeric(19, 2) not null,
    negotiated_amount           numeric(19, 2) not null,
    financing_interest          numeric(19, 2) not null,
    total_amount                numeric(19, 2) not null,
    down_payment                numeric(19, 2) not null,
    paid_amount                 numeric(19, 2) not null,
    installment_count           integer        not null,
    break_overdue_installments  integer        not null,
    break_tolerance_days        integer        not null,
    status                      varchar(20)    not null,
    status_reason               varchar(500),
    formalized_at               timestamptz    not null,
    in_payment_at               timestamptz,
    settled_at                  timestamptz,
    ended_at                    timestamptz,
    replaced_agreement_id       uuid           references negotiation.agreement (id),
    -- deferida: o acordo substituído é encerrado (e aponta o novo) antes da inserção do novo
    replaced_by_agreement_id    uuid           references negotiation.agreement (id) deferrable initially deferred,
    renegotiation_number        integer        not null,
    incremental_amount          numeric(19, 2) not null,
    acceptance_channel          varchar(20),
    accepted_at                 timestamptz,
    accepted_by_type            varchar(20),
    accepted_by_id              uuid,
    acceptance_reference        varchar(120),
    acceptance_ip               varchar(64),
    acceptance_user_agent       varchar(300),
    term_file_id                uuid,
    term_file_key               varchar(500),
    term_sha256                 varchar(64),
    term_size                   bigint,
    term_deleted_at             timestamptz,
    constraint uk_agreement_proposal unique (tenant_id, proposal_id),
    constraint ck_agreement_status check (status in ('FORMALIZED', 'IN_PAYMENT', 'SETTLED', 'BROKEN', 'CANCELLED',
                                                     'RENEGOTIATED')),
    constraint ck_agreement_origin check (origin in ('HUMAN', 'HUMAN_COPILOT', 'ASSISTANT_HANDOFF_NEGOTIATED',
                                                     'ASSISTANT_HANDOFF_CONFIRMED', 'ASSISTANT_AUTONOMOUS',
                                                     'PORTAL_SELF_SERVICE', 'CAMPAIGN')),
    constraint ck_agreement_amounts check (total_amount > 0 and paid_amount >= 0 and paid_amount <= total_amount
                                           and installment_count >= 1 and incremental_amount >= 0),
    constraint ck_agreement_break_rule check (break_overdue_installments >= 1 and break_tolerance_days >= 0),
    constraint ck_agreement_acceptance check (accepted_at is not null and term_sha256 is not null),
    constraint ck_agreement_renegotiation check ((renegotiation_number = 0) = (replaced_agreement_id is null))
);
create index ix_agreement_debtor on negotiation.agreement (tenant_id, debtor_id, formalized_at desc);
create index ix_agreement_creditor on negotiation.agreement (tenant_id, creditor_id, status, formalized_at desc);
create index ix_agreement_case on negotiation.agreement (tenant_id, case_id);
create index ix_agreement_active on negotiation.agreement (tenant_id, id) where status in ('FORMALIZED', 'IN_PAYMENT');
select shared.enable_tenant_rls('negotiation.agreement');
alter table negotiation.proposal add constraint fk_proposal_agreement
    foreign key (agreement_id) references negotiation.agreement (id);
alter table negotiation.proposal add constraint fk_proposal_replaced_agreement
    foreign key (replaced_agreement_id) references negotiation.agreement (id);

create table negotiation.agreement_installment (
    id            uuid           not null primary key,
    tenant_id     uuid           not null references organization.tenant (id),
    version       bigint         not null,
    created_at    timestamptz    not null,
    created_by    uuid,
    updated_at    timestamptz    not null,
    updated_by    uuid,
    agreement_id  uuid           not null references negotiation.agreement (id),
    number        integer        not null,
    due_date      date           not null,
    amount        numeric(19, 2) not null,
    paid_amount   numeric(19, 2) not null,
    status        varchar(20)    not null,
    paid_at       timestamptz,
    constraint uk_agreement_installment unique (agreement_id, number),
    constraint ck_agreement_installment_status check (status in ('OPEN', 'PAID', 'PARTIALLY_PAID', 'OVERDUE', 'CANCELLED')),
    constraint ck_agreement_installment_amounts check (amount > 0 and paid_amount >= 0 and paid_amount <= amount)
);
create index ix_agreement_installment_due on negotiation.agreement_installment (tenant_id, due_date)
    where status in ('OPEN', 'PARTIALLY_PAID', 'OVERDUE');
select shared.enable_tenant_rls('negotiation.agreement_installment');

create table negotiation.agreement_title (
    id            uuid         not null primary key,
    tenant_id     uuid         not null references organization.tenant (id),
    version       bigint       not null,
    created_at    timestamptz  not null,
    created_by    uuid,
    updated_at    timestamptz  not null,
    updated_by    uuid,
    agreement_id  uuid         not null references negotiation.agreement (id),
    title_id      uuid         not null references debt.debt_title (id),
    active        boolean      not null,
    constraint uk_agreement_title unique (agreement_id, title_id)
);
-- um título nunca está em dois acordos em vigor (última defesa contra formalizações concorrentes)
create unique index uk_agreement_title_active on negotiation.agreement_title (tenant_id, title_id) where active;
select shared.enable_tenant_rls('negotiation.agreement_title');

create table negotiation.agreement_bucket (
    id            uuid           not null primary key,
    tenant_id     uuid           not null references organization.tenant (id),
    version       bigint         not null,
    created_at    timestamptz    not null,
    created_by    uuid,
    updated_at    timestamptz    not null,
    updated_by    uuid,
    agreement_id  uuid           not null references negotiation.agreement (id),
    title_id      uuid           not null references debt.debt_title (id),
    part          varchar(20)    not null,
    gross         numeric(19, 2) not null,
    discount      numeric(19, 2) not null,
    payable       numeric(19, 2) not null,
    recognized    numeric(19, 2) not null,
    paid          numeric(19, 2) not null,
    constraint uk_agreement_bucket unique (agreement_id, title_id, part),
    constraint ck_agreement_bucket_part check (part in ('PRINCIPAL', 'INTEREST', 'FINE', 'FEES', 'HONORARIUM')),
    constraint ck_agreement_bucket_amounts check (gross >= 0 and discount >= 0 and payable = gross - discount
                                                  and paid >= 0 and paid <= payable and recognized >= 0)
);
select shared.enable_tenant_rls('negotiation.agreement_bucket');

create table negotiation.agreement_payment (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by               uuid,
    updated_at               timestamptz    not null,
    updated_by               uuid,
    agreement_id             uuid           not null references negotiation.agreement (id),
    payment_id               uuid           not null,
    amount                   numeric(19, 2) not null,
    applied_amount           numeric(19, 2) not null,
    unapplied_amount         numeric(19, 2) not null,
    paid_at                  timestamptz    not null,
    source                   varchar(20),
    method                   varchar(20),
    installment_allocations  jsonb          not null,
    ledger_postings          jsonb          not null,
    status                   varchar(20)    not null,
    ledger_status            varchar(20)    not null,
    ledger_posted_at         timestamptz,
    reversal_id              uuid,
    reversed_at              timestamptz,
    reversal_reason          varchar(500),
    constraint uk_agreement_payment unique (tenant_id, agreement_id, payment_id),
    constraint ck_agreement_payment_status check (status in ('APPLIED', 'UNAPPLIED', 'REVERSED')),
    constraint ck_agreement_payment_ledger check (ledger_status in ('PENDING', 'POSTED', 'REVERSED', 'SKIPPED')),
    constraint ck_agreement_payment_amounts check (applied_amount >= 0 and unapplied_amount >= 0)
);
create index ix_agreement_payment_payment on negotiation.agreement_payment (tenant_id, payment_id);
create index ix_agreement_payment_pending on negotiation.agreement_payment (tenant_id, agreement_id)
    where ledger_status = 'PENDING';
select shared.enable_tenant_rls('negotiation.agreement_payment');

-- valores e cronograma são imutáveis após a criação (o histórico financeiro não se reescreve)
create or replace function negotiation.agreement_installment_immutable() returns trigger
    language plpgsql as
$$
begin
    if new.agreement_id <> old.agreement_id or new.number <> old.number or new.due_date <> old.due_date
       or new.amount <> old.amount then
        raise exception 'Cronograma de acordo é imutável (parcela %)', old.id using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_agreement_installment_immutable before update on negotiation.agreement_installment
    for each row execute function negotiation.agreement_installment_immutable();

grant select, insert, update on negotiation.proposal, negotiation.agreement, negotiation.agreement_installment,
    negotiation.agreement_title, negotiation.agreement_bucket, negotiation.agreement_payment to app_runtime;
grant select, insert on negotiation.proposal_title to app_runtime;
-- propostas, acordos e pagamentos não são apagados (RB05: histórico preservado)
revoke delete on negotiation.proposal, negotiation.proposal_title, negotiation.agreement,
    negotiation.agreement_installment, negotiation.agreement_title, negotiation.agreement_bucket,
    negotiation.agreement_payment from app_runtime;
