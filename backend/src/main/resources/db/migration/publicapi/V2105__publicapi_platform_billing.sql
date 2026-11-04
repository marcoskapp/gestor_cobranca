-- =============================================================================
-- Faturamento da plataforma à assessoria pelo consumo da API pública
-- (docs/negocio/api-publica.md §9, docs/tecnico/desenho/api-publica.md §11).
--
-- Segunda camada de cobrança, independente das condições que a assessoria
-- cobra de cada credor (organization.contract_integration_pricing): a
-- plataforma cobra da assessoria pelo consumo somado de TODOS os tokens dela
-- (inclusive revogados ou expirados), de todos os credores, lido da mesma
-- medição (publicapi.usage_hourly) — sem medição nova.
--
-- As três tabelas ficam no tenant da assessoria cobrada (RLS): a assessoria lê
-- a própria política e os próprios extratos; a equipe da plataforma grava e lê
-- trocando o tenant da transação (shared.tenancy.TenantSwitch), o mesmo
-- mecanismo do provisionamento — não há leitura entre tenants sem RLS.
--
-- * platform_billing_policy: vigência (uma por assessoria).
-- * platform_billing_policy_version: dia de fechamento, mensalidade, franquias,
--   preço do excedente por chamada (único ou por faixas de volume, em
--   request_tiers), MB recebido, MB enviado e registro. Append-only; vale para
--   os ciclos que começam depois do registro, e a primeira versão vale já no
--   ciclo em andamento (decisão 740).
-- * platform_statement: extrato por ciclo, append-only, revisões com motivo.
-- =============================================================================
create table publicapi.platform_billing_policy (
    id          uuid         not null primary key,
    tenant_id   uuid         not null references organization.tenant (id),
    version     bigint       not null,
    created_at  timestamptz  not null,
    created_by  uuid,
    updated_at  timestamptz  not null,
    updated_by  uuid,
    start_date  date         not null,
    end_date    date,
    constraint uk_platform_billing_policy_tenant unique (tenant_id),
    constraint ck_platform_billing_policy_term check (end_date is null or end_date >= start_date)
);
select shared.enable_tenant_rls('publicapi.platform_billing_policy');

create table publicapi.platform_billing_policy_version (
    id                 uuid            not null primary key,
    tenant_id          uuid            not null references organization.tenant (id),
    version            bigint          not null,
    created_at         timestamptz     not null,
    created_by         uuid,
    updated_at         timestamptz     not null,
    updated_by         uuid,
    policy_id          uuid            not null references publicapi.platform_billing_policy (id),
    policy_version     integer         not null,
    closing_day        integer         not null,
    monthly_fee        numeric(19, 2)  not null,
    included_requests  bigint          not null,
    price_per_request  numeric(19, 6),
    request_tiers      jsonb           not null,
    included_mb_in     bigint          not null,
    price_per_mb_in    numeric(19, 6)  not null,
    included_mb_out    bigint          not null,
    price_per_mb_out   numeric(19, 6)  not null,
    included_records   bigint          not null,
    price_per_record   numeric(19, 6)  not null,
    registered_at      timestamptz     not null,
    registered_by      uuid,
    reason             varchar(500),
    constraint uk_platform_billing_policy_version unique (tenant_id, policy_id, policy_version),
    constraint ck_platform_billing_policy_version_number check (policy_version >= 1),
    constraint ck_platform_billing_policy_version_closing_day check (closing_day between 1 and 31),
    constraint ck_platform_billing_policy_version_tiers check (jsonb_typeof(request_tiers) = 'array'
        and jsonb_array_length(request_tiers) <= 10
        and (price_per_request is null) = (jsonb_array_length(request_tiers) > 0)),
    constraint ck_platform_billing_policy_version_values check (monthly_fee >= 0 and included_requests >= 0
        and (price_per_request is null or price_per_request >= 0) and included_mb_in >= 0 and price_per_mb_in >= 0
        and included_mb_out >= 0 and price_per_mb_out >= 0 and included_records >= 0 and price_per_record >= 0)
);
create index ix_platform_billing_policy_version_applicable on publicapi.platform_billing_policy_version
    (tenant_id, policy_id, registered_at desc, policy_version desc);
select shared.enable_tenant_rls('publicapi.platform_billing_policy_version');
create trigger trg_platform_billing_policy_version_append_only before update or delete
    on publicapi.platform_billing_policy_version
    for each row execute function shared.forbid_mutation();

create table publicapi.platform_statement (
    id                    uuid            not null primary key,
    tenant_id             uuid            not null references organization.tenant (id),
    version               bigint          not null,
    created_at            timestamptz     not null,
    created_by            uuid,
    updated_at            timestamptz     not null,
    updated_by            uuid,
    period_start          date            not null,
    period_end            date            not null,
    revision              integer         not null,
    closing_day           integer         not null,
    cycle_days            integer         not null,
    reference_cycle_days  integer         not null,
    cycle_kind            varchar(20)     not null,
    policy_id             uuid            not null references publicapi.platform_billing_policy (id),
    policy_version_id     uuid            not null references publicapi.platform_billing_policy_version (id),
    policy_version        integer         not null,
    requests              bigint          not null,
    billable_requests     bigint          not null,
    billable_mb_in        bigint          not null,
    billable_mb_out       bigint          not null,
    records               bigint          not null,
    creditors             integer         not null,
    tokens                integer         not null,
    total_amount          numeric(19, 2)  not null,
    currency              varchar(3)      not null,
    lines                 jsonb           not null,
    breakdown             jsonb           not null,
    usage_hash            varchar(64)     not null,
    closed_at             timestamptz     not null,
    closed_by             uuid,
    reason                varchar(500),
    constraint uk_platform_statement_revision unique (tenant_id, period_start, revision),
    constraint ck_platform_statement_period check (period_end >= period_start),
    constraint ck_platform_statement_closing_day check (closing_day between 1 and 31),
    constraint ck_platform_statement_days check (cycle_days >= 1 and reference_cycle_days >= 1),
    constraint ck_platform_statement_kind check (cycle_kind in ('REGULAR', 'POLICY_START', 'POLICY_END',
        'TRANSITION')),
    constraint ck_platform_statement_revision check (revision >= 1 and (revision = 1 or reason is not null)),
    constraint ck_platform_statement_values check (requests >= 0 and billable_requests between 0 and requests
        and billable_mb_in >= 0 and billable_mb_out >= 0 and records >= 0 and creditors >= 0 and tokens >= 0
        and total_amount >= 0 and currency = 'BRL'),
    constraint ck_platform_statement_hash check (usage_hash ~ '^[0-9a-f]{64}$')
);
create index ix_platform_statement_period_end on publicapi.platform_statement (tenant_id, period_end desc, revision desc);
select shared.enable_tenant_rls('publicapi.platform_statement');
create trigger trg_platform_statement_append_only before update or delete on publicapi.platform_statement
    for each row execute function shared.forbid_mutation();

grant select, insert, update on publicapi.platform_billing_policy to app_runtime;
revoke delete on publicapi.platform_billing_policy from app_runtime;
grant select, insert on publicapi.platform_billing_policy_version, publicapi.platform_statement to app_runtime;
revoke update, delete on publicapi.platform_billing_policy_version, publicapi.platform_statement from app_runtime;
