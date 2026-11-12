-- =============================================================================
-- Módulo costing — fase 2: conciliação do custo de canal com a fatura real
-- (docs/tecnico/desenho/margem-por-carteira.md §3.5 e §13). Faixa V2500–V2599.
--
-- 1. cost_daily.item: item de custo de canal faturado (TELEPHONY, WHATSAPP, SMS, EMAIL, AI). A categoria MESSAGING
--    passa a ter uma linha por item (WhatsApp, SMS, e-mail), base do rateio do ajuste de cada fatura.
--    Fatos antigos: TELEPHONY e AI recebem o item da própria categoria; MESSAGING fica com item nulo até a próxima
--    apuração do dia (o job diário refaz os últimos 35 dias; meses mais antigos pedem "Reapurar o período" antes da
--    fatura de mensagens — a aplicação recusa com 409 costing.invoice_month_not_itemized).
-- 2. cost_daily.basis ganha INVOICE_ADJUSTMENT: o ajuste pela fatura (fatura − estimativa do item no mês, rateado
--    pela estimativa) é um fato separado; a estimativa (ESTIMATED) nunca é alterada pela fatura.
-- 3. costing.channel_invoice: fatura do mês por item, em R$ (a de IA convertida pela gestão), uma por mês × item.
-- 4. Aviso novo INVOICE_WITHOUT_USAGE (fatura de item sem uso no mês: valor sem credor).
-- Sem dado pessoal em nenhuma tabela.
-- =============================================================================

-- ----------------------------------------------------------------------------- item e base dos fatos
alter table costing.cost_daily add column item varchar(20);

-- cost_daily tem RLS forçada: sem app.tenant_id o update não enxerga nenhuma linha. O preenchimento percorre as
-- assessorias com o tenant da transação, como a aplicação faria (mesmo padrão da decisão 849).
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update costing.cost_daily set item = category where category in ('TELEPHONY', 'AI') and item is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

alter table costing.cost_daily drop constraint ck_cost_daily_basis;
alter table costing.cost_daily add constraint ck_cost_daily_basis
    check (basis in ('ESTIMATED', 'INVOICE_ADJUSTMENT'));
-- coalesce: comparação com item nulo daria NULL, e um check com resultado NULL passa
alter table costing.cost_daily add constraint ck_cost_daily_item check (
    case category
        when 'TELEPHONY' then coalesce(item = 'TELEPHONY', false)
        when 'AI' then coalesce(item = 'AI', false)
        when 'MESSAGING' then coalesce(item in ('WHATSAPP', 'SMS', 'EMAIL'), false)
                              or (item is null and basis = 'ESTIMATED')
        else item is null and basis = 'ESTIMATED'
    end);

drop index costing.uk_cost_daily;
create unique index uk_cost_daily on costing.cost_daily (tenant_id, day,
    coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), category, coalesce(item, ''), basis);

-- ----------------------------------------------------------------------------- aviso novo
alter table costing.cost_warning_daily drop constraint ck_cost_warning_code;
alter table costing.cost_warning_daily add constraint ck_cost_warning_code
    check (code in ('HOURS_WITHOUT_BAND', 'MISSING_FX_RATE', 'NO_FX_RATE', 'MISSING_TARIFF', 'OVERHEAD_WITHOUT_BASIS',
                    'INVOICE_WITHOUT_USAGE'));

-- ----------------------------------------------------------------------------- faturas de canal
create table costing.channel_invoice (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    month        date           not null,
    item         varchar(20)    not null,
    amount       numeric(19, 2) not null,
    supplier     varchar(120),
    note         varchar(500),
    constraint ck_channel_invoice_month check (month = date_trunc('month', month)::date),
    constraint ck_channel_invoice_item check (item in ('TELEPHONY', 'WHATSAPP', 'SMS', 'EMAIL', 'AI')),
    constraint ck_channel_invoice_amount check (amount >= 0),
    constraint ck_channel_invoice_supplier check (supplier is null or length(trim(supplier)) > 0),
    constraint uk_channel_invoice unique (tenant_id, month, item)
);
select shared.enable_tenant_rls('costing.channel_invoice');

grant select, insert, update, delete on costing.channel_invoice to app_runtime;
