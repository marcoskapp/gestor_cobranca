-- =============================================================================
-- Módulo telephony (F20): contas do provedor de telefonia/discador, operadores
-- (ramal e presença para o discador), intervalos de presença (append-only),
-- campanhas de discagem com lista de casos, chamadas vinculadas ao caso,
-- gravações e proteção dos webhooks (rotas, recibos e eventos do provedor).
--
-- O número discado/recebido fica criptografado pela aplicação (number_encrypted,
-- AES-256-GCM) com blind index (number_hash, finalidade contact.phone) e máscara.
-- Credenciais do provedor criptografadas (credentials_encrypted). Chamadas,
-- gravações e campanhas nunca são removidas; o arquivo da gravação é descartado
-- pela retenção (CALL_RECORDINGS, compliance) ou pela anonimização do devedor.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Conta do provedor: número de origem, credenciais (JSON criptografado), gravação
-- e aviso de gravação. creditor_id nulo = conta padrão da assessoria.
-- -----------------------------------------------------------------------------
create table telephony.provider_account (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    provider                varchar(30)    not null,
    creditor_id             uuid           references organization.creditor (id),
    name                    varchar(120)   not null,
    caller_id               varchar(20)    not null,
    credentials_encrypted   varchar(8000),
    credentials_updated_at  timestamptz,
    routing_key             varchar(64)    not null,
    recording_enabled       boolean        not null,
    recording_notice        boolean        not null,
    active                  boolean        not null,
    constraint ck_tel_account_provider check (provider in ('SANDBOX')),
    constraint ck_tel_account_notice check (not recording_enabled or recording_notice),
    constraint uk_tel_account_routing unique (routing_key)
);
create unique index uk_tel_account_active on telephony.provider_account
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid)) where active;
select shared.enable_tenant_rls('telephony.provider_account');

-- -----------------------------------------------------------------------------
-- Rota de webhook (global, sem RLS): resolve o tenant e a conta pelo routingKey
-- antes da validação da assinatura (feita no tenant, com o segredo da conta).
-- -----------------------------------------------------------------------------
create table telephony.webhook_route (
    routing_key          varchar(64)   not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    provider_account_id  uuid          not null references telephony.provider_account (id),
    provider             varchar(30)   not null,
    active               boolean       not null,
    created_at           timestamptz   not null
);

-- Recibos de corpo de webhook (reentrega idêntica não tem efeito).
create table telephony.webhook_receipt (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    provider_account_id  uuid          not null references telephony.provider_account (id),
    payload_sha256       varchar(64)   not null,
    received_at          timestamptz   not null,
    constraint uk_tel_webhook_receipt unique (tenant_id, provider_account_id, payload_sha256)
);
create index ix_tel_webhook_receipt_received on telephony.webhook_receipt (tenant_id, received_at);
select shared.enable_tenant_rls('telephony.webhook_receipt');

-- Eventos do provedor já aplicados (idempotência por id do evento).
create table telephony.provider_event (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    provider_account_id  uuid          not null references telephony.provider_account (id),
    provider_event_id    varchar(200)  not null,
    event_type           varchar(30)   not null,
    received_at          timestamptz   not null,
    constraint uk_tel_provider_event unique (tenant_id, provider_account_id, provider_event_id)
);
create index ix_tel_provider_event_received on telephony.provider_event (tenant_id, received_at);
select shared.enable_tenant_rls('telephony.provider_event');

-- -----------------------------------------------------------------------------
-- Campanha de discagem: lista a partir de uma fila do collection.
-- -----------------------------------------------------------------------------
create table telephony.dialer_campaign (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by               uuid,
    updated_at               timestamptz    not null,
    updated_by               uuid,
    code                     varchar(40)    not null,
    name                     varchar(120)   not null,
    creditor_id              uuid           references organization.creditor (id),
    portfolio_id             uuid           references organization.portfolio (id),
    queue_id                 uuid           not null references collection.queue (id),
    include_assigned         boolean        not null,
    mode                     varchar(20)    not null,
    status                   varchar(20)    not null,
    max_dial_ratio           numeric(5,2)   not null,
    max_abandon_rate         numeric(9,4)   not null,
    max_attempts             integer        not null,
    retry_interval_minutes   integer        not null,
    ring_timeout_seconds     integer        not null,
    window_start             time,
    window_end               time,
    activated_at             timestamptz,
    paused_at                timestamptz,
    finished_at              timestamptz,
    last_loaded_at           timestamptz,
    entries_loaded           integer        not null,
    constraint ck_dialer_campaign_mode check (mode in ('PROGRESSIVE', 'PREDICTIVE')),
    constraint ck_dialer_campaign_status check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'FINISHED')),
    constraint ck_dialer_campaign_ratio check (max_dial_ratio >= 1 and max_dial_ratio <= 5
                                               and (mode = 'PREDICTIVE' or max_dial_ratio = 1)),
    constraint ck_dialer_campaign_abandon check (max_abandon_rate > 0 and max_abandon_rate <= 10),
    constraint ck_dialer_campaign_attempts check (max_attempts between 1 and 20),
    constraint ck_dialer_campaign_retry check (retry_interval_minutes between 5 and 10080),
    constraint ck_dialer_campaign_ring check (ring_timeout_seconds between 10 and 60),
    constraint ck_dialer_campaign_window check ((window_start is null) = (window_end is null)
                                                and (window_start is null or window_start < window_end)),
    constraint ck_dialer_campaign_finished check ((status = 'FINISHED') = (finished_at is not null)),
    constraint uk_dialer_campaign_code unique (tenant_id, code)
);
create index ix_dialer_campaign_status on telephony.dialer_campaign (tenant_id, status);
select shared.enable_tenant_rls('telephony.dialer_campaign');

create table telephony.dialer_entry (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    campaign_id      uuid           not null references telephony.dialer_campaign (id),
    case_id          uuid           not null references collection.collection_case (id),
    debtor_id        uuid           not null references debt.debtor (id),
    creditor_id      uuid           not null references organization.creditor (id),
    portfolio_id     uuid           references organization.portfolio (id),
    status           varchar(20)    not null,
    priority         integer        not null,
    attempts         integer        not null,
    next_attempt_at  timestamptz    not null,
    last_call_id     uuid,
    last_result      varchar(20),
    last_attempt_at  timestamptz,
    last_denial      varchar(300),
    excluded_reason  varchar(60),
    finished_at      timestamptz,
    constraint ck_dialer_entry_status check (status in ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'EXHAUSTED', 'EXCLUDED')),
    constraint ck_dialer_entry_attempts check (attempts >= 0),
    constraint ck_dialer_entry_excluded check ((status = 'EXCLUDED') = (excluded_reason is not null)),
    constraint uk_dialer_entry_case unique (tenant_id, campaign_id, case_id)
);
create index ix_dialer_entry_due on telephony.dialer_entry (tenant_id, campaign_id, next_attempt_at, priority)
    where status = 'PENDING';
create index ix_dialer_entry_campaign_status on telephony.dialer_entry (tenant_id, campaign_id, status);
select shared.enable_tenant_rls('telephony.dialer_entry');

-- -----------------------------------------------------------------------------
-- Operador na telefonia: ramal e presença para o discador.
-- -----------------------------------------------------------------------------
create table telephony.agent (
    id                 uuid           not null primary key,
    tenant_id          uuid           not null references organization.tenant (id),
    version            bigint         not null,
    created_at         timestamptz    not null,
    created_by         uuid,
    updated_at         timestamptz    not null,
    updated_by         uuid,
    user_id            uuid           not null,
    extension          varchar(64)    not null,
    active             boolean        not null,
    status             varchar(20)    not null,
    pause_reason       varchar(30),
    campaign_id        uuid           references telephony.dialer_campaign (id),
    current_call_id    uuid,
    status_since       timestamptz    not null,
    last_available_at  timestamptz,
    last_heartbeat_at  timestamptz,
    logged_in_at       timestamptz,
    constraint ck_agent_status check (status in ('OFFLINE', 'AVAILABLE', 'ON_CALL', 'WRAP_UP', 'PAUSED')),
    constraint ck_agent_pause check ((status = 'PAUSED') = (pause_reason is not null)),
    constraint ck_agent_pause_reason check (pause_reason is null or pause_reason in ('BREAK', 'LUNCH', 'RESTROOM',
        'TRAINING', 'FEEDBACK', 'MEETING', 'BACKOFFICE', 'TECHNICAL_ISSUE', 'OTHER')),
    constraint ck_agent_call check (status not in ('ON_CALL', 'WRAP_UP') or current_call_id is not null),
    constraint uk_agent_user unique (tenant_id, user_id)
);
create index ix_agent_available on telephony.agent (tenant_id, campaign_id, last_available_at)
    where status = 'AVAILABLE' and active;
select shared.enable_tenant_rls('telephony.agent');

-- Intervalos de presença encerrados (append-only): tempo logado, pausas por motivo.
create table telephony.presence_interval (
    id                uuid           not null primary key,
    tenant_id         uuid           not null references organization.tenant (id),
    version           bigint         not null,
    created_at        timestamptz    not null,
    created_by        uuid,
    updated_at        timestamptz    not null,
    updated_by        uuid,
    agent_id          uuid           not null references telephony.agent (id),
    user_id           uuid           not null,
    status            varchar(20)    not null,
    pause_reason      varchar(30),
    campaign_id       uuid,
    started_at        timestamptz    not null,
    ended_at          timestamptz    not null,
    duration_seconds  bigint         not null,
    constraint ck_presence_interval_status check (status in ('OFFLINE', 'AVAILABLE', 'ON_CALL', 'WRAP_UP', 'PAUSED')),
    constraint ck_presence_interval_range check (ended_at >= started_at and duration_seconds >= 0)
);
create index ix_presence_interval_user on telephony.presence_interval (tenant_id, user_id, started_at);
select shared.enable_tenant_rls('telephony.presence_interval');
create trigger trg_presence_interval_append_only before update or delete on telephony.presence_interval
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Chamadas. (provider, provider_call_id) único: idempotência dos eventos.
-- -----------------------------------------------------------------------------
create table telephony.phone_call (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    direction             varchar(10)    not null,
    origin                varchar(20)    not null,
    status                varchar(20)    not null,
    result                varchar(20),
    provider_account_id   uuid           not null references telephony.provider_account (id),
    provider              varchar(30)    not null,
    provider_call_id      varchar(200),
    creditor_id           uuid           references organization.creditor (id),
    portfolio_id          uuid           references organization.portfolio (id),
    debtor_id             uuid           references debt.debtor (id),
    case_id               uuid           references collection.collection_case (id),
    contact_id            uuid           references debt.debtor_contact (id),
    operator_id           uuid,
    campaign_id           uuid           references telephony.dialer_campaign (id),
    entry_id              uuid           references telephony.dialer_entry (id),
    attempt_number        integer        not null,
    number_encrypted      varchar(500),
    number_hash           varchar(64),
    number_masked         varchar(40)    not null,
    contact_attempt_id    uuid,
    denial_reasons        varchar(300),
    queued_at             timestamptz    not null,
    dial_started_at       timestamptz,
    ringing_at            timestamptz,
    answered_at           timestamptz,
    connected_at          timestamptz,
    ended_at              timestamptz,
    tabulated_at          timestamptz,
    ring_seconds          integer,
    talk_seconds          integer,
    wrap_up_seconds       integer,
    amd_result            varchar(10),
    abandoned             boolean        not null,
    hangup_side           varchar(10),
    failure_code          varchar(60),
    failure_detail        varchar(300),
    recording_requested   boolean        not null,
    recording_announced   boolean        not null,
    tabulation_code       varchar(60),
    tabulated_by          uuid,
    interaction_state     varchar(30)    not null,
    interaction_id        uuid,
    constraint ck_call_direction check (direction in ('OUTBOUND', 'INBOUND')),
    constraint ck_call_origin check (origin in ('CLICK_TO_CALL', 'DIALER', 'INBOUND')),
    constraint ck_call_status check (status in ('QUEUED', 'DIALING', 'RINGING', 'ANSWERED', 'COMPLETED', 'NO_ANSWER',
                                                'BUSY', 'FAILED', 'CANCELLED')),
    constraint ck_call_result check (result is null or result in ('ANSWERED', 'NO_ANSWER', 'BUSY', 'VOICEMAIL', 'FAILED',
                                                                  'CANCELLED', 'ABANDONED')),
    constraint ck_call_terminal check ((status in ('COMPLETED', 'NO_ANSWER', 'BUSY', 'FAILED', 'CANCELLED'))
                                       = (ended_at is not null and result is not null)),
    constraint ck_call_amd check (amd_result is null or amd_result in ('HUMAN', 'MACHINE', 'UNKNOWN')),
    constraint ck_call_hangup check (hangup_side is null or hangup_side in ('DEBTOR', 'OPERATOR', 'SYSTEM', 'PROVIDER')),
    constraint ck_call_interaction check (interaction_state in ('NONE', 'AWAITING_TABULATION', 'PENDING', 'RECORDED',
                                                                'SKIPPED')),
    constraint ck_call_recorded check ((interaction_state = 'RECORDED') = (interaction_id is not null)),
    constraint ck_call_dialer check (origin <> 'DIALER' or (campaign_id is not null and entry_id is not null)),
    constraint ck_call_outbound_target check (direction = 'INBOUND' or (case_id is not null and debtor_id is not null)),
    constraint ck_call_durations check (coalesce(ring_seconds, 0) >= 0 and coalesce(talk_seconds, 0) >= 0
                                        and coalesce(wrap_up_seconds, 0) >= 0)
);
create unique index uk_call_provider_id on telephony.phone_call (tenant_id, provider, provider_call_id)
    where provider_call_id is not null;
create index ix_call_case on telephony.phone_call (tenant_id, case_id, queued_at desc) where case_id is not null;
create index ix_call_operator on telephony.phone_call (tenant_id, operator_id, queued_at desc) where operator_id is not null;
create index ix_call_awaiting on telephony.phone_call (tenant_id, operator_id)
    where interaction_state = 'AWAITING_TABULATION';
create index ix_call_campaign on telephony.phone_call (tenant_id, campaign_id, queued_at) where campaign_id is not null;
create index ix_call_active on telephony.phone_call (tenant_id, status, queued_at)
    where status in ('QUEUED', 'DIALING', 'RINGING', 'ANSWERED');
create index ix_call_number on telephony.phone_call (tenant_id, number_hash, queued_at desc) where number_hash is not null;
create index ix_call_debtor on telephony.phone_call (tenant_id, debtor_id) where debtor_id is not null;
select shared.enable_tenant_rls('telephony.phone_call');

-- -----------------------------------------------------------------------------
-- Gravações: metadados; arquivo no S3 (categoria CALL_RECORDING).
-- -----------------------------------------------------------------------------
create table telephony.call_recording (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    call_id                 uuid           not null references telephony.phone_call (id),
    creditor_id             uuid           references organization.creditor (id),
    debtor_id               uuid           references debt.debtor (id),
    provider_recording_ref  varchar(200)   not null,
    status                  varchar(20)    not null,
    duration_seconds        integer,
    file_key                varchar(500),
    content_type            varchar(100),
    size_bytes              bigint,
    sha256                  varchar(64),
    announced               boolean        not null,
    available_at            timestamptz    not null,
    stored_at               timestamptz,
    failure_code            varchar(60),
    purged_at               timestamptz,
    purge_reason            varchar(30),
    access_count            integer        not null,
    last_accessed_at        timestamptz,
    constraint ck_recording_status check (status in ('PENDING', 'STORED', 'FAILED', 'PURGED')),
    constraint ck_recording_stored check (status <> 'STORED' or (file_key is not null and sha256 is not null)),
    constraint ck_recording_purged check ((status = 'PURGED') = (purged_at is not null)),
    constraint uk_recording_call unique (tenant_id, call_id)
);
create index ix_recording_retention on telephony.call_recording (tenant_id, stored_at) where status = 'STORED';
create index ix_recording_debtor on telephony.call_recording (tenant_id, debtor_id) where status <> 'PURGED';
select shared.enable_tenant_rls('telephony.call_recording');

grant select, insert, update, delete on all tables in schema telephony to app_runtime;
-- intervalos de presença são imutáveis; chamadas, gravações, campanhas e contas nunca são removidas
revoke update, delete on telephony.presence_interval from app_runtime;
revoke delete on telephony.phone_call, telephony.call_recording, telephony.dialer_campaign, telephony.dialer_entry,
    telephony.provider_account, telephony.agent, telephony.webhook_route from app_runtime;
