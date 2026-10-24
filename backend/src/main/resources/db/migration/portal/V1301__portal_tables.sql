-- =============================================================================
-- Módulo portal (F19): portal de autonegociação do devedor.
--   * configuração por credor (marca, carteiras habilitadas, canais do código, desafio
--     de conhecimento, aviso de privacidade) e rota pública global (slug → tenant);
--   * acesso seguro: links de uso único, desafios de código (OTP) — inclusive "fictícios"
--     para documento inexistente (anti-enumeração) —, bloqueio progressivo por documento
--     e IP, sessões curtas próprias do portal (revogáveis);
--   * pedidos que fogem do portal (falar com atendente, contestação) e acordos fechados no
--     portal (funil até o pagamento).
-- Nenhuma tabela guarda documento, contato ou código em claro: só HMAC (blind index) e
-- SHA-256 de tokens; a observação livre do pedido de atendimento é criptografada.
-- Ver docs/tecnico/desenho/portal.md.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Configuração do portal de um credor (uma por credor).
-- -----------------------------------------------------------------------------
create table portal.portal_configuration (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by               uuid,
    updated_at               timestamptz    not null,
    updated_by               uuid,
    creditor_id              uuid           not null references organization.creditor (id),
    slug                     varchar(60)    not null,
    enabled                  boolean        not null,
    display_name             varchar(80)    not null,
    primary_color            varchar(7)     not null,
    accent_color             varchar(7)     not null,
    logo_file_key            varchar(500),
    logo_content_type        varchar(40),
    logo_sha256              varchar(64),
    otp_channels             varchar(60)    not null,
    knowledge_challenge      varchar(20)    not null,
    link_validity_hours      integer        not null,
    privacy_notice_version   integer        not null,
    privacy_notice_text      text           not null,
    privacy_notice_sha256    varchar(64)    not null,
    support_text             varchar(300),
    constraint uk_portal_configuration_creditor unique (tenant_id, creditor_id),
    constraint uk_portal_configuration_slug unique (slug),
    constraint ck_portal_configuration_slug check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) between 3 and 60),
    constraint ck_portal_configuration_colors check (primary_color ~ '^#[0-9A-F]{6}$' and accent_color ~ '^#[0-9A-F]{6}$'),
    constraint ck_portal_configuration_knowledge check (knowledge_challenge in ('NONE', 'BIRTH_DATE', 'CONTRACT_SUFFIX')),
    constraint ck_portal_configuration_link check (link_validity_hours between 1 and 168),
    constraint ck_portal_configuration_notice check (privacy_notice_version >= 1),
    constraint ck_portal_configuration_logo check ((logo_file_key is null) = (logo_content_type is null)
                                                   and (logo_content_type is null or logo_content_type in ('image/png', 'image/jpeg', 'image/webp')))
);
select shared.enable_tenant_rls('portal.portal_configuration');

-- Carteiras habilitadas no portal (vazio = nenhuma: a habilitação é explícita).
create table portal.portal_configuration_portfolio (
    configuration_id  uuid not null references portal.portal_configuration (id) on delete cascade,
    portfolio_id      uuid not null references organization.portfolio (id),
    primary key (configuration_id, portfolio_id)
);
alter table portal.portal_configuration_portfolio enable row level security;
alter table portal.portal_configuration_portfolio force row level security;
create policy tenant_isolation on portal.portal_configuration_portfolio
    using (exists (select 1 from portal.portal_configuration c where c.id = configuration_id))
    with check (exists (select 1 from portal.portal_configuration c where c.id = configuration_id));

-- -----------------------------------------------------------------------------
-- Rota pública (tabela GLOBAL, sem RLS): o devedor chega sem tenant; o slug da URL
-- resolve a assessoria e a configuração. Só o slug e ids — nenhum dado de credor.
-- -----------------------------------------------------------------------------
create table portal.portal_route (
    slug              varchar(60)   not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    configuration_id  uuid          not null references portal.portal_configuration (id),
    active            boolean       not null,
    updated_at        timestamptz   not null
);
create unique index uk_portal_route_configuration on portal.portal_route (configuration_id);

-- -----------------------------------------------------------------------------
-- Link de acesso de uso único (só o SHA-256 do token). Um novo link revoga os anteriores
-- ainda abertos do mesmo devedor no mesmo portal.
-- -----------------------------------------------------------------------------
create table portal.access_link (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    configuration_id  uuid          not null references portal.portal_configuration (id),
    creditor_id       uuid          not null references organization.creditor (id),
    debtor_id         uuid          not null,
    token_hash        varchar(64)   not null,
    channel           varchar(20)   not null,
    issued_by_type    varchar(20)   not null,
    issued_by_id      uuid          not null,
    message_id        uuid,
    expires_at        timestamptz   not null,
    used_at           timestamptz,
    revoked_at        timestamptz,
    constraint uk_access_link_token unique (token_hash),
    constraint ck_access_link_channel check (channel in ('WHATSAPP', 'SMS', 'EMAIL'))
);
create index ix_access_link_debtor on portal.access_link (tenant_id, configuration_id, debtor_id)
    where used_at is null and revoked_at is null;
select shared.enable_tenant_rls('portal.access_link');

-- -----------------------------------------------------------------------------
-- Desafio de acesso: código (OTP) enviado ao contato cadastrado ou etapa do link, com
-- desafio de conhecimento opcional. debtor_id nulo = desafio fictício (documento
-- inexistente/fora do portal): responde igual e nunca é aceito.
-- -----------------------------------------------------------------------------
create table portal.access_challenge (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    configuration_id     uuid          not null references portal.portal_configuration (id),
    debtor_id            uuid,
    method               varchar(10)   not null,
    channel              varchar(20),
    document_hash        varchar(64),
    code_hash            varchar(64),
    link_id              uuid          references portal.access_link (id),
    message_id           uuid,
    knowledge_challenge  varchar(20)   not null,
    attempts             integer       not null,
    max_attempts         integer       not null,
    status               varchar(10)   not null,
    expires_at           timestamptz   not null,
    completed_at         timestamptz,
    constraint ck_access_challenge_method check (method in ('OTP', 'LINK')),
    constraint ck_access_challenge_status check (status in ('PENDING', 'VERIFIED', 'FAILED', 'EXPIRED')),
    constraint ck_access_challenge_knowledge check (knowledge_challenge in ('NONE', 'BIRTH_DATE', 'CONTRACT_SUFFIX')),
    constraint ck_access_challenge_otp check (method <> 'OTP' or code_hash is not null),
    constraint ck_access_challenge_link check (method <> 'LINK' or (link_id is not null and debtor_id is not null)),
    constraint ck_access_challenge_attempts check (attempts between 0 and max_attempts)
);
create index ix_access_challenge_document on portal.access_challenge (tenant_id, document_hash, created_at desc);
create index ix_access_challenge_debtor on portal.access_challenge (tenant_id, debtor_id);
select shared.enable_tenant_rls('portal.access_challenge');

-- -----------------------------------------------------------------------------
-- Bloqueio progressivo por documento e por IP (chaves HMAC). Persistido (vale entre
-- instâncias); o limite por minuto de cada instância fica em memória (web).
-- -----------------------------------------------------------------------------
create table portal.access_throttle (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    key_type           varchar(10)   not null,
    key_hash           varchar(64)   not null,
    window_started_at  timestamptz   not null,
    failures           integer       not null,
    requests           integer       not null,
    lock_count         integer       not null,
    locked_until       timestamptz,
    updated_at         timestamptz   not null,
    constraint uk_access_throttle_key unique (tenant_id, key_type, key_hash),
    constraint ck_access_throttle_type check (key_type in ('DOCUMENT', 'IP')),
    constraint ck_access_throttle_counts check (failures >= 0 and requests >= 0 and lock_count >= 0)
);
select shared.enable_tenant_rls('portal.access_throttle');

-- -----------------------------------------------------------------------------
-- Sessão do portal (curta, revogável), com as etapas do funil atingidas.
-- -----------------------------------------------------------------------------
create table portal.portal_session (
    id                        uuid          not null primary key,
    tenant_id                 uuid          not null references organization.tenant (id),
    version                   bigint        not null,
    created_at                timestamptz   not null,
    created_by                uuid,
    updated_at                timestamptz   not null,
    updated_by                uuid,
    configuration_id          uuid          not null references portal.portal_configuration (id),
    creditor_id               uuid          not null references organization.creditor (id),
    debtor_id                 uuid          not null,
    method                    varchar(10)   not null,
    status                    varchar(10)   not null,
    last_seen_at              timestamptz   not null,
    expires_at                timestamptz   not null,
    ended_at                  timestamptz,
    end_reason                varchar(30),
    client_ip                 varchar(64),
    user_agent                varchar(300),
    privacy_notice_version    integer,
    privacy_acknowledged_at   timestamptz,
    simulated_at              timestamptz,
    accepted_at               timestamptz,
    pix_issued_at             timestamptz,
    constraint ck_portal_session_method check (method in ('OTP', 'LINK')),
    constraint ck_portal_session_status check (status in ('ACTIVE', 'ENDED', 'REVOKED', 'EXPIRED')),
    constraint ck_portal_session_end check ((status = 'ACTIVE') = (ended_at is null))
);
create index ix_portal_session_debtor on portal.portal_session (tenant_id, debtor_id, status);
create index ix_portal_session_funnel on portal.portal_session (tenant_id, configuration_id, created_at);
create index ix_portal_session_active on portal.portal_session (tenant_id, expires_at) where status = 'ACTIVE';
select shared.enable_tenant_rls('portal.portal_session');

-- -----------------------------------------------------------------------------
-- Acordo fechado no portal (etapas aceite → pagamento do funil). Append-only, exceto o
-- instante do primeiro pagamento confirmado (privilégio de coluna).
-- -----------------------------------------------------------------------------
create table portal.portal_agreement (
    agreement_id      uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    session_id        uuid          not null references portal.portal_session (id),
    configuration_id  uuid          not null references portal.portal_configuration (id),
    creditor_id       uuid          not null references organization.creditor (id),
    portfolio_id      uuid,
    debtor_id         uuid          not null,
    proposal_id       uuid          not null,
    installments      integer       not null,
    total_amount      numeric(19,2) not null,
    formalized_at     timestamptz   not null,
    first_paid_at     timestamptz
);
create index ix_portal_agreement_funnel on portal.portal_agreement (tenant_id, configuration_id, formalized_at);
select shared.enable_tenant_rls('portal.portal_agreement');

-- -----------------------------------------------------------------------------
-- Pedido que foge do portal: falar com atendente, contestação, outro. Encaminhado à fila
-- humana do communication (conversa + HandoffRequested) por job.
-- -----------------------------------------------------------------------------
create table portal.service_request (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    version            bigint        not null,
    created_at         timestamptz   not null,
    created_by         uuid,
    updated_at         timestamptz   not null,
    updated_by         uuid,
    configuration_id   uuid          not null references portal.portal_configuration (id),
    session_id         uuid          not null references portal.portal_session (id),
    creditor_id        uuid          not null references organization.creditor (id),
    portfolio_id       uuid,
    debtor_id          uuid          not null,
    protocol           varchar(20)   not null,
    reason             varchar(20)   not null,
    preferred_channel  varchar(20)   not null,
    note_encrypted     text,
    status             varchar(15)   not null,
    conversation_id    uuid,
    message_id         uuid,
    failure_code       varchar(60),
    forwarded_at       timestamptz,
    constraint uk_service_request_protocol unique (tenant_id, protocol),
    constraint ck_service_request_reason check (reason in ('TALK_TO_AGENT', 'DISPUTE', 'CANNOT_AFFORD', 'OTHER')),
    constraint ck_service_request_channel check (preferred_channel in ('WHATSAPP', 'SMS', 'EMAIL')),
    constraint ck_service_request_status check (status in ('OPEN', 'FORWARDED', 'UNDELIVERED'))
);
create index ix_service_request_creditor on portal.service_request (tenant_id, creditor_id, created_at desc);
create index ix_service_request_debtor on portal.service_request (tenant_id, debtor_id, created_at desc);
select shared.enable_tenant_rls('portal.service_request');

grant select, insert, update, delete on all tables in schema portal to app_runtime;
-- configurações, links, desafios, sessões e pedidos são encerrados, nunca removidos (rastreabilidade)
revoke delete on portal.portal_configuration, portal.portal_route, portal.access_link, portal.access_challenge,
    portal.portal_session, portal.service_request from app_runtime;
-- acordos do portal: somente o primeiro pagamento é registrado depois
revoke update, delete on portal.portal_agreement from app_runtime;
grant update (first_paid_at) on portal.portal_agreement to app_runtime;
