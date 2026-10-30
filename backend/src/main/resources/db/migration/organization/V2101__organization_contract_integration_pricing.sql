-- =============================================================================
-- Condições de cobrança das integrações (API pública) no contrato de prestação de
-- serviço do credor (docs/tecnico/desenho/api-publica.md §6, decisão 608).
--
-- Uma linha por versão, gravada uma única vez (append-only): dia de fechamento do
-- ciclo de consumo (1–31; meses sem o dia fecham no último dia), mensalidade,
-- franquias e preços do excedente por chamada, MB recebido, MB enviado e registro
-- (MB = 1.048.576 bytes). A versão vale para os ciclos que começam depois de
-- registered_at (não retroatividade). O faturamento (módulo publicapi) lê as
-- condições pela interface usecases do módulo organization.
-- =============================================================================
create table organization.contract_integration_pricing (
    id                 uuid            not null primary key,
    tenant_id          uuid            not null references organization.tenant (id),
    version            bigint          not null,
    created_at         timestamptz     not null,
    created_by         uuid,
    updated_at         timestamptz     not null,
    updated_by         uuid,
    contract_id        uuid            not null references organization.service_contract (id),
    creditor_id        uuid            not null references organization.creditor (id),
    pricing_version    integer         not null,
    usage_closing_day  integer         not null,
    monthly_fee        numeric(19, 2)  not null,
    included_requests  bigint          not null,
    price_per_request  numeric(19, 6)  not null,
    included_mb_in     bigint          not null,
    price_per_mb_in    numeric(19, 6)  not null,
    included_mb_out    bigint          not null,
    price_per_mb_out   numeric(19, 6)  not null,
    included_records   bigint          not null,
    price_per_record   numeric(19, 6)  not null,
    registered_at      timestamptz     not null,
    registered_by      uuid,
    reason             varchar(500),
    constraint uk_contract_integration_pricing_version unique (tenant_id, contract_id, pricing_version),
    constraint ck_contract_integration_pricing_version check (pricing_version >= 1),
    constraint ck_contract_integration_pricing_closing_day check (usage_closing_day between 1 and 31),
    constraint ck_contract_integration_pricing_values check (monthly_fee >= 0 and included_requests >= 0
        and price_per_request >= 0 and included_mb_in >= 0 and price_per_mb_in >= 0 and included_mb_out >= 0
        and price_per_mb_out >= 0 and included_records >= 0 and price_per_record >= 0)
);
create index ix_contract_integration_pricing_applicable on organization.contract_integration_pricing
    (tenant_id, contract_id, registered_at desc, pricing_version desc);
create index ix_contract_integration_pricing_creditor on organization.contract_integration_pricing (tenant_id, creditor_id);
select shared.enable_tenant_rls('organization.contract_integration_pricing');
create trigger trg_contract_integration_pricing_append_only before update or delete
    on organization.contract_integration_pricing
    for each row execute function shared.forbid_mutation();

grant select, insert on organization.contract_integration_pricing to app_runtime;
revoke update, delete on organization.contract_integration_pricing from app_runtime;
