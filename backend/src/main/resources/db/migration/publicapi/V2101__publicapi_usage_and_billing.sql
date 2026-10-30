-- =============================================================================
-- Consumo e faturamento da API pública (docs/tecnico/desenho/api-publica.md §6).
--
-- * usage_hourly: consumo agregado por (credor, token, hora, método, rota),
--   gravado por soma em lote (upsert) a partir da acumulação em memória de cada
--   instância — nada é escrito no caminho da requisição. Só soma: contadores
--   nunca diminuem (gatilho). Retenção longa (insumo do faturamento).
-- * api_import_batch: lote de importação criado por token (atribuição assíncrona
--   dos registros processados ao token).
-- * price_plan: tabela de preços versionada (padrão da assessoria ou do credor);
--   versão publicada imutável.
-- * usage_statement: extrato mensal por credor; append-only (nova revisão em vez
--   de alteração).
-- =============================================================================
create table publicapi.usage_hourly (
    tenant_id         uuid          not null references organization.tenant (id),
    creditor_id       uuid          not null references organization.creditor (id),
    token_id          uuid          not null references publicapi.integration_token (id),
    hour_start        timestamptz   not null,
    method            varchar(10)   not null,
    route             varchar(200)  not null,
    requests          bigint        not null,
    throttled         bigint        not null,
    client_errors     bigint        not null,
    server_errors     bigint        not null,
    bytes_in          bigint        not null,
    bytes_out         bigint        not null,
    records           bigint        not null,
    latency_ms_total  bigint        not null,
    updated_at        timestamptz   not null,
    primary key (tenant_id, creditor_id, token_id, hour_start, method, route),
    constraint ck_usage_hourly_hour check (date_trunc('hour', hour_start) = hour_start),
    constraint ck_usage_hourly_counts check (requests >= 0 and throttled >= 0 and client_errors >= 0
        and server_errors >= 0 and bytes_in >= 0 and bytes_out >= 0 and records >= 0 and latency_ms_total >= 0
        and throttled + client_errors + server_errors <= requests)
);
create index ix_usage_hourly_tenant_hour on publicapi.usage_hourly (tenant_id, hour_start);
create index ix_usage_hourly_token_hour on publicapi.usage_hourly (tenant_id, token_id, hour_start);
select shared.enable_tenant_rls('publicapi.usage_hourly');

create or replace function publicapi.usage_hourly_guard() returns trigger
    language plpgsql
as $$
begin
    if new.requests < old.requests or new.throttled < old.throttled or new.client_errors < old.client_errors
       or new.server_errors < old.server_errors or new.bytes_in < old.bytes_in or new.bytes_out < old.bytes_out
       or new.records < old.records or new.latency_ms_total < old.latency_ms_total then
        raise exception 'Consumo da API pública só pode ser somado' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_usage_hourly_guard before update on publicapi.usage_hourly
    for each row execute function publicapi.usage_hourly_guard();

create table publicapi.api_import_batch (
    batch_id     uuid         not null primary key,
    tenant_id    uuid         not null references organization.tenant (id),
    token_id     uuid         not null references publicapi.integration_token (id),
    creditor_id  uuid         not null references organization.creditor (id),
    created_at   timestamptz  not null
);
select shared.enable_tenant_rls('publicapi.api_import_batch');
create trigger trg_api_import_batch_append_only before update or delete on publicapi.api_import_batch
    for each row execute function shared.forbid_mutation();

create table publicapi.price_plan (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    code                varchar(40)     not null,
    plan_version        integer         not null,
    creditor_id         uuid            references organization.creditor (id),
    name                varchar(100)    not null,
    currency            varchar(3)      not null,
    effective_from      date            not null,
    monthly_fee         numeric(19, 2)  not null,
    included_requests   bigint          not null,
    price_per_request   numeric(19, 6)  not null,
    included_mb_in      bigint          not null,
    price_per_mb_in     numeric(19, 6)  not null,
    included_mb_out     bigint          not null,
    price_per_mb_out    numeric(19, 6)  not null,
    included_records    bigint          not null,
    price_per_record    numeric(19, 6)  not null,
    status              varchar(10)     not null,
    published_at        timestamptz,
    published_by        uuid,
    constraint uk_price_plan_version unique (tenant_id, code, plan_version),
    constraint ck_price_plan_code check (code ~ '^[A-Z0-9_-]{2,40}$'),
    constraint ck_price_plan_status check (status in ('DRAFT', 'PUBLISHED')),
    constraint ck_price_plan_published check ((status = 'PUBLISHED') = (published_at is not null)),
    constraint ck_price_plan_effective check (extract(day from effective_from) = 1),
    constraint ck_price_plan_currency check (currency = 'BRL'),
    constraint ck_price_plan_values check (monthly_fee >= 0 and included_requests >= 0 and price_per_request >= 0
        and included_mb_in >= 0 and price_per_mb_in >= 0 and included_mb_out >= 0 and price_per_mb_out >= 0
        and included_records >= 0 and price_per_record >= 0)
);
create index ix_price_plan_applicable on publicapi.price_plan (tenant_id, creditor_id, effective_from desc)
    where status = 'PUBLISHED';
select shared.enable_tenant_rls('publicapi.price_plan');

create or replace function publicapi.price_plan_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if old.status = 'PUBLISHED' then
            raise exception 'Tabela de preços publicada não pode ser removida' using errcode = 'insufficient_privilege';
        end if;
        return old;
    end if;
    if old.status = 'PUBLISHED' then
        raise exception 'Versão publicada da tabela de preços é imutável' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_price_plan_guard before update or delete on publicapi.price_plan
    for each row execute function publicapi.price_plan_guard();

create table publicapi.usage_statement (
    id                   uuid            not null primary key,
    tenant_id            uuid            not null references organization.tenant (id),
    version              bigint          not null,
    created_at           timestamptz     not null,
    created_by           uuid,
    updated_at           timestamptz     not null,
    updated_by           uuid,
    creditor_id          uuid            not null references organization.creditor (id),
    period_start         date            not null,
    revision             integer         not null,
    price_plan_id        uuid            references publicapi.price_plan (id),
    price_plan_code      varchar(40),
    price_plan_version   integer,
    requests             bigint          not null,
    billable_requests    bigint          not null,
    bytes_in             bigint          not null,
    bytes_out            bigint          not null,
    records              bigint          not null,
    total_amount         numeric(19, 2)  not null,
    currency             varchar(3)      not null,
    lines                jsonb           not null,
    breakdown            jsonb           not null,
    usage_hash           varchar(64)     not null,
    closed_at            timestamptz     not null,
    closed_by            uuid,
    reason               varchar(500),
    constraint uk_usage_statement_revision unique (tenant_id, creditor_id, period_start, revision),
    constraint ck_usage_statement_period check (extract(day from period_start) = 1),
    constraint ck_usage_statement_revision check (revision >= 1 and (revision = 1 or reason is not null)),
    constraint ck_usage_statement_values check (requests >= 0 and billable_requests between 0 and requests
        and bytes_in >= 0 and bytes_out >= 0 and records >= 0 and total_amount >= 0),
    constraint ck_usage_statement_hash check (usage_hash ~ '^[0-9a-f]{64}$')
);
create index ix_usage_statement_period on publicapi.usage_statement (tenant_id, period_start desc, creditor_id);
select shared.enable_tenant_rls('publicapi.usage_statement');
create trigger trg_usage_statement_append_only before update or delete on publicapi.usage_statement
    for each row execute function shared.forbid_mutation();

grant select, insert, update on publicapi.usage_hourly to app_runtime;
grant select, insert on publicapi.api_import_batch, publicapi.usage_statement to app_runtime;
grant select, insert, update on publicapi.price_plan to app_runtime;
revoke update, delete on publicapi.usage_statement, publicapi.api_import_batch from app_runtime;
revoke delete on publicapi.usage_hourly, publicapi.price_plan from app_runtime;
