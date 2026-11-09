-- =============================================================================
-- Módulo complaint (I5 da revisão de produto de 11/2026 — docs/tecnico/desenho/conformidade.md §3):
-- reclamações recebidas por Procon, consumidor.gov.br, Bacen/ouvidoria do credor,
-- Reclame Aqui e outros, com prazo por canal, responsável, resposta, encerramento,
-- vínculos (credor obrigatório; devedor e caso opcionais) e evidências em quarentena.
-- Faixa nova V2400–V2499 (docs/arquitetura/visao-geral.md §8).
--
-- Texto com dado pessoal (resumo da reclamação, resposta ao canal) fica
-- criptografado (*_encrypted). Reclamações não são apagadas (rastreabilidade); na
-- anonimização do devedor o texto livre é substituído.
-- =============================================================================
create schema if not exists complaint;
grant usage on schema complaint to app_runtime;

create table complaint.complaint (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    creditor_id          uuid          not null references organization.creditor (id),
    debtor_id            uuid          references debt.debtor (id),
    case_id              uuid          references collection.collection_case (id),
    channel              varchar(20)   not null,
    external_protocol    varchar(60),
    subject              varchar(25)   not null,
    summary_encrypted    text          not null,
    received_at          timestamptz   not null,
    due_date             date          not null,
    deadline_source      varchar(15)   not null,
    warning_date         date          not null,
    status               varchar(15)   not null,
    assignee_id          uuid,
    assigned_at          timestamptz,
    response_encrypted   text,
    answered_at          timestamptz,
    answered_by          uuid,
    answered_on_time     boolean,
    outcome              varchar(20),
    closed_at            timestamptz,
    closed_by            uuid,
    overdue_notified_at  timestamptz,
    registered_by        uuid,
    constraint ck_complaint_channel check (channel in ('PROCON', 'CONSUMIDOR_GOV', 'BACEN', 'CREDITOR_OMBUDSMAN',
                                                       'RECLAME_AQUI', 'OTHER')),
    constraint ck_complaint_subject check (subject in ('ABUSIVE_COLLECTION', 'CONTACT_FREQUENCY',
                                                       'THIRD_PARTY_CONTACT', 'DEBT_NOT_RECOGNIZED', 'WRONG_AMOUNT',
                                                       'AGREEMENT_OR_PAYMENT', 'CREDIT_BUREAU', 'DATA_PRIVACY',
                                                       'OTHER')),
    constraint ck_complaint_status check (status in ('RECEIVED', 'IN_PROGRESS', 'ANSWERED', 'CLOSED')),
    constraint ck_complaint_deadline_source check (deadline_source in ('INFORMED', 'CHANNEL_RULE', 'DEFAULT')),
    constraint ck_complaint_outcome check (outcome is null or outcome in ('RESOLVED', 'NOT_ACCEPTED', 'UNFOUNDED',
                                                                          'CLOSED_BY_CHANNEL')),
    constraint ck_complaint_case_debtor check (case_id is null or debtor_id is not null),
    constraint ck_complaint_warning check (warning_date <= due_date),
    constraint ck_complaint_answered check ((status in ('ANSWERED', 'CLOSED'))
                                            = (answered_at is not null and response_encrypted is not null
                                               and answered_on_time is not null)),
    constraint ck_complaint_closed check ((status = 'CLOSED') = (closed_at is not null and outcome is not null)),
    constraint ck_complaint_answer_after_received check (answered_at is null or answered_at >= received_at)
);
-- protocolo do canal único por canal (evita registro duplicado da mesma reclamação)
create unique index uk_complaint_protocol on complaint.complaint (tenant_id, channel, external_protocol)
    where external_protocol is not null;
create index ix_complaint_open_due on complaint.complaint (tenant_id, creditor_id, warning_date)
    where status in ('RECEIVED', 'IN_PROGRESS');
create index ix_complaint_open_assignee on complaint.complaint (tenant_id, assignee_id, warning_date)
    where status in ('RECEIVED', 'IN_PROGRESS');
create index ix_complaint_due on complaint.complaint (tenant_id, due_date, id);
create index ix_complaint_creditor_received on complaint.complaint (tenant_id, creditor_id, received_at);
create index ix_complaint_creditor_answered on complaint.complaint (tenant_id, creditor_id, answered_at)
    where answered_at is not null;
create index ix_complaint_debtor on complaint.complaint (tenant_id, debtor_id) where debtor_id is not null;
create index ix_complaint_case on complaint.complaint (tenant_id, case_id) where case_id is not null;
select shared.enable_tenant_rls('complaint.complaint');
grant select, insert, update on complaint.complaint to app_runtime;

create table complaint.complaint_evidence (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    complaint_id  uuid          not null references complaint.complaint (id),
    creditor_id   uuid          not null references organization.creditor (id),
    file_key      varchar(512),
    file_name     varchar(255)  not null,
    content_type  varchar(100)  not null,
    size_bytes    bigint        not null,
    sha256        varchar(64)   not null,
    status        varchar(15)   not null,
    verdict       varchar(15),
    engine        varchar(30),
    signature     varchar(200),
    attempts      integer       not null default 0,
    scanned_at    timestamptz,
    constraint ck_complaint_evidence_status check (status in ('PENDING_SCAN', 'AVAILABLE', 'REJECTED', 'FAILED',
                                                              'REMOVED')),
    constraint ck_complaint_evidence_removed check (status <> 'REMOVED' or file_key is null),
    constraint ck_complaint_evidence_verdict check (verdict is null or verdict in ('CLEAN', 'NOT_SCANNED', 'INFECTED')),
    constraint ck_complaint_evidence_type check (content_type in ('application/pdf', 'image/png', 'image/jpeg')),
    constraint ck_complaint_evidence_available check (status <> 'AVAILABLE'
                                                      or (file_key is not null and verdict in ('CLEAN', 'NOT_SCANNED'))),
    constraint ck_complaint_evidence_rejected check (status <> 'REJECTED' or (file_key is null and verdict = 'INFECTED'))
);
create index ix_complaint_evidence_complaint on complaint.complaint_evidence (tenant_id, complaint_id, created_at);
select shared.enable_tenant_rls('complaint.complaint_evidence');
grant select, insert, update on complaint.complaint_evidence to app_runtime;

create table complaint.complaint_deadline_rule (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    channel        varchar(20)   not null,
    response_days  integer       not null,
    warning_days   integer       not null,
    constraint uk_complaint_rule_channel unique (tenant_id, channel),
    constraint ck_complaint_rule_channel check (channel in ('PROCON', 'CONSUMIDOR_GOV', 'BACEN', 'CREDITOR_OMBUDSMAN',
                                                            'RECLAME_AQUI', 'OTHER')),
    constraint ck_complaint_rule_days check (response_days between 1 and 120),
    constraint ck_complaint_rule_warning check (warning_days >= 0 and warning_days < response_days)
);
select shared.enable_tenant_rls('complaint.complaint_deadline_rule');
grant select, insert, update on complaint.complaint_deadline_rule to app_runtime;
