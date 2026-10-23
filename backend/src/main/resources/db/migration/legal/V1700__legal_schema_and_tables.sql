-- =============================================================================
-- Módulo legal (workflow jurídico) — docs/design/juridico.md, Parte B.
--
-- Casos jurídicos (indicação, fases, viabilidade, autorização do credor), medidas
-- extrajudiciais, processos com número CNJ, andamentos, prazos em dias úteis,
-- suspensões forenses, audiências, custas e despesas, escritórios parceiros,
-- documentos/evidências e acordos do negotiation referenciados por id.
--
-- Textos livres que podem citar pessoas (parecer, pesquisa patrimonial, teor dos
-- andamentos, participantes e resultado de audiência) ficam criptografados
-- (*_encrypted). Históricos, andamentos, análises e documentos são append-only;
-- despesas nunca são apagadas e só a situação muda. Usuários (identity) são
-- referenciados por id, sem chave estrangeira, como nos demais módulos.
-- =============================================================================

create schema if not exists legal;
grant usage on schema legal to app_runtime;
alter default privileges in schema legal grant select, insert, update, delete on tables to app_runtime;
alter default privileges in schema legal grant usage, select on sequences to app_runtime;

-- -----------------------------------------------------------------------------
-- Parâmetros do jurídico por assessoria (ausente = padrões de app.legal.defaults).
-- Inclui as decisões pendentes da issue #14 (quem conduz, quem adianta custas,
-- dívidas prescritas) com padrões conservadores.
-- -----------------------------------------------------------------------------
create table legal.legal_settings (
    id                                   uuid         not null primary key,
    tenant_id                            uuid         not null references organization.tenant (id),
    version                              bigint       not null,
    created_at                           timestamptz  not null,
    created_by                           uuid,
    updated_at                           timestamptz  not null,
    updated_by                           uuid,
    conduct_model                        varchar(20)  not null,
    allowed_expense_advancers            varchar(60)  not null,
    budget_enforcement                   varchar(10)  not null,
    prescribed_title_policy              varchar(10)  not null,
    collection_hold_stage                varchar(20)  not null,
    deadline_alert_levels                varchar(40)  not null,
    year_end_recess                      boolean      not null,
    removal_deadline_business_days       integer      not null,
    homologation_deadline_business_days  integer      not null,
    credit_bureau_notice_days            integer      not null,
    prescription_warning_days            integer      not null,
    referral_cooldown_days               integer      not null,
    constraint uk_legal_settings_tenant unique (tenant_id),
    constraint ck_legal_settings_conduct check (conduct_model in ('INTERNAL_ONLY', 'LAW_FIRMS_ONLY', 'BOTH')),
    constraint ck_legal_settings_budget check (budget_enforcement in ('BLOCK', 'WARN')),
    constraint ck_legal_settings_prescribed check (prescribed_title_policy in ('EXCLUDE', 'FLAG')),
    constraint ck_legal_settings_hold check (collection_hold_stage in ('REVIEW', 'AUTHORIZATION')),
    constraint ck_legal_settings_advancers check (allowed_expense_advancers ~ '^(CREDITOR|AGENCY|LAW_FIRM)(,(CREDITOR|AGENCY|LAW_FIRM))*$'),
    constraint ck_legal_settings_levels check (deadline_alert_levels ~ '^[0-9]{1,2}(,[0-9]{1,2}){0,4}$'),
    constraint ck_legal_settings_values check (removal_deadline_business_days between 1 and 30
                                               and homologation_deadline_business_days between 1 and 60
                                               and credit_bureau_notice_days between 1 and 60
                                               and prescription_warning_days between 1 and 1825
                                               and referral_cooldown_days between 0 and 1825)
);
select shared.enable_tenant_rls('legal.legal_settings');

-- -----------------------------------------------------------------------------
-- Regras de indicação ao jurídico (critérios combinados por E; regras por OU)
-- -----------------------------------------------------------------------------
create table legal.eligibility_rule (
    id                        uuid           not null primary key,
    tenant_id                 uuid           not null references organization.tenant (id),
    version                   bigint         not null,
    created_at                timestamptz    not null,
    created_by                uuid,
    updated_at                timestamptz    not null,
    updated_by                uuid,
    creditor_id               uuid           not null references organization.creditor (id),
    portfolio_id              uuid           references organization.portfolio (id),
    name                      varchar(120)   not null,
    active                    boolean        not null,
    min_days_overdue          integer,
    min_open_balance          numeric(19, 2),
    require_collateral        boolean        not null,
    require_executive_title   boolean        not null,
    min_broken_agreements     integer,
    prescription_within_days  integer,
    constraint ck_eligibility_rule_values check ((min_days_overdue is null or min_days_overdue >= 0)
                                                 and (min_open_balance is null or min_open_balance >= 0)
                                                 and (min_broken_agreements is null or min_broken_agreements >= 1)
                                                 and (prescription_within_days is null or prescription_within_days >= 1)),
    constraint ck_eligibility_rule_criteria check (min_days_overdue is not null or min_open_balance is not null
                                                   or require_collateral or require_executive_title
                                                   or min_broken_agreements is not null
                                                   or prescription_within_days is not null)
);
create index ix_eligibility_rule_creditor on legal.eligibility_rule (tenant_id, creditor_id, active);
select shared.enable_tenant_rls('legal.eligibility_rule');

-- -----------------------------------------------------------------------------
-- Escritórios parceiros e seus membros (usuários da assessoria)
-- -----------------------------------------------------------------------------
create table legal.law_firm (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    name              varchar(150)  not null,
    cnpj              varchar(14)   not null,
    oab_registration  varchar(40),
    contact_email     varchar(150),
    active            boolean       not null,
    constraint uk_law_firm_cnpj unique (tenant_id, cnpj),
    constraint ck_law_firm_cnpj check (cnpj ~ '^[0-9]{14}$')
);
select shared.enable_tenant_rls('legal.law_firm');

create table legal.law_firm_member (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    law_firm_id  uuid          not null references legal.law_firm (id),
    user_id      uuid          not null,
    active       boolean       not null,
    joined_at    timestamptz   not null,
    left_at      timestamptz,
    constraint ck_law_firm_member_left check (active = (left_at is null))
);
-- um usuário pertence a no máximo um escritório por vez
create unique index uk_law_firm_member_active on legal.law_firm_member (tenant_id, user_id) where active;
create index ix_law_firm_member_firm on legal.law_firm_member (tenant_id, law_firm_id);
select shared.enable_tenant_rls('legal.law_firm_member');

-- -----------------------------------------------------------------------------
-- Caso jurídico
-- -----------------------------------------------------------------------------
create table legal.legal_matter (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    collection_case_id     uuid           not null references collection.collection_case (id),
    debtor_id              uuid           not null references debt.debtor (id),
    creditor_id            uuid           not null references organization.creditor (id),
    portfolio_id           uuid           not null references organization.portfolio (id),
    origin                 varchar(20)    not null,
    eligibility_rule_id    uuid           references legal.eligibility_rule (id),
    referral_note          varchar(500),
    stage                  varchar(30)    not null,
    resume_stage           varchar(30),
    stage_reason           varchar(500),
    stage_changed_at       timestamptz    not null,
    primary_route          varchar(40),
    responsible_type       varchar(20)    not null,
    responsible_user_id    uuid,
    law_firm_id            uuid           references legal.law_firm (id),
    debt_amount            numeric(19, 2) not null,
    expected_costs         numeric(19, 2),
    estimated_recoverable  numeric(19, 2),
    authorization_id       uuid,
    authorized_budget      numeric(19, 2),
    expenses_total         numeric(19, 2) not null,
    recovered_amount       numeric(19, 2) not null,
    agreement_id           uuid,
    collection_hold        boolean        not null,
    referred_at            timestamptz    not null,
    closed_at              timestamptz,
    closure_reason         varchar(30),
    constraint ck_legal_matter_origin check (origin in ('RULE', 'PRESCRIPTION_ALERT', 'MANUAL')),
    constraint ck_legal_matter_stage check (stage in ('ELIGIBLE', 'UNDER_REVIEW', 'AWAITING_AUTHORIZATION', 'EXTRAJUDICIAL',
        'FILED', 'IN_PROGRESS', 'ENFORCEMENT', 'SETTLED_IN_COURT', 'CLOSED', 'NOT_VIABLE', 'RETURNED_TO_CREDITOR')),
    constraint ck_legal_matter_resume_stage check (resume_stage is null or resume_stage in ('FILED', 'IN_PROGRESS', 'ENFORCEMENT')),
    constraint ck_legal_matter_route check (primary_route is null or primary_route in ('PROTEST', 'CREDIT_BUREAU_LISTING',
        'EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT', 'EXECUTION', 'MONITORY_ACTION', 'COLLECTION_ACTION', 'SEARCH_AND_SEIZURE')),
    constraint ck_legal_matter_responsible check (
        (responsible_type = 'NONE' and responsible_user_id is null and law_firm_id is null)
        or (responsible_type = 'INTERNAL' and responsible_user_id is not null and law_firm_id is null)
        or (responsible_type = 'LAW_FIRM' and law_firm_id is not null)),
    constraint ck_legal_matter_closure check ((stage <> 'CLOSED' or closure_reason is not null)
        and (closure_reason is null or closure_reason in ('PAID', 'AGREEMENT_FULFILLED', 'ASSET_RECOVERED', 'UNCOLLECTIBLE',
            'PRESCRIBED', 'WITHDRAWN', 'CREDITOR_REQUEST', 'OTHER'))),
    constraint ck_legal_matter_amounts check (debt_amount > 0 and expenses_total >= 0 and recovered_amount >= 0
        and (expected_costs is null or expected_costs >= 0) and (authorized_budget is null or authorized_budget >= 0))
);
-- no máximo um caso jurídico não final por caso de cobrança
create unique index uk_legal_matter_open_case on legal.legal_matter (tenant_id, collection_case_id)
    where stage not in ('CLOSED', 'RETURNED_TO_CREDITOR');
create index ix_legal_matter_stage on legal.legal_matter (tenant_id, stage, creditor_id);
create index ix_legal_matter_firm on legal.legal_matter (tenant_id, law_firm_id) where law_firm_id is not null;
create index ix_legal_matter_debtor on legal.legal_matter (tenant_id, debtor_id);
create index ix_legal_matter_case on legal.legal_matter (tenant_id, collection_case_id, closed_at);
select shared.enable_tenant_rls('legal.legal_matter');

-- títulos e sinalizações do caso jurídico (RLS pela linha pai)
create table legal.legal_matter_title (
    matter_id  uuid  not null references legal.legal_matter (id),
    title_id   uuid  not null references debt.debt_title (id),
    primary key (matter_id, title_id)
);
create index ix_legal_matter_title_title on legal.legal_matter_title (title_id);
alter table legal.legal_matter_title enable row level security;
alter table legal.legal_matter_title force row level security;
create policy tenant_isolation on legal.legal_matter_title
    using (exists (select 1 from legal.legal_matter m where m.id = matter_id))
    with check (exists (select 1 from legal.legal_matter m where m.id = matter_id));

create table legal.legal_matter_flag (
    matter_id  uuid         not null references legal.legal_matter (id),
    flag       varchar(40)  not null,
    primary key (matter_id, flag),
    constraint ck_legal_matter_flag check (flag in ('PRESCRIBED_TITLES_EXCLUDED', 'PRESCRIBED_TITLES_INCLUDED',
        'EXECUTION_PRESCRIBED', 'PRESCRIPTION_NEAR', 'PRESCRIPTION_RULE_UNVALIDATED', 'DISPUTED_TITLES_EXCLUDED',
        'NEGATIVE_COST_BENEFIT', 'TITLES_SETTLED'))
);
alter table legal.legal_matter_flag enable row level security;
alter table legal.legal_matter_flag force row level security;
create policy tenant_isolation on legal.legal_matter_flag
    using (exists (select 1 from legal.legal_matter m where m.id = matter_id))
    with check (exists (select 1 from legal.legal_matter m where m.id = matter_id));

-- linha do tempo das fases (append-only)
create table legal.matter_stage_history (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    matter_id       uuid          not null references legal.legal_matter (id),
    from_stage      varchar(30),
    to_stage        varchar(30)   not null,
    reason          varchar(500),
    closure_reason  varchar(30),
    cause           varchar(40)   not null,
    occurred_at     timestamptz   not null
);
create index ix_matter_stage_history_matter on legal.matter_stage_history (tenant_id, matter_id, occurred_at);
select shared.enable_tenant_rls('legal.matter_stage_history');
create trigger trg_matter_stage_history_append_only before update or delete on legal.matter_stage_history
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Documentos e evidências (FileStorage do tenant; append-only)
-- -----------------------------------------------------------------------------
create table legal.legal_document (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    matter_id     uuid          not null references legal.legal_matter (id),
    creditor_id   uuid          not null references organization.creditor (id),
    kind          varchar(30)   not null,
    link_type     varchar(20)   not null,
    link_id       uuid,
    description   varchar(300),
    storage_key   varchar(500)  not null,
    file_name     varchar(255)  not null,
    content_type  varchar(100)  not null,
    size_bytes    bigint        not null,
    sha256        varchar(64)   not null,
    constraint ck_legal_document_kind check (kind in ('CONTRACT', 'DEBT_STATEMENT', 'NOTICE', 'AUTHORIZATION_EVIDENCE',
        'POWER_OF_ATTORNEY', 'PETITION', 'COURT_DECISION', 'PROTEST_INSTRUMENT', 'CREDIT_BUREAU_RECEIPT',
        'REGISTRY_CERTIFICATE', 'RECEIPT', 'HEARING_RECORD', 'OTHER')),
    constraint ck_legal_document_link check (link_type in ('MATTER', 'AUTHORIZATION', 'MEASURE', 'LAWSUIT', 'MOVEMENT',
        'HEARING', 'EXPENSE') and ((link_type = 'MATTER') = (link_id is null))),
    constraint ck_legal_document_file check (size_bytes > 0 and sha256 ~ '^[0-9a-f]{64}$')
);
create index ix_legal_document_matter on legal.legal_document (tenant_id, matter_id, created_at);
select shared.enable_tenant_rls('legal.legal_document');
create trigger trg_legal_document_append_only before update or delete on legal.legal_document
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Análise de viabilidade (append-only; a mais recente é a vigente)
-- -----------------------------------------------------------------------------
create table legal.viability_assessment (
    id                      uuid            not null primary key,
    tenant_id               uuid            not null references organization.tenant (id),
    version                 bigint          not null,
    created_at              timestamptz     not null,
    created_by              uuid,
    updated_at              timestamptz     not null,
    updated_by              uuid,
    matter_id               uuid            not null references legal.legal_matter (id),
    creditor_id             uuid            not null references organization.creditor (id),
    debt_amount             numeric(19, 2)  not null,
    collectible_debt        numeric(19, 2)  not null,
    collateral_value        numeric(19, 2)  not null,
    collateral_recoverable  numeric(19, 2)  not null,
    other_assets_value      numeric(19, 2)  not null,
    realization_rate        numeric(9, 4)   not null,
    success_probability     numeric(9, 4)   not null,
    expected_costs          numeric(19, 2)  not null,
    estimated_recoverable   numeric(19, 2)  not null,
    net_benefit             numeric(19, 2)  not null,
    cost_benefit_ratio      numeric(19, 2),
    economically_viable     boolean         not null,
    viable                  boolean         not null,
    recommended_routes      varchar(300),
    available_routes        varchar(300),
    suggested_routes        varchar(300),
    flags                   varchar(500),
    collateral_snapshot     jsonb           not null,
    prescription_snapshot   jsonb           not null,
    asset_search_encrypted  varchar(24000),
    opinion_encrypted       varchar(32000),
    constraint ck_viability_rates check (realization_rate between 0 and 100 and success_probability between 0 and 100),
    constraint ck_viability_conclusion check (viable = (recommended_routes is not null))
);
create index ix_viability_assessment_matter on legal.viability_assessment (tenant_id, matter_id, created_at desc);
select shared.enable_tenant_rls('legal.viability_assessment');
create trigger trg_viability_assessment_append_only before update or delete on legal.viability_assessment
    for each row execute function shared.forbid_mutation();

create table legal.viability_checklist_item (
    assessment_id  uuid          not null references legal.viability_assessment (id),
    position       integer       not null,
    code           varchar(40)   not null,
    label          varchar(150)  not null,
    status         varchar(20)   not null,
    required       boolean       not null,
    primary key (assessment_id, position),
    constraint ck_viability_checklist_status check (status in ('PRESENT', 'MISSING', 'NOT_APPLICABLE'))
);
alter table legal.viability_checklist_item enable row level security;
alter table legal.viability_checklist_item force row level security;
create policy tenant_isolation on legal.viability_checklist_item
    using (exists (select 1 from legal.viability_assessment a where a.id = assessment_id))
    with check (exists (select 1 from legal.viability_assessment a where a.id = assessment_id));
create trigger trg_viability_checklist_append_only before update or delete on legal.viability_checklist_item
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Autorização do credor (uma solicitação pendente e uma concessão vigente por vez)
-- -----------------------------------------------------------------------------
create table legal.creditor_authorization (
    id                       uuid            not null primary key,
    tenant_id                uuid            not null references organization.tenant (id),
    version                  bigint          not null,
    created_at               timestamptz     not null,
    created_by               uuid,
    updated_at               timestamptz     not null,
    updated_by               uuid,
    matter_id                uuid            not null references legal.legal_matter (id),
    creditor_id              uuid            not null references organization.creditor (id),
    status                   varchar(20)     not null,
    requested_routes         varchar(300)    not null,
    requested_budget         numeric(19, 2)  not null,
    request_note             varchar(500),
    authorized_routes        varchar(300),
    approved_budget          numeric(19, 2),
    decision_date            date,
    channel                  varchar(20),
    creditor_representative  varchar(150),
    evidence_document_id     uuid            references legal.legal_document (id),
    decision_note            varchar(500),
    decided_at               timestamptz,
    decided_by               uuid,
    constraint ck_creditor_authorization_status check (status in ('REQUESTED', 'GRANTED', 'DENIED', 'SUPERSEDED')),
    constraint ck_creditor_authorization_channel check (channel is null
        or channel in ('EMAIL', 'SIGNED_DOCUMENT', 'CREDITOR_PORTAL', 'LETTER', 'OTHER')),
    constraint ck_creditor_authorization_decision check (status = 'REQUESTED' or (decision_date is not null
        and channel is not null and creditor_representative is not null and decided_at is not null)),
    constraint ck_creditor_authorization_grant check (status not in ('GRANTED', 'SUPERSEDED')
        or (authorized_routes is not null and approved_budget is not null and evidence_document_id is not null))
);
create unique index uk_creditor_authorization_pending on legal.creditor_authorization (tenant_id, matter_id)
    where status = 'REQUESTED';
create unique index uk_creditor_authorization_granted on legal.creditor_authorization (tenant_id, matter_id)
    where status = 'GRANTED';
select shared.enable_tenant_rls('legal.creditor_authorization');
alter table legal.legal_matter add constraint fk_legal_matter_authorization
    foreign key (authorization_id) references legal.creditor_authorization (id);

-- -----------------------------------------------------------------------------
-- Medidas extrajudiciais
-- -----------------------------------------------------------------------------
create table legal.extrajudicial_measure (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    matter_id           uuid            not null references legal.legal_matter (id),
    creditor_id         uuid            not null references organization.creditor (id),
    type                varchar(30)     not null,
    collateral_id       uuid            references debt.collateral (id),
    amount              numeric(19, 2)  not null,
    authority           varchar(150),
    protocol            varchar(80),
    prior_notice_on     date,
    submitted_on        date,
    effective_on        date,
    resolved_on         date,
    due_date            date,
    status              varchar(20)     not null,
    status_reason       varchar(500),
    provider            varchar(40)     not null,
    provider_reference  varchar(120),
    constraint ck_extrajudicial_measure_type check (type in ('NOTICE', 'PROTEST', 'CREDIT_BUREAU_LISTING',
        'COLLATERAL_ENFORCEMENT')),
    constraint ck_extrajudicial_measure_status check (status in ('PLANNED', 'SUBMITTED', 'EFFECTIVE', 'FAILED',
        'CANCELLED', 'REMOVED')),
    constraint ck_extrajudicial_measure_collateral check ((type = 'COLLATERAL_ENFORCEMENT') = (collateral_id is not null)),
    constraint ck_extrajudicial_measure_dates check ((status in ('PLANNED', 'CANCELLED') or submitted_on is not null)
        and (status not in ('EFFECTIVE', 'REMOVED') or effective_on is not null) and amount > 0)
);
create index ix_extrajudicial_measure_matter on legal.extrajudicial_measure (tenant_id, matter_id, status);
select shared.enable_tenant_rls('legal.extrajudicial_measure');

create table legal.extrajudicial_measure_title (
    measure_id  uuid  not null references legal.extrajudicial_measure (id),
    title_id    uuid  not null references debt.debt_title (id),
    primary key (measure_id, title_id)
);
alter table legal.extrajudicial_measure_title enable row level security;
alter table legal.extrajudicial_measure_title force row level security;
create policy tenant_isolation on legal.extrajudicial_measure_title
    using (exists (select 1 from legal.extrajudicial_measure e where e.id = measure_id))
    with check (exists (select 1 from legal.extrajudicial_measure e where e.id = measure_id));

-- -----------------------------------------------------------------------------
-- Processos, partes e andamentos
-- -----------------------------------------------------------------------------
create table legal.lawsuit (
    id              uuid            not null primary key,
    tenant_id       uuid            not null references organization.tenant (id),
    version         bigint          not null,
    created_at      timestamptz     not null,
    created_by      uuid,
    updated_at      timestamptz     not null,
    updated_by      uuid,
    matter_id       uuid            not null references legal.legal_matter (id),
    creditor_id     uuid            not null references organization.creditor (id),
    cnj_number      varchar(20)     not null,
    court_code      varchar(5)      not null,
    court_name      varchar(150),
    court_unit      varchar(200),
    route           varchar(40)     not null,
    action_class    varchar(150),
    creditor_pole   varchar(10)     not null,
    claim_amount    numeric(19, 2)  not null,
    filed_on        date            not null,
    status          varchar(20)     not null,
    status_reason   varchar(500),
    provider        varchar(40)     not null,
    last_synced_at  timestamptz,
    constraint uk_lawsuit_cnj unique (tenant_id, cnj_number),
    constraint ck_lawsuit_cnj check (cnj_number ~ '^[0-9]{20}$' and court_code ~ '^[1-9]\.[0-9]{2}$'),
    constraint ck_lawsuit_route check (route in ('EXECUTION', 'MONITORY_ACTION', 'COLLECTION_ACTION', 'SEARCH_AND_SEIZURE')),
    constraint ck_lawsuit_pole check (creditor_pole in ('ACTIVE', 'PASSIVE')),
    constraint ck_lawsuit_status check (status in ('ACTIVE', 'SUSPENDED', 'ARCHIVED', 'TERMINATED')),
    constraint ck_lawsuit_amount check (claim_amount > 0)
);
create index ix_lawsuit_matter on legal.lawsuit (tenant_id, matter_id);
create index ix_lawsuit_status on legal.lawsuit (tenant_id, status, id);
select shared.enable_tenant_rls('legal.lawsuit');

create table legal.lawsuit_party (
    lawsuit_id  uuid  not null references legal.lawsuit (id),
    debtor_id   uuid  not null references debt.debtor (id),
    primary key (lawsuit_id, debtor_id)
);
alter table legal.lawsuit_party enable row level security;
alter table legal.lawsuit_party force row level security;
create policy tenant_isolation on legal.lawsuit_party
    using (exists (select 1 from legal.lawsuit l where l.id = lawsuit_id))
    with check (exists (select 1 from legal.lawsuit l where l.id = lawsuit_id));

create table legal.case_movement (
    id                     uuid            not null primary key,
    tenant_id              uuid            not null references organization.tenant (id),
    version                bigint          not null,
    created_at             timestamptz     not null,
    created_by             uuid,
    updated_at             timestamptz     not null,
    updated_by             uuid,
    lawsuit_id             uuid            not null references legal.lawsuit (id),
    matter_id              uuid            not null references legal.legal_matter (id),
    creditor_id            uuid            not null references organization.creditor (id),
    occurred_on            date            not null,
    type                   varchar(30)     not null,
    description_encrypted  varchar(24000),
    source                 varchar(10)     not null,
    provider               varchar(40),
    external_id            varchar(120),
    amount                 numeric(19, 2),
    collateral_id          uuid            references debt.collateral (id),
    constraint ck_case_movement_type check (type in ('DISTRIBUTION', 'CITATION_ORDERED', 'CITATION', 'DEFENSE_FILED',
        'DECISION', 'SENTENCE', 'APPEAL', 'JUDGMENT_ENFORCEMENT', 'SEIZURE', 'APPRAISAL', 'AUCTION', 'ADJUDICATION',
        'FUNDS_RELEASED', 'SETTLEMENT_HOMOLOGATED', 'SUSPENSION', 'ARCHIVING', 'PUBLICATION', 'OTHER')),
    constraint ck_case_movement_source check (source in ('MANUAL', 'PROVIDER')
        and (source = 'MANUAL' or (provider is not null and external_id is not null))),
    constraint ck_case_movement_amount check (amount is null or amount > 0)
);
-- captura idempotente por provedor
create unique index uk_case_movement_external on legal.case_movement (tenant_id, lawsuit_id, provider, external_id)
    where external_id is not null;
create index ix_case_movement_lawsuit on legal.case_movement (tenant_id, lawsuit_id, occurred_on desc);
select shared.enable_tenant_rls('legal.case_movement');
create trigger trg_case_movement_append_only before update or delete on legal.case_movement
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Prazos e suspensões forenses
-- -----------------------------------------------------------------------------
create table legal.court_suspension (
    id          uuid          not null primary key,
    tenant_id   uuid          not null references organization.tenant (id),
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid,
    updated_at  timestamptz   not null,
    updated_by  uuid,
    start_date  date          not null,
    end_date    date          not null,
    court_code  varchar(5),
    reason      varchar(300)  not null,
    constraint ck_court_suspension_range check (end_date >= start_date and end_date - start_date <= 366),
    constraint ck_court_suspension_court check (court_code is null or court_code ~ '^[1-9]\.[0-9]{2}$')
);
create index ix_court_suspension_range on legal.court_suspension (tenant_id, start_date, end_date);
select shared.enable_tenant_rls('legal.court_suspension');

create table legal.legal_deadline (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    matter_id            uuid          not null references legal.legal_matter (id),
    creditor_id          uuid          not null references organization.creditor (id),
    lawsuit_id           uuid          references legal.lawsuit (id),
    movement_id          uuid          references legal.case_movement (id),
    measure_id           uuid          references legal.extrajudicial_measure (id),
    kind                 varchar(30)   not null,
    description          varchar(300)  not null,
    court_code           varchar(5),
    start_date           date,
    start_event          varchar(20),
    term_days            integer,
    counting_mode        varchar(20)   not null,
    fixed_date           date,
    due_date             date          not null,
    responsible_user_id  uuid,
    status               varchar(20)   not null,
    completed_on         date,
    status_reason        varchar(500),
    last_alert_level     integer,
    last_alert_at        timestamptz,
    constraint ck_legal_deadline_kind check (kind in ('RESPONSE', 'APPEAL', 'PAYMENT', 'FILING', 'HEARING_PREPARATION',
        'EXTRAJUDICIAL', 'RESTRICTION_REMOVAL', 'OTHER')),
    constraint ck_legal_deadline_mode check (
        (counting_mode in ('BUSINESS_DAYS', 'CALENDAR_DAYS') and start_date is not null and term_days between 1 and 365
            and start_event in ('INTIMATION', 'DJE_AVAILABILITY') and fixed_date is null)
        or (counting_mode = 'FIXED_DATE' and fixed_date is not null and start_date is null and term_days is null)),
    constraint ck_legal_deadline_status check (status in ('PENDING', 'MET', 'MISSED', 'CANCELLED')
        and ((status = 'MET') = (completed_on is not null)))
);
create index ix_legal_deadline_due on legal.legal_deadline (tenant_id, status, due_date);
create index ix_legal_deadline_matter on legal.legal_deadline (tenant_id, matter_id, due_date);
select shared.enable_tenant_rls('legal.legal_deadline');

-- -----------------------------------------------------------------------------
-- Audiências
-- -----------------------------------------------------------------------------
create table legal.hearing (
    id                               uuid           not null primary key,
    tenant_id                        uuid           not null references organization.tenant (id),
    version                          bigint         not null,
    created_at                       timestamptz    not null,
    created_by                       uuid,
    updated_at                       timestamptz    not null,
    updated_by                       uuid,
    lawsuit_id                       uuid           not null references legal.lawsuit (id),
    matter_id                        uuid           not null references legal.legal_matter (id),
    creditor_id                      uuid           not null references organization.creditor (id),
    type                             varchar(30)    not null,
    scheduled_at                     timestamptz    not null,
    mode                             varchar(20)    not null,
    location                         varchar(300),
    link                             varchar(500),
    external_participants_encrypted  varchar(8000),
    status                           varchar(20)    not null,
    outcome                          varchar(20),
    result_notes_encrypted           varchar(24000),
    reschedule_count                 integer        not null,
    constraint ck_hearing_type check (type in ('CONCILIATION', 'MEDIATION', 'INSTRUCTION_AND_JUDGMENT', 'OTHER')),
    constraint ck_hearing_mode check (mode in ('IN_PERSON', 'VIDEO', 'HYBRID')),
    constraint ck_hearing_status check (status in ('SCHEDULED', 'HELD', 'CANCELLED')
        and ((status = 'HELD') = (outcome is not null))),
    constraint ck_hearing_outcome check (outcome is null
        or outcome in ('AGREEMENT', 'NO_AGREEMENT', 'POSTPONED', 'DEBTOR_ABSENT', 'OTHER')),
    constraint ck_hearing_link check (link is null or link ~ '^https://')
);
create index ix_hearing_lawsuit on legal.hearing (tenant_id, lawsuit_id, scheduled_at);
create index ix_hearing_scheduled on legal.hearing (tenant_id, status, scheduled_at);
select shared.enable_tenant_rls('legal.hearing');

create table legal.hearing_participant (
    hearing_id  uuid  not null references legal.hearing (id),
    user_id     uuid  not null,
    primary key (hearing_id, user_id)
);
alter table legal.hearing_participant enable row level security;
alter table legal.hearing_participant force row level security;
create policy tenant_isolation on legal.hearing_participant
    using (exists (select 1 from legal.hearing h where h.id = hearing_id))
    with check (exists (select 1 from legal.hearing h where h.id = hearing_id));

-- -----------------------------------------------------------------------------
-- Custas e despesas (nunca apagadas; colunas financeiras imutáveis)
-- -----------------------------------------------------------------------------
create table legal.legal_expense (
    id                   uuid            not null primary key,
    tenant_id            uuid            not null references organization.tenant (id),
    version              bigint          not null,
    created_at           timestamptz     not null,
    created_by           uuid,
    updated_at           timestamptz     not null,
    updated_by           uuid,
    matter_id            uuid            not null references legal.legal_matter (id),
    creditor_id          uuid            not null references organization.creditor (id),
    lawsuit_id           uuid            references legal.lawsuit (id),
    type                 varchar(30)     not null,
    description          varchar(300),
    amount               numeric(19, 2)  not null,
    incurred_on          date            not null,
    advanced_by          varchar(20)     not null,
    law_firm_id          uuid            references legal.law_firm (id),
    reimbursable         boolean         not null,
    receipt_document_id  uuid            references legal.legal_document (id),
    over_budget          boolean         not null,
    status               varchar(20)     not null,
    status_date          date,
    status_reason        varchar(500),
    constraint ck_legal_expense_type check (type in ('COURT_FEES', 'PROCEDURAL_COSTS', 'BAILIFF', 'REGISTRY_FEES',
        'CREDIT_BUREAU_FEES', 'APPRAISAL', 'AUCTION', 'PUBLICATION', 'CERTIFICATES', 'TRAVEL', 'OTHER')),
    constraint ck_legal_expense_advanced_by check (advanced_by in ('CREDITOR', 'AGENCY', 'LAW_FIRM')
        and ((advanced_by = 'LAW_FIRM') = (law_firm_id is not null))),
    constraint ck_legal_expense_status check (status in ('RECORDED', 'REIMBURSED', 'CANCELLED')
        and ((status = 'RECORDED') = (status_date is null))),
    constraint ck_legal_expense_amount check (amount > 0)
);
create index ix_legal_expense_matter on legal.legal_expense (tenant_id, matter_id, incurred_on desc);
select shared.enable_tenant_rls('legal.legal_expense');

-- -----------------------------------------------------------------------------
-- Acordos do negotiation conhecidos pelo jurídico (por id, via AgreementStatusChanged)
-- -----------------------------------------------------------------------------
create table legal.agreement_record (
    id                     uuid          not null primary key,
    tenant_id              uuid          not null references organization.tenant (id),
    version                bigint        not null,
    created_at             timestamptz   not null,
    created_by             uuid,
    updated_at             timestamptz   not null,
    updated_by             uuid,
    agreement_id           uuid          not null,
    collection_case_id     uuid,
    debtor_id              uuid          not null,
    creditor_id            uuid          not null,
    portfolio_id           uuid,
    matter_id              uuid          references legal.legal_matter (id),
    judicial               boolean       not null,
    status                 varchar(20)   not null,
    broken                 boolean       not null,
    replaced_agreement_id  uuid,
    last_event_at          timestamptz   not null,
    constraint uk_agreement_record unique (tenant_id, agreement_id)
);
create index ix_agreement_record_case on legal.agreement_record (tenant_id, collection_case_id) where broken;
create index ix_agreement_record_matter on legal.agreement_record (tenant_id, matter_id) where matter_id is not null;
select shared.enable_tenant_rls('legal.agreement_record');

-- -----------------------------------------------------------------------------
-- Privilégios do papel de runtime
-- -----------------------------------------------------------------------------
grant select, insert, update, delete on all tables in schema legal to app_runtime;
-- históricos, andamentos, análises e documentos são imutáveis
revoke update, delete on legal.matter_stage_history, legal.legal_document, legal.viability_assessment,
    legal.viability_checklist_item, legal.case_movement from app_runtime;
-- despesas: só a situação muda (cancelamento/reembolso), nunca o valor
revoke update, delete on legal.legal_expense from app_runtime;
grant update (status, status_date, status_reason, version, updated_at, updated_by) on legal.legal_expense to app_runtime;
-- casos jurídicos, medidas, processos, prazos, audiências, autorizações e escritórios são encerrados, nunca removidos
revoke delete on legal.legal_matter, legal.legal_matter_title, legal.extrajudicial_measure,
    legal.extrajudicial_measure_title, legal.lawsuit, legal.lawsuit_party, legal.legal_deadline, legal.hearing,
    legal.creditor_authorization, legal.law_firm, legal.law_firm_member, legal.eligibility_rule,
    legal.agreement_record, legal.legal_settings from app_runtime;
