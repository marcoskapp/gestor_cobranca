-- =============================================================================
-- Módulo communication (M05, F12, RB07): contas de canal (WhatsApp, e-mail, SMS)
-- com credenciais de provedor criptografadas e remetente por credor, templates
-- versionados com variáveis tipadas e aprovação do WhatsApp, conversas com dono
-- do atendimento (ASSISTANT, QUEUE, USER, NONE), transferências, rascunhos do
-- assistente, mensagens e recibos de webhook.
--
-- Conteúdo de mensagens, variáveis, rascunhos e resumos de transferência ficam
-- criptografados (*_encrypted, AES-256-GCM pela aplicação); contatos são guardados
-- por blind index (contact_hash) e mascarados para exibição.
-- Transferências são append-only. Mensagens e conversas nunca são removidas: o
-- conteúdo é descartado pela retenção (MESSAGE_CONTENT, compliance).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Conta de canal: provedor, remetente e credenciais (JSON criptografado).
-- creditor_id nulo = conta padrão da assessoria para o canal.
-- -----------------------------------------------------------------------------
create table communication.channel_account (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    channel                 varchar(20)    not null,
    provider                varchar(30)    not null,
    creditor_id             uuid           references organization.creditor (id),
    name                    varchar(120)   not null,
    sender_address          varchar(320)   not null,
    sender_name             varchar(120),
    reply_to                varchar(320),
    credentials_encrypted   varchar(8000),
    credentials_updated_at  timestamptz,
    routing_key             varchar(64)    not null,
    active                  boolean        not null,
    constraint ck_channel_account_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS')),
    constraint ck_channel_account_provider check (provider in ('WHATSAPP_CLOUD', 'SMTP', 'SANDBOX')),
    constraint uk_channel_account_routing unique (routing_key)
);
-- uma conta ativa por canal e credor (ou padrão da assessoria)
create unique index uk_channel_account_active on communication.channel_account
    (tenant_id, channel, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid)) where active;
select shared.enable_tenant_rls('communication.channel_account');

-- -----------------------------------------------------------------------------
-- Rota de webhook (global, sem RLS): o provedor chama /webhooks/<provedor>/{routingKey}
-- sem contexto de tenant; a rota resolve o tenant e a conta antes da validação da
-- assinatura, feita no tenant com o segredo da conta. Não contém segredo nem dado pessoal.
-- -----------------------------------------------------------------------------
create table communication.webhook_route (
    routing_key         varchar(64)   not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    channel_account_id  uuid          not null references communication.channel_account (id),
    provider            varchar(30)   not null,
    active              boolean       not null,
    created_at          timestamptz   not null
);

-- -----------------------------------------------------------------------------
-- Templates versionados por credor (nulo = assessoria). Uma versão publicada por
-- (credor, código); rascunhos são editáveis, versões publicadas são imutáveis.
-- -----------------------------------------------------------------------------
create table communication.message_template (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    creditor_id             uuid           references organization.creditor (id),
    code                    varchar(60)    not null,
    template_version        integer        not null,
    channel                 varchar(20)    not null,
    name                    varchar(120)   not null,
    purpose                 varchar(30)    not null,
    subject                 varchar(300),
    body                    varchar(8000)  not null,
    variables               jsonb          not null,
    language                varchar(10)    not null,
    status                  varchar(20)    not null,
    provider_template_name  varchar(120),
    approval_status         varchar(20)    not null,
    approval_reference      varchar(120),
    approval_note           varchar(500),
    approval_decided_at     timestamptz,
    approval_registered_by  uuid,
    published_at            timestamptz,
    published_by            uuid,
    retired_at              timestamptz,
    constraint ck_template_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS')),
    constraint ck_template_purpose check (purpose in ('COLLECTION', 'REMINDER', 'NOTIFICATION', 'AGREEMENT',
                                                      'PAYMENT', 'SERVICE')),
    constraint ck_template_status check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    constraint ck_template_approval check (approval_status in ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_template_whatsapp check (channel <> 'WHATSAPP' or approval_status <> 'NOT_REQUIRED'),
    constraint ck_template_published check (status <> 'PUBLISHED' or published_at is not null),
    constraint ck_template_published_approved check (status <> 'PUBLISHED' or channel <> 'WHATSAPP'
                                                     or (approval_status = 'APPROVED'
                                                         and provider_template_name is not null)),
    constraint ck_template_version check (template_version >= 1)
);
create unique index uk_template_version on communication.message_template
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid), code, template_version);
create unique index uk_template_published on communication.message_template
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid), code) where status = 'PUBLISHED';
create index ix_template_code on communication.message_template (tenant_id, code, status);
select shared.enable_tenant_rls('communication.message_template');

-- -----------------------------------------------------------------------------
-- Conversas: devedor (nulo = contato ainda não identificado), credor, caso,
-- canal, conta e contato (blind index). Uma conversa aberta por conta, contato e
-- credor. O dono do atendimento acompanha o do caso (collection), quando há caso.
-- -----------------------------------------------------------------------------
create table communication.conversation (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    debtor_id             uuid           references debt.debtor (id),
    creditor_id           uuid           references organization.creditor (id),
    portfolio_id          uuid           references organization.portfolio (id),
    case_id               uuid           references collection.collection_case (id),
    channel               varchar(20)    not null,
    channel_account_id    uuid           not null references communication.channel_account (id),
    contact_id            uuid           references debt.debtor_contact (id),
    contact_hash          varchar(64)    not null,
    contact_masked        varchar(254)   not null,
    contact_encrypted     varchar(1000)  not null,
    status                varchar(20)    not null,
    owner_type            varchar(20)    not null,
    owner_user_id         uuid,
    owner_team_id         uuid,
    required_profile      varchar(40),
    owner_since           timestamptz    not null,
    last_inbound_at       timestamptz,
    last_outbound_at      timestamptz,
    last_message_at       timestamptz,
    awaiting_since        timestamptz,
    unread_count          integer        not null,
    message_count         integer        not null,
    closed_at             timestamptz,
    closed_by             uuid,
    close_reason          varchar(300),
    constraint ck_conversation_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS')),
    constraint ck_conversation_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_conversation_owner check (owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE')),
    constraint ck_conversation_owner_user check ((owner_type = 'USER') = (owner_user_id is not null)),
    constraint ck_conversation_closed check ((status = 'CLOSED') = (closed_at is not null)),
    constraint ck_conversation_counts check (unread_count >= 0 and message_count >= 0)
);
create unique index uk_conversation_open on communication.conversation
    (tenant_id, channel_account_id, contact_hash, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where status = 'OPEN';
create index ix_conversation_contact on communication.conversation (tenant_id, channel_account_id, contact_hash, status);
create index ix_conversation_inbox on communication.conversation (tenant_id, status, owner_type, awaiting_since);
create index ix_conversation_owner_user on communication.conversation (tenant_id, owner_user_id)
    where status = 'OPEN' and owner_user_id is not null;
create index ix_conversation_case on communication.conversation (tenant_id, case_id) where case_id is not null;
create index ix_conversation_debtor on communication.conversation (tenant_id, debtor_id, last_message_at desc);
select shared.enable_tenant_rls('communication.conversation');

-- -----------------------------------------------------------------------------
-- Transferências de dono (append-only): de, para, motivo, resumo e perfil exigido.
-- -----------------------------------------------------------------------------
create table communication.conversation_handoff (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    conversation_id     uuid           not null references communication.conversation (id),
    case_id             uuid           references collection.collection_case (id),
    from_owner_type     varchar(20)    not null,
    from_user_id        uuid,
    to_owner_type       varchar(20)    not null,
    to_user_id          uuid,
    to_team_id          uuid,
    kind                varchar(30)    not null,
    reason_code         varchar(60)    not null,
    summary_encrypted   varchar(12000),
    required_profile    varchar(40),
    actor_type          varchar(20)    not null,
    actor_id            uuid           not null,
    reference_type      varchar(40),
    reference_id        uuid,
    occurred_at         timestamptz    not null,
    constraint ck_handoff_from check (from_owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE')),
    constraint ck_handoff_to check (to_owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE')),
    constraint ck_handoff_kind check (kind in ('ESCALATION', 'TAKE_OVER', 'ASSIGNMENT', 'RELEASE',
                                              'RETURN_TO_ASSISTANT', 'CLOSE', 'REOPEN', 'CASE_SYNC'))
);
create index ix_handoff_conversation on communication.conversation_handoff (tenant_id, conversation_id, occurred_at);
select shared.enable_tenant_rls('communication.conversation_handoff');
create trigger trg_handoff_append_only before update or delete on communication.conversation_handoff
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Mensagens. provider_message_id único por canal (idempotência dos webhooks);
-- idempotency_key do cliente evita envio duplicado em reenvio de comando.
-- -----------------------------------------------------------------------------
create table communication.message (
    id                     uuid            not null primary key,
    tenant_id              uuid            not null references organization.tenant (id),
    version                bigint          not null,
    created_at             timestamptz     not null,
    created_by             uuid,
    updated_at             timestamptz     not null,
    updated_by             uuid,
    conversation_id        uuid            not null references communication.conversation (id),
    channel                varchar(20)     not null,
    channel_account_id     uuid            not null references communication.channel_account (id),
    direction              varchar(10)     not null,
    sender_type            varchar(20)     not null,
    sender_id              uuid,
    template_id            uuid            references communication.message_template (id),
    variables_encrypted    varchar(12000),
    subject_encrypted      varchar(2000),
    content_encrypted      varchar(100000),
    content_purged_at      timestamptz,
    status                 varchar(20)     not null,
    status_at              timestamptz     not null,
    provider_message_id    varchar(200),
    contact_attempt_id     uuid,
    send_attempts          integer         not null,
    failure_code           varchar(60),
    failure_detail         varchar(500),
    campaign_execution_id  uuid,
    draft_id               uuid,
    idempotency_key        varchar(100),
    queued_at              timestamptz,
    sent_at                timestamptz,
    delivered_at           timestamptz,
    read_at                timestamptz,
    failed_at              timestamptz,
    received_at            timestamptz,
    constraint ck_message_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS')),
    constraint ck_message_direction check (direction in ('OUTBOUND', 'INBOUND')),
    constraint ck_message_sender check (sender_type in ('USER', 'ASSISTANT', 'CAMPAIGN', 'SYSTEM', 'DEBTOR')),
    constraint ck_message_status check (status in ('QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'RECEIVED')),
    constraint ck_message_direction_status check ((direction = 'INBOUND') = (status = 'RECEIVED')),
    constraint ck_message_inbound_sender check ((direction = 'INBOUND') = (sender_type = 'DEBTOR')),
    constraint ck_message_failed check ((status = 'FAILED') = (failure_code is not null)),
    constraint ck_message_attempts check (send_attempts >= 0)
);
create unique index uk_message_provider_id on communication.message (tenant_id, channel, provider_message_id)
    where provider_message_id is not null;
create unique index uk_message_idempotency on communication.message (tenant_id, idempotency_key)
    where idempotency_key is not null;
create index ix_message_conversation on communication.message (tenant_id, conversation_id, created_at);
create index ix_message_retention on communication.message (tenant_id, created_at) where content_purged_at is null;
select shared.enable_tenant_rls('communication.message');

-- -----------------------------------------------------------------------------
-- Rascunhos e sugestões do assistente (modos sombra e copiloto): o operador envia
-- (com ou sem edição) ou descarta.
-- -----------------------------------------------------------------------------
create table communication.conversation_draft (
    id                 uuid            not null primary key,
    tenant_id          uuid            not null references organization.tenant (id),
    version            bigint          not null,
    created_at         timestamptz     not null,
    created_by         uuid,
    updated_at         timestamptz     not null,
    updated_by         uuid,
    conversation_id    uuid            not null references communication.conversation (id),
    mode               varchar(20)     not null,
    author_type        varchar(20)     not null,
    author_id          uuid            not null,
    content_encrypted  varchar(100000) not null,
    rationale          varchar(500),
    status             varchar(20)     not null,
    decided_by         uuid,
    decided_at         timestamptz,
    edited             boolean         not null,
    message_id         uuid            references communication.message (id),
    constraint ck_draft_mode check (mode in ('SHADOW', 'COPILOT')),
    constraint ck_draft_status check (status in ('PENDING', 'SENT', 'DISCARDED', 'EXPIRED')),
    constraint ck_draft_sent check ((status = 'SENT') = (message_id is not null))
);
create index ix_draft_conversation on communication.conversation_draft (tenant_id, conversation_id, status);
select shared.enable_tenant_rls('communication.conversation_draft');

-- -----------------------------------------------------------------------------
-- Recibos de webhook (proteção contra reentrega/replay do mesmo corpo assinado).
-- -----------------------------------------------------------------------------
create table communication.webhook_receipt (
    id                  uuid          not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    channel_account_id  uuid          not null references communication.channel_account (id),
    payload_sha256      varchar(64)   not null,
    received_at         timestamptz   not null,
    constraint uk_webhook_receipt unique (tenant_id, channel_account_id, payload_sha256)
);
create index ix_webhook_receipt_received on communication.webhook_receipt (tenant_id, received_at);
select shared.enable_tenant_rls('communication.webhook_receipt');

grant select, insert, update, delete on all tables in schema communication to app_runtime;
-- transferências são imutáveis; mensagens, conversas, contas e templates são encerrados/desativados, nunca removidos
revoke update, delete on communication.conversation_handoff from app_runtime;
revoke delete on communication.message, communication.conversation, communication.channel_account,
    communication.message_template, communication.conversation_draft, communication.webhook_route from app_runtime;
