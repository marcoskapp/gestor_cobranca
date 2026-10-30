-- =============================================================================
-- Consumo e faturamento da API pública (docs/tecnico/desenho/api-publica.md §6).
--
-- * usage_hourly: consumo agregado por (credor, token, hora, método, rota),
--   gravado por soma em lote (upsert) a partir da acumulação em memória de cada
--   instância — nada é escrito no caminho da requisição. Só soma: contadores
--   nunca diminuem (gatilho). Retenção longa (insumo do faturamento).
-- * api_import_batch: lote de importação criado por token (atribuição assíncrona
--   dos registros processados ao token).
-- * usage_statement: extrato por ciclo de consumo do credor; append-only (nova
--   revisão em vez de alteração). Os preços e o dia de fechamento do ciclo vêm
--   das condições de cobrança das integrações do contrato do credor
--   (organization.contract_integration_pricing, V2101) — o módulo não mantém
--   tabela de preços própria (decisão 608).
--
-- Cobrança (decisão 607): só requisições autenticadas com resposta 2xx/3xx; o
-- tráfego cobrável (billable_bytes_*) é o dessas requisições. A medição técnica
-- fica em bytes; o extrato trabalha em MB inteiros (1 MB = 1.048.576 bytes,
-- arredondado para cima no total do ciclo por token e direção).
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
    billable_bytes_in   bigint      not null,
    billable_bytes_out  bigint      not null,
    records           bigint        not null,
    latency_ms_total  bigint        not null,
    updated_at        timestamptz   not null,
    primary key (tenant_id, creditor_id, token_id, hour_start, method, route),
    constraint ck_usage_hourly_hour check (date_trunc('hour', hour_start) = hour_start),
    constraint ck_usage_hourly_counts check (requests >= 0 and throttled >= 0 and client_errors >= 0
        and server_errors >= 0 and bytes_in >= 0 and bytes_out >= 0 and records >= 0 and latency_ms_total >= 0
        and throttled + client_errors + server_errors <= requests),
    constraint ck_usage_hourly_billable_bytes check (billable_bytes_in between 0 and bytes_in
        and billable_bytes_out between 0 and bytes_out)
);
create index ix_usage_hourly_tenant_hour on publicapi.usage_hourly (tenant_id, hour_start);
create index ix_usage_hourly_token_hour on publicapi.usage_hourly (tenant_id, token_id, hour_start);
create index ix_usage_hourly_creditor_hour on publicapi.usage_hourly (tenant_id, creditor_id, hour_start);
select shared.enable_tenant_rls('publicapi.usage_hourly');

create or replace function publicapi.usage_hourly_guard() returns trigger
    language plpgsql
as $$
begin
    if new.requests < old.requests or new.throttled < old.throttled or new.client_errors < old.client_errors
       or new.server_errors < old.server_errors or new.bytes_in < old.bytes_in or new.bytes_out < old.bytes_out
       or new.billable_bytes_in < old.billable_bytes_in or new.billable_bytes_out < old.billable_bytes_out
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
    period_end           date            not null,
    revision             integer         not null,
    usage_closing_day    integer         not null,
    contract_id          uuid            references organization.service_contract (id),
    contract_number      varchar(60),
    pricing_id           uuid            references organization.contract_integration_pricing (id),
    pricing_version      integer,
    requests             bigint          not null,
    billable_requests    bigint          not null,
    billable_mb_in       bigint          not null,
    billable_mb_out      bigint          not null,
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
    constraint ck_usage_statement_period check (period_end >= period_start),
    constraint ck_usage_statement_closing_day check (usage_closing_day between 1 and 31),
    constraint ck_usage_statement_pricing check ((pricing_id is null) = (pricing_version is null)
        and (pricing_id is null or contract_id is not null)),
    constraint ck_usage_statement_revision check (revision >= 1 and (revision = 1 or reason is not null)),
    constraint ck_usage_statement_values check (requests >= 0 and billable_requests between 0 and requests
        and billable_mb_in >= 0 and billable_mb_out >= 0 and records >= 0 and total_amount >= 0),
    constraint ck_usage_statement_hash check (usage_hash ~ '^[0-9a-f]{64}$')
);
create index ix_usage_statement_creditor_period on publicapi.usage_statement (tenant_id, creditor_id, period_end desc);
create index ix_usage_statement_period_end on publicapi.usage_statement (tenant_id, period_end desc);
select shared.enable_tenant_rls('publicapi.usage_statement');
create trigger trg_usage_statement_append_only before update or delete on publicapi.usage_statement
    for each row execute function shared.forbid_mutation();

grant select, insert, update on publicapi.usage_hourly to app_runtime;
grant select, insert on publicapi.api_import_batch, publicapi.usage_statement to app_runtime;
revoke update, delete on publicapi.usage_statement, publicapi.api_import_batch from app_runtime;
revoke delete on publicapi.usage_hourly from app_runtime;
