-- =============================================================================
-- Módulo identity: usuários, perfis, equipes, sessões, tokens e histórico de senhas.
-- Tabelas de coleção (perfis do usuário, escopo, membros) são protegidas por RLS
-- através da linha pai, que já é filtrada por tenant.
-- =============================================================================

create table identity.role (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    code         varchar(40)   not null,
    name         varchar(100)  not null,
    description  varchar(500),
    system_role  boolean       not null,
    active       boolean       not null,
    constraint uk_role_code unique (tenant_id, code)
);
select shared.enable_tenant_rls('identity.role');

create table identity.role_permission (
    role_id     uuid         not null references identity.role (id) on delete cascade,
    permission  varchar(60)  not null,
    primary key (role_id, permission)
);
alter table identity.role_permission enable row level security;
alter table identity.role_permission force row level security;
create policy tenant_isolation on identity.role_permission
    using (exists (select 1 from identity.role r where r.id = role_id))
    with check (exists (select 1 from identity.role r where r.id = role_id));

create table identity.app_user (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    name                  varchar(150)  not null,
    email                 varchar(200)  not null,
    password_hash         varchar(255),
    status                varchar(30)   not null,
    failed_attempts       integer       not null default 0,
    locked_until          timestamptz,
    lockout_count         integer       not null default 0,
    mfa_secret_encrypted  varchar(255),
    mfa_enabled           boolean       not null default false,
    mfa_enrolled_at       timestamptz,
    mfa_last_step         bigint,
    password_changed_at   timestamptz,
    must_change_password  boolean       not null default false,
    last_login_at         timestamptz,
    business_unit_id      uuid          references organization.business_unit (id),
    all_creditors         boolean       not null default false,
    constraint uk_app_user_email unique (tenant_id, email),
    constraint ck_app_user_status check (status in ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED'))
);
create index ix_app_user_name on identity.app_user (tenant_id, name);
select shared.enable_tenant_rls('identity.app_user');

create table identity.user_role (
    user_id  uuid not null references identity.app_user (id) on delete cascade,
    role_id  uuid not null references identity.role (id),
    primary key (user_id, role_id)
);
create index ix_user_role_role on identity.user_role (role_id);
alter table identity.user_role enable row level security;
alter table identity.user_role force row level security;
create policy tenant_isolation on identity.user_role
    using (exists (select 1 from identity.app_user u where u.id = user_id))
    with check (exists (select 1 from identity.app_user u where u.id = user_id));

create table identity.user_creditor_scope (
    user_id      uuid not null references identity.app_user (id) on delete cascade,
    creditor_id  uuid not null references organization.creditor (id),
    primary key (user_id, creditor_id)
);
alter table identity.user_creditor_scope enable row level security;
alter table identity.user_creditor_scope force row level security;
create policy tenant_isolation on identity.user_creditor_scope
    using (exists (select 1 from identity.app_user u where u.id = user_id))
    with check (exists (select 1 from identity.app_user u where u.id = user_id));

create table identity.team (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    name              varchar(120)  not null,
    business_unit_id  uuid          references organization.business_unit (id),
    supervisor_id     uuid          references identity.app_user (id),
    active            boolean       not null
);
select shared.enable_tenant_rls('identity.team');

create table identity.team_member (
    team_id  uuid not null references identity.team (id) on delete cascade,
    user_id  uuid not null references identity.app_user (id),
    primary key (team_id, user_id)
);
create index ix_team_member_user on identity.team_member (user_id);
alter table identity.team_member enable row level security;
alter table identity.team_member force row level security;
create policy tenant_isolation on identity.team_member
    using (exists (select 1 from identity.team t where t.id = team_id))
    with check (exists (select 1 from identity.team t where t.id = team_id));

create table identity.auth_session (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    user_id              uuid          not null references identity.app_user (id),
    status               varchar(20)   not null,
    mfa_verified         boolean       not null,
    started_at           timestamptz   not null,
    last_seen_at         timestamptz   not null,
    absolute_expires_at  timestamptz   not null,
    client_ip            varchar(64),
    user_agent           varchar(512),
    ended_at             timestamptz,
    end_reason           varchar(60),
    constraint ck_auth_session_status check (status in ('ACTIVE', 'REVOKED', 'EXPIRED'))
);
create index ix_auth_session_user on identity.auth_session (tenant_id, user_id, status);
create index ix_auth_session_active on identity.auth_session (last_seen_at) where status = 'ACTIVE';
select shared.enable_tenant_rls('identity.auth_session');

create table identity.refresh_token (
    id          uuid          not null primary key,
    tenant_id   uuid          not null references organization.tenant (id),
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid,
    updated_at  timestamptz   not null,
    updated_by  uuid,
    session_id  uuid          not null references identity.auth_session (id),
    token_hash  char(64)      not null,
    status      varchar(20)   not null,
    expires_at  timestamptz   not null,
    used_at     timestamptz,
    constraint uk_refresh_token_hash unique (token_hash),
    constraint ck_refresh_token_status check (status in ('ACTIVE', 'USED', 'REVOKED'))
);
create index ix_refresh_token_session on identity.refresh_token (session_id, status);
select shared.enable_tenant_rls('identity.refresh_token');

create table identity.action_token (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    user_id      uuid          not null references identity.app_user (id),
    purpose      varchar(30)   not null,
    token_hash   char(64)      not null,
    expires_at   timestamptz   not null,
    used_at      timestamptz,
    invalidated  boolean       not null default false,
    constraint uk_action_token_hash unique (token_hash),
    constraint ck_action_token_purpose check (purpose in ('ACTIVATION', 'PASSWORD_RESET'))
);
create index ix_action_token_user on identity.action_token (user_id, purpose);
select shared.enable_tenant_rls('identity.action_token');

create table identity.password_history (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    user_id        uuid          not null references identity.app_user (id),
    password_hash  varchar(255)  not null
);
create index ix_password_history_user on identity.password_history (user_id, created_at desc);
select shared.enable_tenant_rls('identity.password_history');

grant select, insert, update, delete on all tables in schema identity to app_runtime;
-- histórico de senhas não é alterado
revoke update, delete on identity.password_history from app_runtime;
