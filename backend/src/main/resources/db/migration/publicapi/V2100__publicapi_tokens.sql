-- =============================================================================
-- Módulo publicapi: API pública de integração dos credores
-- (docs/tecnico/desenho/api-publica.md). Faixa V2100–V2199.
--
-- * integration_token: token de integração de UM credor. Guarda só o HMAC-SHA256
--   do segredo (chave do servidor fora do banco, versionada em hash_key_id); o
--   prefixo público identifica o token. Revogação definitiva (sem DELETE); escopo,
--   credor e prefixo imutáveis; durante a carência da rotação, o hash anterior
--   continua aceito até previous_valid_until.
-- * integration_token_scope / integration_token_allowed_ip: escopos e faixas CIDR.
-- * token_route: tabela GLOBAL (sem RLS, como as rotas de webhook) consultada
--   antes de haver tenant na requisição: só prefixo → (tenant, token). Sem segredo.
-- =============================================================================
create schema if not exists publicapi;
grant usage on schema publicapi to app_runtime;

create table publicapi.integration_token (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    creditor_id            uuid           not null references organization.creditor (id),
    name                   varchar(100)   not null,
    description            varchar(500),
    prefix                 varchar(12)    not null,
    secret_hash            varchar(64)    not null,
    hash_key_id            varchar(16)    not null,
    previous_secret_hash   varchar(64),
    previous_hash_key_id   varchar(16),
    previous_valid_until   timestamptz,
    rate_limit_per_minute  integer        not null,
    expires_at             timestamptz,
    status                 varchar(10)    not null,
    revoked_at             timestamptz,
    revoked_by             uuid,
    revoke_reason          varchar(500),
    rotated_at             timestamptz,
    rotation_count         integer        not null default 0,
    last_used_at           timestamptz,
    last_used_ip           varchar(45),
    constraint uk_integration_token_prefix unique (prefix),
    constraint ck_integration_token_prefix check (prefix ~ '^[a-z2-7]{12}$'),
    constraint ck_integration_token_hash check (secret_hash ~ '^[0-9a-f]{64}$'
        and (previous_secret_hash is null or previous_secret_hash ~ '^[0-9a-f]{64}$')),
    constraint ck_integration_token_status check (status in ('ACTIVE', 'REVOKED')),
    constraint ck_integration_token_revoked check ((status = 'REVOKED') = (revoked_at is not null)
        and (status <> 'REVOKED' or (revoke_reason is not null and previous_secret_hash is null))),
    constraint ck_integration_token_previous check ((previous_secret_hash is null) = (previous_valid_until is null)
        and (previous_secret_hash is null) = (previous_hash_key_id is null)),
    constraint ck_integration_token_rate check (rate_limit_per_minute between 1 and 6000),
    constraint ck_integration_token_rotations check (rotation_count >= 0),
    constraint ck_integration_token_name check (length(btrim(name)) > 0)
);
create index ix_integration_token_creditor on publicapi.integration_token (tenant_id, creditor_id, status);
create index ix_integration_token_created on publicapi.integration_token (tenant_id, created_at desc);
select shared.enable_tenant_rls('publicapi.integration_token');

-- Imutáveis depois da criação: credor, prefixo e criação; revogado não volta a ativo.
create or replace function publicapi.integration_token_guard() returns trigger
    language plpgsql
as $$
begin
    if new.creditor_id is distinct from old.creditor_id or new.prefix is distinct from old.prefix
       or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by
       or new.expires_at is distinct from old.expires_at then
        raise exception 'Credor, prefixo, criação e expiração do token são imutáveis'
            using errcode = 'insufficient_privilege';
    end if;
    if old.status = 'REVOKED' and (new.status <> 'REVOKED' or new.secret_hash is distinct from old.secret_hash
       or new.revoked_at is distinct from old.revoked_at or new.revoke_reason is distinct from old.revoke_reason) then
        raise exception 'Token revogado não pode ser alterado' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_integration_token_guard before update on publicapi.integration_token
    for each row execute function publicapi.integration_token_guard();

create table publicapi.integration_token_scope (
    token_id  uuid         not null references publicapi.integration_token (id),
    scope     varchar(40)  not null,
    primary key (token_id, scope),
    constraint ck_integration_token_scope check (scope in ('PORTFOLIOS_READ', 'PORTFOLIOS_WRITE', 'IMPORTS_READ',
        'IMPORTS_WRITE', 'TITLES_READ', 'TITLES_WRITE', 'DEBTORS_READ', 'DEBTORS_WRITE', 'COLLECTION_READ',
        'AGREEMENTS_READ', 'PAYMENTS_READ', 'PAYMENTS_WRITE', 'DISPUTES_READ', 'DISPUTES_WRITE'))
);
alter table publicapi.integration_token_scope enable row level security;
alter table publicapi.integration_token_scope force row level security;
create policy tenant_isolation on publicapi.integration_token_scope
    using (exists (select 1 from publicapi.integration_token t where t.id = token_id))
    with check (exists (select 1 from publicapi.integration_token t where t.id = token_id));

create table publicapi.integration_token_allowed_ip (
    token_id  uuid         not null references publicapi.integration_token (id),
    cidr      varchar(49)  not null,
    primary key (token_id, cidr),
    -- validado como faixa CIDR pela aplicação; o banco garante a forma
    constraint ck_integration_token_allowed_ip check (cidr ~ '^[0-9a-fA-F:.]+(/[0-9]{1,3})?$')
);
alter table publicapi.integration_token_allowed_ip enable row level security;
alter table publicapi.integration_token_allowed_ip force row level security;
create policy tenant_isolation on publicapi.integration_token_allowed_ip
    using (exists (select 1 from publicapi.integration_token t where t.id = token_id))
    with check (exists (select 1 from publicapi.integration_token t where t.id = token_id));

-- Rota global prefixo → (tenant, token): consultada antes de haver tenant na requisição.
create table publicapi.token_route (
    prefix      varchar(12)  not null primary key,
    tenant_id   uuid         not null references organization.tenant (id),
    token_id    uuid         not null references publicapi.integration_token (id),
    created_at  timestamptz  not null,
    constraint ck_token_route_prefix check (prefix ~ '^[a-z2-7]{12}$')
);

grant select, insert, update on publicapi.integration_token to app_runtime;
grant select, insert, delete on publicapi.integration_token_scope, publicapi.integration_token_allowed_ip to app_runtime;
grant select, insert on publicapi.token_route to app_runtime;
-- tokens não são removidos fisicamente (revogação), nem a rota
revoke delete on publicapi.integration_token, publicapi.token_route from app_runtime;
