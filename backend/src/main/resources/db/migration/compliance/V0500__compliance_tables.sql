-- =============================================================================
-- Módulo compliance (M05/M09, RB02, RB07, RB09/LGPD): restrições de contato e
-- opt-out, políticas de contato versionadas, feriados, registro de tentativas,
-- bases legais por credor, solicitações de titulares (art. 18) e retenção/descarte.
--
-- Dados pessoais do titular (nome, documento, contato informado na solicitação)
-- ficam criptografados (*_encrypted) com blind index (*_hash) quando há busca.
-- Restrições por contato específico guardam apenas o blind index e o valor mascarado.
-- Tentativas de contato e itens de execução de retenção são append-only.
-- =============================================================================

-- Exclusão de sobreposição de vigência (bases legais) exige operadores de igualdade em GiST.
-- btree_gist é extensão confiável (PG13+): o dono do banco pode criá-la.
create extension if not exists btree_gist with schema shared;

-- -----------------------------------------------------------------------------
-- Solicitações de titulares (LGPD art. 18). O devedor fica nulo até a identidade
-- ser confirmada e o titular localizado na base.
-- -----------------------------------------------------------------------------
create table compliance.data_subject_request (
    id                           uuid           not null primary key,
    tenant_id                    uuid           not null references organization.tenant (id),
    version                      bigint         not null,
    created_at                   timestamptz    not null,
    created_by                   uuid,
    updated_at                   timestamptz    not null,
    updated_by                   uuid,
    debtor_id                    uuid           references debt.debtor (id),
    requester_name_encrypted     varchar(2048)  not null,
    document_encrypted           varchar(512),
    document_hash                varchar(64),
    requester_contact_encrypted  varchar(2048),
    details_encrypted            varchar(8192),
    channel                      varchar(20)    not null,
    type                         varchar(40)    not null,
    status                       varchar(20)    not null,
    received_at                  timestamptz    not null,
    due_at                       timestamptz    not null,
    overdue                      boolean        not null,
    overdue_at                   timestamptz,
    assigned_to                  uuid,
    identity_verified_at         timestamptz,
    identity_verified_by         uuid,
    executed_at                  timestamptz,
    completed_at                 timestamptz,
    response_summary             varchar(4000),
    result_file_key              varchar(512),
    result_sha256                varchar(64),
    constraint ck_dsr_channel check (channel in ('EMAIL', 'PHONE', 'WHATSAPP', 'PORTAL', 'LETTER', 'IN_PERSON',
                                                 'CREDITOR_FORWARD')),
    constraint ck_dsr_type check (type in ('CONFIRMATION', 'ACCESS', 'CORRECTION', 'ANONYMIZATION', 'PORTABILITY',
                                           'INFORMATION_SHARING', 'OPPOSITION', 'REVIEW_AUTOMATED_DECISION')),
    constraint ck_dsr_status check (status in ('RECEIVED', 'IDENTITY_VERIFIED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED')),
    constraint ck_dsr_document check ((document_encrypted is null) = (document_hash is null)),
    constraint ck_dsr_due check (due_at >= received_at),
    constraint ck_dsr_overdue check (not overdue or overdue_at is not null),
    constraint ck_dsr_identity check (status = 'RECEIVED' or status = 'REJECTED' or identity_verified_at is not null),
    constraint ck_dsr_executed check (executed_at is null or identity_verified_at is not null),
    constraint ck_dsr_final check ((status in ('COMPLETED', 'REJECTED')) = (completed_at is not null)
                                   and (status not in ('COMPLETED', 'REJECTED') or response_summary is not null))
);
create index ix_dsr_status_due on compliance.data_subject_request (tenant_id, status, due_at);
create index ix_dsr_debtor on compliance.data_subject_request (tenant_id, debtor_id) where debtor_id is not null;
create index ix_dsr_document on compliance.data_subject_request (tenant_id, document_hash) where document_hash is not null;
select shared.enable_tenant_rls('compliance.data_subject_request');

-- -----------------------------------------------------------------------------
-- Restrições de contato (opt-out, bloqueios jurídicos/operacionais). Sem devedor,
-- a restrição vale para o contato (blind index) independentemente do devedor —
-- caso típico: palavra de descadastro recebida de um número.
-- credor nulo = todos os credores; canal nulo = todos os canais.
-- -----------------------------------------------------------------------------
create table compliance.contact_restriction (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by               uuid,
    updated_at               timestamptz    not null,
    updated_by               uuid,
    debtor_id                uuid           references debt.debtor (id),
    creditor_id              uuid           references organization.creditor (id),
    channel                  varchar(20),
    type                     varchar(40)    not null,
    contact_value_type       varchar(10),
    contact_value_hash       varchar(64),
    contact_value_masked     varchar(254),
    reason                   varchar(1000)  not null,
    source                   varchar(30)    not null,
    starts_at                timestamptz    not null,
    ends_at                  timestamptz,
    status                   varchar(20)    not null,
    revoked_at               timestamptz,
    revoked_by               uuid,
    revoked_reason           varchar(1000),
    origin_request_id        uuid           references compliance.data_subject_request (id),
    evidence_file_key        varchar(512),
    evidence_file_name       varchar(200),
    evidence_content_type    varchar(100),
    evidence_sha256          varchar(64),
    expiry_notified_at       timestamptz,
    constraint ck_contact_restriction_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS', 'PHONE', 'PORTAL')),
    constraint ck_contact_restriction_type check (type in ('OPT_OUT', 'DEBTOR_REQUEST', 'LEGAL_BLOCK', 'CREDITOR_REQUEST',
                                                           'DECEASED', 'FRAUD_SUSPECTED', 'INVALID_CONTACT_HISTORY')),
    constraint ck_contact_restriction_source check (source in ('OPERATOR', 'DEBTOR', 'PROVIDER_STOP_KEYWORD', 'CREDITOR',
                                                               'LEGAL')),
    constraint ck_contact_restriction_status check (status in ('ACTIVE', 'REVOKED')),
    constraint ck_contact_restriction_value_type check (contact_value_type in ('PHONE', 'EMAIL')),
    constraint ck_contact_restriction_value check (
        (contact_value_hash is null and contact_value_type is null and contact_value_masked is null)
        or (contact_value_hash is not null and contact_value_type is not null and contact_value_masked is not null)),
    constraint ck_contact_restriction_target check (debtor_id is not null or contact_value_hash is not null),
    constraint ck_contact_restriction_period check (ends_at is null or ends_at > starts_at),
    constraint ck_contact_restriction_revoked check (
        (status = 'ACTIVE' and revoked_at is null and revoked_reason is null)
        or (status = 'REVOKED' and revoked_at is not null and revoked_reason is not null)),
    constraint ck_contact_restriction_evidence check (
        (evidence_file_key is null) = (evidence_sha256 is null))
);
-- porteiro de contato: restrições ativas do devedor e de um contato específico
create index ix_contact_restriction_debtor on compliance.contact_restriction (tenant_id, debtor_id)
    where status = 'ACTIVE';
create index ix_contact_restriction_value on compliance.contact_restriction (tenant_id, contact_value_hash)
    where status = 'ACTIVE' and contact_value_hash is not null;
create index ix_contact_restriction_creditor on compliance.contact_restriction (tenant_id, creditor_id, created_at desc);
-- aviso de fim de vigência de restrições temporárias (recálculo de elegibilidade)
create index ix_contact_restriction_expiring on compliance.contact_restriction (tenant_id, ends_at)
    where status = 'ACTIVE' and ends_at is not null and expiry_notified_at is null;
select shared.enable_tenant_rls('compliance.contact_restriction');

-- -----------------------------------------------------------------------------
-- Política de contato versionada: padrão do tenant (credor nulo) e, opcionalmente,
-- por credor. Cada publicação é uma nova versão imutável com vigência a partir de
-- effective_from; a vigente é a de maior effective_from já alcançado.
-- -----------------------------------------------------------------------------
create table compliance.contact_policy (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    creditor_id     uuid           references organization.creditor (id),
    policy_version  integer        not null,
    effective_from  timestamptz    not null,
    notes           varchar(1000),
    constraint ck_contact_policy_version check (policy_version > 0)
);
create unique index uk_contact_policy_tenant_version on compliance.contact_policy (tenant_id, policy_version)
    where creditor_id is null;
create unique index uk_contact_policy_creditor_version on compliance.contact_policy (tenant_id, creditor_id, policy_version)
    where creditor_id is not null;
create index ix_contact_policy_effective on compliance.contact_policy (tenant_id, creditor_id, effective_from desc);
select shared.enable_tenant_rls('compliance.contact_policy');

-- Regras por canal de uma versão. Janela nula (início, fim e dias) herda a janela da
-- assessoria; início = fim significa dia inteiro; início > fim atravessa a meia-noite
-- (o trecho após a meia-noite pertence ao dia em que a janela abriu).
create table compliance.contact_policy_rule (
    policy_id             uuid          not null references compliance.contact_policy (id),
    channel               varchar(20)   not null,
    window_start          time,
    window_end            time,
    allowed_days          varchar(27),
    max_per_day           integer,
    max_per_week          integer,
    min_interval_minutes  integer,
    block_on_holidays     boolean       not null,
    primary key (policy_id, channel),
    constraint ck_contact_policy_rule_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS', 'PHONE', 'PORTAL')),
    constraint ck_contact_policy_rule_window check ((window_start is null) = (window_end is null)
                                                   and (window_start is null) = (allowed_days is null)),
    constraint ck_contact_policy_rule_days check (allowed_days is null or allowed_days ~ '^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$'),
    constraint ck_contact_policy_rule_day_limit check (max_per_day is null or max_per_day > 0),
    constraint ck_contact_policy_rule_week_limit check (max_per_week is null or max_per_week > 0),
    constraint ck_contact_policy_rule_interval check (min_interval_minutes is null or min_interval_minutes > 0)
);
alter table compliance.contact_policy_rule enable row level security;
alter table compliance.contact_policy_rule force row level security;
create policy tenant_isolation on compliance.contact_policy_rule
    using (exists (select 1 from compliance.contact_policy p where p.id = policy_id))
    with check (exists (select 1 from compliance.contact_policy p where p.id = policy_id));
-- versões publicadas são imutáveis
create trigger trg_contact_policy_immutable before update or delete on compliance.contact_policy
    for each row execute function shared.forbid_mutation();
create trigger trg_contact_policy_rule_immutable before update or delete on compliance.contact_policy_rule
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Feriados cadastrados pela assessoria (bloqueio de contato quando a regra pede).
-- -----------------------------------------------------------------------------
create table compliance.holiday (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    holiday_date  date          not null,
    name          varchar(120)  not null,
    constraint uk_holiday_date unique (tenant_id, holiday_date)
);
select shared.enable_tenant_rls('compliance.holiday');

-- -----------------------------------------------------------------------------
-- Tentativas de contato (append-only): base dos limites diário/semanal e do
-- intervalo mínimo. Gravadas pelo porteiro de contato sob lock por devedor.
-- -----------------------------------------------------------------------------
create table compliance.contact_attempt (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    debtor_id       uuid          not null references debt.debtor (id),
    creditor_id     uuid          not null references organization.creditor (id),
    channel         varchar(20)   not null,
    attempted_at    timestamptz   not null,
    origin          varchar(20)   not null,
    reference_type  varchar(40),
    reference_id    uuid,
    constraint ck_contact_attempt_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS', 'PHONE', 'PORTAL')),
    constraint ck_contact_attempt_origin check (origin in ('OPERATOR', 'CAMPAIGN', 'SYSTEM')),
    constraint ck_contact_attempt_reference check ((reference_type is null) = (reference_id is null))
);
create index ix_contact_attempt_gate on compliance.contact_attempt (tenant_id, debtor_id, channel, attempted_at desc);
create index ix_contact_attempt_creditor on compliance.contact_attempt (tenant_id, creditor_id, attempted_at desc);
select shared.enable_tenant_rls('compliance.contact_attempt');
create trigger trg_contact_attempt_append_only before update or delete on compliance.contact_attempt
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Base legal do tratamento por credor (e opcionalmente por carteira), com papel da
-- assessoria (operador/controlador) e vigência sem sobreposição.
-- -----------------------------------------------------------------------------
create table compliance.legal_basis_record (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    creditor_id         uuid           not null references organization.creditor (id),
    portfolio_id        uuid           references organization.portfolio (id),
    legal_basis         varchar(40)    not null,
    processing_role     varchar(20)    not null,
    contract_reference  varchar(120)   not null,
    valid_from          date           not null,
    valid_until         date,
    legal_responsible   varchar(150)   not null,
    retention_months    integer        not null,
    notes               varchar(1000),
    end_reason          varchar(500),
    constraint ck_legal_basis check (legal_basis in ('EXECUCAO_CONTRATO', 'EXERCICIO_REGULAR_DIREITOS', 'LEGITIMO_INTERESSE',
                                                     'PROTECAO_CREDITO', 'CONSENTIMENTO', 'OBRIGACAO_LEGAL')),
    constraint ck_legal_basis_role check (processing_role in ('OPERADOR', 'CONTROLADOR', 'CO_CONTROLADOR')),
    constraint ck_legal_basis_period check (valid_until is null or valid_until >= valid_from),
    constraint ck_legal_basis_retention check (retention_months > 0),
    constraint ex_legal_basis_overlap exclude using gist (
        tenant_id with =,
        creditor_id with =,
        (coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid)) with =,
        daterange(valid_from, valid_until, '[]') with &&)
);
create index ix_legal_basis_creditor on compliance.legal_basis_record (tenant_id, creditor_id, portfolio_id, valid_from);
select shared.enable_tenant_rls('compliance.legal_basis_record');

-- -----------------------------------------------------------------------------
-- Retenção e descarte: política por categoria (desabilitada por padrão), execuções
-- (simuladas ou efetivas) e itens processados (append-only, evidência do descarte).
-- -----------------------------------------------------------------------------
create table compliance.retention_policy (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    category          varchar(30)   not null,
    retention_months  integer       not null,
    action            varchar(20)   not null,
    enabled           boolean       not null,
    constraint uk_retention_policy_category unique (tenant_id, category),
    constraint ck_retention_policy_category check (category in ('DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT',
                                                                'CALL_RECORDINGS')),
    constraint ck_retention_policy_action check (action in ('ANONYMIZE', 'DELETE_FILE')),
    constraint ck_retention_policy_action_category check (
        (category = 'DEBTOR_INACTIVE' and action = 'ANONYMIZE') or (category <> 'DEBTOR_INACTIVE' and action = 'DELETE_FILE')),
    constraint ck_retention_policy_months check (retention_months > 0)
);
select shared.enable_tenant_rls('compliance.retention_policy');

create table compliance.retention_run (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    category          varchar(30)   not null,
    trigger_type      varchar(20)   not null,
    dry_run           boolean       not null,
    retention_months  integer       not null,
    cutoff            timestamptz   not null,
    started_at        timestamptz   not null,
    finished_at       timestamptz,
    status            varchar(20)   not null,
    candidate_count   integer       not null,
    eligible_count    integer       not null,
    processed_count   integer       not null,
    retained_count    integer       not null,
    failed_count      integer       not null,
    failure_reason    varchar(500),
    constraint ck_retention_run_category check (category in ('DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT',
                                                             'CALL_RECORDINGS')),
    constraint ck_retention_run_trigger check (trigger_type in ('SCHEDULED', 'MANUAL')),
    constraint ck_retention_run_status check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_retention_run_finished check ((status = 'RUNNING') = (finished_at is null)),
    constraint ck_retention_run_counts check (candidate_count >= 0 and eligible_count >= 0 and processed_count >= 0
                                              and retained_count >= 0 and failed_count >= 0)
);
-- no máximo uma execução em andamento por categoria
create unique index uk_retention_run_running on compliance.retention_run (tenant_id, category) where status = 'RUNNING';
create index ix_retention_run_time on compliance.retention_run (tenant_id, started_at desc);
select shared.enable_tenant_rls('compliance.retention_run');

create table compliance.retention_run_item (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    run_id        uuid          not null references compliance.retention_run (id),
    debtor_id     uuid          not null references debt.debtor (id),
    outcome       varchar(20)   not null,
    error_code    varchar(100),
    detail        varchar(500),
    processed_at  timestamptz   not null,
    constraint uk_retention_run_item unique (tenant_id, run_id, debtor_id),
    -- ELIGIBLE: simulação; RETAINED: mantido porque a base legal de um credor exige prazo maior
    constraint ck_retention_run_item_outcome check (outcome in ('ELIGIBLE', 'ANONYMIZED', 'RETAINED', 'FAILED')),
    constraint ck_retention_run_item_error check ((outcome = 'FAILED') = (error_code is not null))
);
create index ix_retention_run_item_run on compliance.retention_run_item (tenant_id, run_id, processed_at);
select shared.enable_tenant_rls('compliance.retention_run_item');
create trigger trg_retention_run_item_append_only before update or delete on compliance.retention_run_item
    for each row execute function shared.forbid_mutation();

grant select, insert, update, delete on all tables in schema compliance to app_runtime;
-- histórico imutável: tentativas, itens de retenção e versões de política
revoke update, delete on compliance.contact_attempt, compliance.retention_run_item, compliance.contact_policy,
    compliance.contact_policy_rule from app_runtime;
-- restrições são revogadas (não removidas); solicitações, bases legais, políticas e execuções são preservadas
revoke delete on compliance.contact_restriction, compliance.data_subject_request, compliance.legal_basis_record,
    compliance.retention_policy, compliance.retention_run from app_runtime;
