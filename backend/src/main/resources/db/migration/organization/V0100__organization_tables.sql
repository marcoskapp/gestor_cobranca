-- =============================================================================
-- Módulo organization: assessorias (tenants), unidades, credores, contratos e carteiras.
-- =============================================================================

-- Tenant: sem RLS (consultado antes da autenticação para resolver o login)
create table organization.tenant (
    id                    uuid          not null primary key,
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    slug                  varchar(50)   not null,
    legal_name            varchar(200)  not null,
    trade_name            varchar(200)  not null,
    cnpj                  varchar(14)   not null,
    status                varchar(20)   not null,
    platform              boolean       not null default false,
    contact_window_start  time          not null,
    contact_window_end    time          not null,
    contact_on_saturday   boolean       not null,
    contact_on_sunday     boolean       not null,
    constraint uk_tenant_slug unique (slug),
    constraint ck_tenant_status check (status in ('ACTIVE', 'SUSPENDED')),
    constraint ck_tenant_contact_window check (contact_window_start < contact_window_end)
);
-- no máximo um tenant de plataforma
create unique index uk_tenant_platform on organization.tenant (platform) where platform;

create table organization.business_unit (
    id          uuid          not null primary key,
    tenant_id   uuid          not null references organization.tenant (id),
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid,
    updated_at  timestamptz   not null,
    updated_by  uuid,
    code        varchar(30)   not null,
    name        varchar(120)  not null,
    active      boolean       not null,
    constraint uk_business_unit_code unique (tenant_id, code)
);
select shared.enable_tenant_rls('organization.business_unit');

create table organization.creditor (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    legal_name     varchar(200)  not null,
    trade_name     varchar(200)  not null,
    document       varchar(14)   not null,
    external_code  varchar(60),
    contact_email  varchar(200),
    status         varchar(20)   not null,
    constraint uk_creditor_document unique (tenant_id, document),
    constraint ck_creditor_status check (status in ('ACTIVE', 'INACTIVE'))
);
create index ix_creditor_name on organization.creditor (tenant_id, trade_name);
select shared.enable_tenant_rls('organization.creditor');

create table organization.service_contract (
    id                 uuid           not null primary key,
    tenant_id          uuid           not null references organization.tenant (id),
    version            bigint         not null,
    created_at         timestamptz    not null,
    created_by         uuid,
    updated_at         timestamptz    not null,
    updated_by         uuid,
    creditor_id        uuid           not null references organization.creditor (id),
    number             varchar(60)    not null,
    start_date         date           not null,
    end_date           date,
    return_after_days  integer,
    settlement_day     integer        not null,
    notes              varchar(2000),
    status             varchar(20)    not null,
    constraint uk_service_contract_number unique (tenant_id, creditor_id, number),
    constraint ck_service_contract_period check (end_date is null or end_date >= start_date),
    constraint ck_service_contract_settlement_day check (settlement_day between 1 and 28),
    constraint ck_service_contract_status check (status in ('ACTIVE', 'TERMINATED'))
);
create index ix_service_contract_creditor on organization.service_contract (tenant_id, creditor_id);
select shared.enable_tenant_rls('organization.service_contract');

create table organization.portfolio (
    id             uuid           not null primary key,
    tenant_id      uuid           not null references organization.tenant (id),
    version        bigint         not null,
    created_at     timestamptz    not null,
    created_by     uuid,
    updated_at     timestamptz    not null,
    updated_by     uuid,
    creditor_id    uuid           not null references organization.creditor (id),
    contract_id    uuid           not null references organization.service_contract (id),
    code           varchar(40)    not null,
    name           varchar(150)   not null,
    description    varchar(1000),
    received_on    date           not null,
    valid_until    date,
    status         varchar(20)    not null,
    status_reason  varchar(500),
    constraint uk_portfolio_code unique (tenant_id, creditor_id, code),
    constraint ck_portfolio_validity check (valid_until is null or valid_until >= received_on),
    constraint ck_portfolio_status check (status in ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETURNED', 'CLOSED'))
);
create index ix_portfolio_creditor on organization.portfolio (tenant_id, creditor_id, status);
select shared.enable_tenant_rls('organization.portfolio');

grant select, insert, update on organization.tenant to app_runtime;
grant select, insert, update, delete on organization.business_unit, organization.creditor,
    organization.service_contract, organization.portfolio to app_runtime;
-- dados organizacionais não são removidos fisicamente
revoke delete on organization.creditor, organization.service_contract, organization.portfolio from app_runtime;
