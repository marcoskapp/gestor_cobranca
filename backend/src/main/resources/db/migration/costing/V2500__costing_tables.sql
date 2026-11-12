-- =============================================================================
-- Módulo costing — margem e custo por credor e carteira, fase 1
-- (docs/tecnico/desenho/margem-por-carteira.md §10). Faixa V2500–V2599
-- (docs/arquitetura/visao-geral.md §8).
--
-- Cadastros com vigência (nunca apagados, só encerrados; auditoria AuditEntry):
--   cost_band       faixa de custo de pessoas (custo-empresa por hora), por nome;
--   user_cost_band  pessoa → nome da faixa (o valor vem da vigência da faixa no dia);
--   channel_tariff  tarifa por canal de mensagem (por unidade cobrada);
--   fx_rate         câmbio mensal USD→BRL do custo de IA;
--   overhead        custo fixo do mês com a regra de rateio (único que pode ser excluído).
-- Fatos apurados pelo job costing.refresh (reconstruídos por período; sem dado pessoal —
-- nem usuário, nem devedor):
--   cost_daily          custo por dia × credor × carteira × categoria;
--   activity_daily      recuperado, receita da assessoria, acordos quitados e casos-dia;
--   cost_warning_daily  base dos avisos (horas sem faixa, câmbio ausente, tarifa ausente,
--                       custo fixo sem base de rateio);
--   refresh_state       último dia apurado.
-- Credor nulo nos fatos = valor da assessoria sem credor (ociosidade de quem não trabalhou
-- nenhum credor no dia, chamada recebida sem credor, custo fixo sem base): só quem vê todos
-- os credores enxerga essas linhas.
-- =============================================================================
create schema if not exists costing;
grant usage on schema costing to app_runtime;

-- ----------------------------------------------------------------------------- faixas
create table costing.cost_band (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    name         varchar(80)    not null,
    hourly_cost  numeric(12, 2) not null,
    valid_from   date           not null,
    valid_to     date,
    constraint ck_cost_band_cost check (hourly_cost > 0),
    constraint ck_cost_band_period check (valid_to is null or valid_to >= valid_from),
    constraint ck_cost_band_name check (length(trim(name)) > 0),
    constraint ex_cost_band_overlap exclude using gist (
        tenant_id with =, name with =, daterange(valid_from, valid_to, '[]') with &&)
);
create index ix_cost_band_name on costing.cost_band (tenant_id, name, valid_from);
select shared.enable_tenant_rls('costing.cost_band');

create table costing.user_cost_band (
    id           uuid         not null primary key,
    tenant_id    uuid         not null references organization.tenant (id),
    version      bigint       not null,
    created_at   timestamptz  not null,
    created_by   uuid,
    updated_at   timestamptz  not null,
    updated_by   uuid,
    user_id      uuid         not null,
    band_name    varchar(80)  not null,
    valid_from   date         not null,
    valid_to     date,
    constraint ck_user_cost_band_period check (valid_to is null or valid_to >= valid_from),
    constraint ex_user_cost_band_overlap exclude using gist (
        tenant_id with =, user_id with =, daterange(valid_from, valid_to, '[]') with &&)
);
create index ix_user_cost_band_user on costing.user_cost_band (tenant_id, user_id, valid_from);
select shared.enable_tenant_rls('costing.user_cost_band');

-- ----------------------------------------------------------------------------- tarifas
-- unit_cost = R$ por unidade cobrada: conversa de 24 h por categoria (WhatsApp), mensagem (SMS, e-mail).
create table costing.channel_tariff (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    channel      varchar(30)    not null,
    unit_cost    numeric(12, 6) not null,
    valid_from   date           not null,
    valid_to     date,
    constraint ck_channel_tariff_channel check (channel in ('WHATSAPP_UTILITY', 'WHATSAPP_MARKETING',
                                                            'WHATSAPP_AUTHENTICATION', 'SMS', 'EMAIL')),
    constraint ck_channel_tariff_cost check (unit_cost >= 0),
    constraint ck_channel_tariff_period check (valid_to is null or valid_to >= valid_from),
    constraint ex_channel_tariff_overlap exclude using gist (
        tenant_id with =, channel with =, daterange(valid_from, valid_to, '[]') with &&)
);
select shared.enable_tenant_rls('costing.channel_tariff');

-- ----------------------------------------------------------------------------- câmbio
create table costing.fx_rate (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    month        date           not null,
    usd_brl      numeric(12, 6) not null,
    constraint ck_fx_rate_month check (month = date_trunc('month', month)::date),
    constraint ck_fx_rate_value check (usd_brl > 0),
    constraint uk_fx_rate_month unique (tenant_id, month)
);
select shared.enable_tenant_rls('costing.fx_rate');

-- ----------------------------------------------------------------------------- custos fixos
create table costing.overhead (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    month        date           not null,
    description  varchar(200)   not null,
    amount       numeric(19, 2) not null,
    allocation   varchar(20)    not null,
    creditor_id  uuid           references organization.creditor (id),
    constraint ck_overhead_month check (month = date_trunc('month', month)::date),
    constraint ck_overhead_amount check (amount > 0),
    constraint ck_overhead_allocation check (allocation in ('HOURS', 'RECOVERED', 'CASES', 'CREDITOR')),
    constraint ck_overhead_creditor check ((allocation = 'CREDITOR') = (creditor_id is not null)),
    constraint ck_overhead_description check (length(trim(description)) > 0)
);
create index ix_overhead_month on costing.overhead (tenant_id, month);
select shared.enable_tenant_rls('costing.overhead');

-- ----------------------------------------------------------------------------- fatos
create table costing.cost_daily (
    tenant_id     uuid           not null references organization.tenant (id),
    day           date           not null,
    creditor_id   uuid,
    portfolio_id  uuid,
    category      varchar(30)    not null,
    amount        numeric(19, 6) not null,
    quantity      numeric(19, 4) not null,
    unit          varchar(20)    not null,
    basis         varchar(20)    not null,
    constraint ck_cost_daily_category check (category in ('HUMAN_DIRECT', 'HUMAN_IDLE', 'AI', 'TELEPHONY', 'MESSAGING',
                                                          'VARIABLE_COMMISSION', 'LEGAL', 'OVERHEAD')),
    constraint ck_cost_daily_unit check (unit in ('HOURS', 'MINUTES', 'UNITS', 'USD', 'ENTRIES', 'BRL')),
    constraint ck_cost_daily_basis check (basis in ('ESTIMATED')),
    constraint ck_cost_daily_portfolio check (portfolio_id is null or creditor_id is not null)
);
create unique index uk_cost_daily on costing.cost_daily (tenant_id, day,
    coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), category);
create index ix_cost_daily_creditor on costing.cost_daily (tenant_id, creditor_id, day);
select shared.enable_tenant_rls('costing.cost_daily');

create table costing.activity_daily (
    tenant_id           uuid           not null references organization.tenant (id),
    day                 date           not null,
    creditor_id         uuid           not null,
    portfolio_id        uuid,
    recovered           numeric(19, 2) not null,
    recovered_gross     numeric(19, 2) not null,
    revenue             numeric(19, 2) not null,
    settled_agreements  integer        not null,
    worked_cases        integer        not null,
    constraint ck_activity_daily_counts check (settled_agreements >= 0 and worked_cases >= 0 and recovered_gross >= 0)
);
create unique index uk_activity_daily on costing.activity_daily (tenant_id, day, creditor_id,
    coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid));
create index ix_activity_daily_creditor on costing.activity_daily (tenant_id, creditor_id, day);
select shared.enable_tenant_rls('costing.activity_daily');

create table costing.cost_warning_daily (
    tenant_id     uuid           not null references organization.tenant (id),
    day           date           not null,
    creditor_id   uuid,
    portfolio_id  uuid,
    code          varchar(40)    not null,
    quantity      numeric(19, 4) not null,
    constraint ck_cost_warning_code check (code in ('HOURS_WITHOUT_BAND', 'MISSING_FX_RATE', 'NO_FX_RATE',
                                                    'MISSING_TARIFF', 'OVERHEAD_WITHOUT_BASIS'))
);
create index ix_cost_warning_day on costing.cost_warning_daily (tenant_id, day);
select shared.enable_tenant_rls('costing.cost_warning_daily');

-- version = versão das regras de apuração que gravou os fatos (CostRefreshService.RULES_VERSION).
create table costing.refresh_state (
    tenant_id      uuid         not null primary key references organization.tenant (id),
    last_day       date         not null,
    last_from      date         not null,
    refreshed_at   timestamptz  not null,
    duration_ms    bigint       not null,
    row_count      bigint       not null,
    version        integer      not null
);
select shared.enable_tenant_rls('costing.refresh_state');

grant select, insert, update on costing.cost_band, costing.user_cost_band, costing.channel_tariff, costing.fx_rate
    to app_runtime;
grant select, insert, update, delete on costing.overhead to app_runtime;
grant select, insert, delete on costing.cost_daily, costing.activity_daily, costing.cost_warning_daily to app_runtime;
grant select, insert, update on costing.refresh_state to app_runtime;
