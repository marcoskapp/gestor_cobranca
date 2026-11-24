-- Portal do credor (ADR 0011, docs/tecnico/desenho/portal-credor.md): identidade da pessoa do credor, separada do
-- usuário da assessoria (tabelas próprias; nenhuma FK para app_user/auth_session). Todas com RLS por tenant.

-- portal liberado por credor: sem liberação, nenhum convite e nenhuma sessão do credor
create table identity.creditor_portal_access (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    creditor_id  uuid          not null references organization.creditor (id),
    enabled      boolean       not null,
    enabled_at   timestamptz,
    enabled_by   uuid,
    disabled_at  timestamptz,
    disabled_by  uuid,
    constraint uk_creditor_portal_access unique (tenant_id, creditor_id)
);
select shared.enable_tenant_rls('identity.creditor_portal_access');

-- pessoa do credor: uma conta por credor (o e-mail é único na assessoria, como o do usuário interno)
create table identity.creditor_user (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    creditor_id           uuid          not null references organization.creditor (id),
    name                  varchar(150)  not null,
    email                 varchar(200)  not null,
    role                  varchar(20)   not null,
    status                varchar(20)   not null,
    password_hash         varchar(255),
    password_changed_at   timestamptz,
    mfa_secret_encrypted  varchar(255),
    mfa_enrolled_at       timestamptz,
    mfa_last_step         bigint,
    failed_attempts       integer       not null default 0,
    locked_until          timestamptz,
    lockout_count         integer       not null default 0,
    last_login_at         timestamptz,
    invited_by            uuid          not null,
    invited_at            timestamptz   not null,
    revoked_at            timestamptz,
    revoked_by            uuid,
    revoke_reason         varchar(200),
    constraint uk_creditor_user_email unique (tenant_id, email),
    constraint ck_creditor_user_role check (role in ('GESTOR', 'FINANCEIRO', 'JURIDICO', 'COMPLIANCE', 'LEITURA')),
    constraint ck_creditor_user_status check (status in ('INVITED', 'ACTIVE', 'REVOKED')),
    constraint ck_creditor_user_counts check (failed_attempts >= 0 and lockout_count >= 0),
    -- conta ativa sempre com senha e MFA cadastrado (o MFA do credor é obrigatório, nunca por perfil)
    constraint ck_creditor_user_active_credentials check (
        status <> 'ACTIVE' or (password_hash is not null and mfa_secret_encrypted is not null and mfa_enrolled_at is not null))
);
create index ix_creditor_user_creditor on identity.creditor_user (tenant_id, creditor_id, status);
select shared.enable_tenant_rls('identity.creditor_user');

-- convite (ativação: senha + MFA numa transação) e redefinição de senha: só o hash, uso único
create table identity.creditor_action_token (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    user_id      uuid          not null references identity.creditor_user (id),
    purpose      varchar(20)   not null,
    token_hash   varchar(64)   not null,
    expires_at   timestamptz   not null,
    used_at      timestamptz,
    invalidated  boolean       not null default false,
    constraint uk_creditor_action_token_hash unique (token_hash),
    constraint ck_creditor_action_token_purpose check (purpose in ('INVITATION', 'PASSWORD_RESET'))
);
create index ix_creditor_action_token_user on identity.creditor_action_token (user_id, purpose);
select shared.enable_tenant_rls('identity.creditor_action_token');

create table identity.creditor_session (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    user_id              uuid          not null references identity.creditor_user (id),
    creditor_id          uuid          not null references organization.creditor (id),
    status               varchar(20)   not null,
    started_at           timestamptz   not null,
    last_seen_at         timestamptz   not null,
    absolute_expires_at  timestamptz   not null,
    client_ip            varchar(64),
    user_agent           varchar(512),
    ended_at             timestamptz,
    end_reason           varchar(60),
    constraint ck_creditor_session_status check (status in ('ACTIVE', 'REVOKED', 'EXPIRED'))
);
create index ix_creditor_session_user on identity.creditor_session (tenant_id, user_id, status);
create index ix_creditor_session_active on identity.creditor_session (last_seen_at) where status = 'ACTIVE';
create index ix_creditor_session_creditor on identity.creditor_session (tenant_id, creditor_id, status);
select shared.enable_tenant_rls('identity.creditor_session');

create table identity.creditor_refresh_token (
    id          uuid          not null primary key,
    tenant_id   uuid          not null references organization.tenant (id),
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid,
    updated_at  timestamptz   not null,
    updated_by  uuid,
    session_id  uuid          not null references identity.creditor_session (id),
    token_hash  varchar(64)   not null,
    status      varchar(20)   not null,
    expires_at  timestamptz   not null,
    used_at     timestamptz,
    constraint uk_creditor_refresh_token_hash unique (token_hash),
    constraint ck_creditor_refresh_token_status check (status in ('ACTIVE', 'USED', 'REVOKED'))
);
create index ix_creditor_refresh_token_session on identity.creditor_refresh_token (session_id, status);
select shared.enable_tenant_rls('identity.creditor_refresh_token');

create table identity.creditor_password_history (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    user_id        uuid          not null references identity.creditor_user (id),
    password_hash  varchar(255)  not null
);
create index ix_creditor_password_history_user on identity.creditor_password_history (user_id, created_at desc);
select shared.enable_tenant_rls('identity.creditor_password_history');

-- limite persistido do login do credor por IP (HMAC; o banco nunca tem o IP em claro), entre instâncias
create table identity.creditor_login_throttle (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    key_type           varchar(20)   not null,
    key_hash           varchar(128)  not null,
    window_started_at  timestamptz,
    failures           integer       not null default 0,
    requests           integer       not null default 0,
    lock_count         integer       not null default 0,
    locked_until       timestamptz,
    updated_at         timestamptz   not null,
    constraint uk_creditor_login_throttle_key unique (tenant_id, key_type, key_hash),
    constraint ck_creditor_login_throttle_type check (key_type in ('IP')),
    constraint ck_creditor_login_throttle_counts check (failures >= 0 and requests >= 0 and lock_count >= 0)
);
create index ix_creditor_login_throttle_idle on identity.creditor_login_throttle (tenant_id, updated_at);
select shared.enable_tenant_rls('identity.creditor_login_throttle');

grant select, insert, update, delete on identity.creditor_portal_access, identity.creditor_user, identity.creditor_action_token,
    identity.creditor_session, identity.creditor_refresh_token, identity.creditor_password_history,
    identity.creditor_login_throttle to app_runtime;
