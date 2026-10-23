-- =============================================================================
-- incentive: razão de comissão (append-only), períodos de apuração, extratos,
-- contestações, ajustes e exportações para a folha.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Períodos de apuração (mensais). Fechado = extratos gerados; não reabre.
-- -----------------------------------------------------------------------------
create table incentive.commission_period (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    period_start      date          not null,
    period_end        date          not null,
    status            varchar(20)   not null,
    closed_at         timestamptz,
    closed_by         uuid,
    close_trigger     varchar(20),
    statements_count  integer,
    constraint uk_commission_period unique (tenant_id, period_start),
    constraint ck_commission_period_status check (status in ('OPEN', 'CLOSED')),
    constraint ck_commission_period_range check (extract(day from period_start) = 1 and period_end >= period_start),
    constraint ck_commission_period_closed check ((status = 'CLOSED') = (closed_at is not null)),
    constraint ck_commission_period_trigger check (close_trigger in ('SCHEDULED', 'MANUAL'))
);
select shared.enable_tenant_rls('incentive.commission_period');

-- -----------------------------------------------------------------------------
-- Razão de comissão. Cada linha é um crédito de recuperação (base × peso) a um
-- beneficiário (usuário ou equipe/pool) originado de um pagamento confirmado
-- (ACCRUAL) ou do seu estorno (REVERSAL, compensatório, valores negativos).
-- Conteúdo financeiro imutável; somente o ciclo PENDING → CLEARED/WAIVED, o
-- período de competência e o extrato podem ser preenchidos (gatilho + privilégio
-- por coluna).
-- -----------------------------------------------------------------------------
create table incentive.commission_ledger_entry (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    kind                  varchar(10)    not null,
    status                varchar(10)    not null,
    beneficiary_type      varchar(10)    not null,
    beneficiary_id        uuid           not null,
    role                  varchar(20),
    attribution_id        uuid           not null references incentive.agreement_attribution (id),
    agreement_id          uuid           not null,
    payment_id            uuid           not null,
    reversal_id           uuid,
    reverses_entry_id     uuid           references incentive.commission_ledger_entry (id),
    creditor_id           uuid           not null,
    portfolio_id          uuid,
    delay_bucket          varchar(20)    not null,
    origin                varchar(40)    not null,
    channel               varchar(20)    not null,
    base_amount           numeric(19, 2) not null,
    credit_weight         numeric(9, 4)  not null,
    renegotiation_factor  numeric(9, 4)  not null,
    credited_amount       numeric(19, 2) not null,
    credit_note           varchar(60)    not null,
    plan_id               uuid           references incentive.commission_plan (id),
    paid_on               date           not null,
    confirmed_on          date           not null,
    clears_on             date           not null,
    cleared_at            timestamptz,
    period_start          date,
    statement_id          uuid,
    constraint ck_ledger_kind check (kind in ('ACCRUAL', 'REVERSAL')),
    constraint ck_ledger_status check (status in ('PENDING', 'CLEARED', 'WAIVED')),
    constraint ck_ledger_beneficiary check (beneficiary_type in ('USER', 'TEAM')),
    constraint ck_ledger_bucket check (delay_bucket in ('D0_30', 'D31_90', 'D91_180', 'D181_360', 'D361_PLUS',
                                                        'UNKNOWN')),
    constraint ck_ledger_sign check ((kind = 'ACCRUAL' and base_amount >= 0 and credited_amount >= 0
                                      and reversal_id is null and reverses_entry_id is null)
                                     or (kind = 'REVERSAL' and base_amount <= 0 and credited_amount <= 0
                                         and reversal_id is not null and reverses_entry_id is not null)),
    constraint ck_ledger_cleared check ((status = 'CLEARED') = (cleared_at is not null and period_start is not null)),
    constraint ck_ledger_statement check (statement_id is null or status = 'CLEARED'),
    constraint ck_ledger_weights check (credit_weight between 0 and 100 and renegotiation_factor between 0 and 100)
);
create unique index uk_ledger_accrual on incentive.commission_ledger_entry
    (tenant_id, payment_id, attribution_id, beneficiary_type, beneficiary_id, role) nulls not distinct
    where kind = 'ACCRUAL';
create unique index uk_ledger_reversal on incentive.commission_ledger_entry (tenant_id, reversal_id, reverses_entry_id)
    where kind = 'REVERSAL';
create index ix_ledger_pending on incentive.commission_ledger_entry (tenant_id, clears_on) where status = 'PENDING';
create index ix_ledger_beneficiary_period on incentive.commission_ledger_entry
    (tenant_id, beneficiary_type, beneficiary_id, period_start);
create index ix_ledger_payment on incentive.commission_ledger_entry (tenant_id, payment_id);
create index ix_ledger_statement on incentive.commission_ledger_entry (tenant_id, statement_id)
    where statement_id is not null;
select shared.enable_tenant_rls('incentive.commission_ledger_entry');

create or replace function incentive.ledger_entry_guard() returns trigger
    language plpgsql
as $$
begin
    if (new.kind, new.beneficiary_type, new.beneficiary_id, new.role, new.attribution_id, new.agreement_id,
        new.payment_id, new.reversal_id, new.reverses_entry_id, new.creditor_id, new.portfolio_id, new.delay_bucket,
        new.origin, new.channel, new.base_amount, new.credit_weight, new.renegotiation_factor, new.credited_amount,
        new.credit_note, new.plan_id, new.paid_on, new.confirmed_on, new.clears_on, new.tenant_id, new.created_at)
       is distinct from
       (old.kind, old.beneficiary_type, old.beneficiary_id, old.role, old.attribution_id, old.agreement_id,
        old.payment_id, old.reversal_id, old.reverses_entry_id, old.creditor_id, old.portfolio_id, old.delay_bucket,
        old.origin, old.channel, old.base_amount, old.credit_weight, old.renegotiation_factor, old.credited_amount,
        old.credit_note, old.plan_id, old.paid_on, old.confirmed_on, old.clears_on, old.tenant_id, old.created_at) then
        raise exception 'Lançamento de comissão é imutável: use lançamento compensatório'
            using errcode = 'insufficient_privilege';
    end if;
    if old.status <> 'PENDING' and new.status <> old.status then
        raise exception 'Lançamento % não pode voltar de % para %', old.id, old.status, new.status
            using errcode = 'insufficient_privilege';
    end if;
    if old.statement_id is not null and new.statement_id is distinct from old.statement_id then
        raise exception 'Lançamento já incluído em extrato' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_ledger_entry_guard before update on incentive.commission_ledger_entry
    for each row execute function incentive.ledger_entry_guard();
create trigger trg_ledger_entry_no_delete before delete on incentive.commission_ledger_entry
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Extrato por negociador e período, com memória de cálculo reprodutível
-- (versão do plano + lançamentos + entradas do motor). Linhas = itens contestáveis.
-- -----------------------------------------------------------------------------
create table incentive.commission_statement (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    period_id              uuid           not null references incentive.commission_period (id),
    period_start           date           not null,
    beneficiary_id         uuid           not null,
    plan_id                uuid           references incentive.commission_plan (id),
    plan_code              varchar(40),
    plan_version           integer,
    status                 varchar(20)    not null,
    outcome                varchar(30)    not null,
    calculated_amount      numeric(19, 2) not null,
    adjustments_amount     numeric(19, 2) not null,
    payable_amount         numeric(19, 2) not null,
    withheld_amount        numeric(19, 2) not null,
    credited_recovery      numeric(19, 2) not null,
    calculation            jsonb          not null,
    calculation_schema     integer        not null,
    calculated_at          timestamptz    not null,
    calculated_by          uuid,
    approved_at            timestamptz,
    approved_by            uuid,
    exported_at            timestamptz,
    export_id              uuid,
    constraint uk_commission_statement unique (tenant_id, period_start, beneficiary_id),
    constraint ck_statement_status check (status in ('CALCULATED', 'DISPUTED', 'ADJUSTED', 'APPROVED', 'EXPORTED')),
    constraint ck_statement_outcome check (outcome in ('PAYABLE', 'NOT_ELIGIBLE', 'NO_PLAN', 'SUSPENDED_QUALITY_GATE',
                                                       'ZEROED_COMPLIANCE')),
    constraint ck_statement_amounts check (calculated_amount >= 0 and payable_amount >= 0 and withheld_amount >= 0),
    constraint ck_statement_approved check ((status in ('APPROVED', 'EXPORTED')) = (approved_at is not null)),
    constraint ck_statement_exported check ((status = 'EXPORTED') = (exported_at is not null))
);
create index ix_statement_period on incentive.commission_statement (tenant_id, period_start, status);
create index ix_statement_beneficiary on incentive.commission_statement (tenant_id, beneficiary_id, period_start desc);
select shared.enable_tenant_rls('incentive.commission_statement');

create table incentive.commission_statement_line (
    statement_id  uuid           not null references incentive.commission_statement (id),
    line_number   integer        not null,
    component     varchar(30)    not null,
    description   varchar(300)   not null,
    segment_key   varchar(200),
    base_amount   numeric(19, 2),
    rate          numeric(19, 6),
    factor        numeric(19, 6),
    amount        numeric(19, 2) not null,
    primary key (statement_id, line_number)
);
alter table incentive.commission_statement_line enable row level security;
alter table incentive.commission_statement_line force row level security;
create policy tenant_isolation on incentive.commission_statement_line
    using (exists (select 1 from incentive.commission_statement s where s.id = statement_id))
    with check (exists (select 1 from incentive.commission_statement s where s.id = statement_id));

-- extrato aprovado/exportado é imutável (correções entram no período seguinte)
create or replace function incentive.statement_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Extrato de comissão não pode ser removido' using errcode = 'insufficient_privilege';
    end if;
    if old.status in ('APPROVED', 'EXPORTED')
       and (new.calculated_amount <> old.calculated_amount or new.adjustments_amount <> old.adjustments_amount
            or new.payable_amount <> old.payable_amount or new.calculation is distinct from old.calculation
            or new.approved_by is distinct from old.approved_by
            or (old.status = 'EXPORTED' and new.status <> old.status)
            or (old.status = 'APPROVED' and new.status not in ('APPROVED', 'EXPORTED'))) then
        raise exception 'Extrato aprovado é imutável' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_statement_guard before update or delete on incentive.commission_statement
    for each row execute function incentive.statement_guard();

create or replace function incentive.statement_line_guard() returns trigger
    language plpgsql
as $$
begin
    if exists (select 1 from incentive.commission_statement s
               where s.id = coalesce(old.statement_id, new.statement_id) and s.status in ('APPROVED', 'EXPORTED')) then
        raise exception 'Extrato aprovado é imutável' using errcode = 'insufficient_privilege';
    end if;
    return coalesce(new, old);
end
$$;
create trigger trg_statement_line_guard before insert or update or delete on incentive.commission_statement_line
    for each row execute function incentive.statement_line_guard();

-- -----------------------------------------------------------------------------
-- Contestação item a item e ajustes manuais (motivo obrigatório; dupla aprovação
-- acima do limite configurado).
-- -----------------------------------------------------------------------------
create table incentive.statement_dispute (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    statement_id     uuid           not null references incentive.commission_statement (id),
    line_number      integer,
    ledger_entry_id  uuid           references incentive.commission_ledger_entry (id),
    agreement_id     uuid,
    reason           varchar(2000)  not null,
    status           varchar(20)    not null,
    opened_by        uuid           not null,
    opened_at        timestamptz    not null,
    resolution       varchar(2000),
    resolved_by      uuid,
    resolved_at      timestamptz,
    adjustment_id    uuid,
    constraint ck_dispute_status check (status in ('OPEN', 'ACCEPTED', 'REJECTED')),
    constraint ck_dispute_target check (line_number is not null or ledger_entry_id is not null or agreement_id is not null),
    constraint ck_dispute_resolved check ((status = 'OPEN') = (resolved_at is null)
                                          and (status = 'OPEN' or (resolution is not null and resolved_by is not null)))
);
create index ix_dispute_statement on incentive.statement_dispute (tenant_id, statement_id, status);
select shared.enable_tenant_rls('incentive.statement_dispute');

create table incentive.statement_adjustment (
    id                 uuid           not null primary key,
    tenant_id          uuid           not null references organization.tenant (id),
    version            bigint         not null,
    created_at         timestamptz    not null,
    created_by         uuid,
    updated_at         timestamptz    not null,
    updated_by         uuid,
    statement_id       uuid           not null references incentive.commission_statement (id),
    dispute_id         uuid           references incentive.statement_dispute (id),
    amount             numeric(19, 2) not null,
    reason             varchar(2000)  not null,
    status             varchar(30)    not null,
    requested_by       uuid           not null,
    requested_at       timestamptz    not null,
    decided_by         uuid,
    decided_at         timestamptz,
    decision_note      varchar(2000),
    constraint ck_adjustment_status check (status in ('APPLIED', 'PENDING_SECOND_APPROVAL', 'REJECTED')),
    constraint ck_adjustment_amount check (amount <> 0),
    constraint ck_adjustment_segregation check (decided_by is null or decided_by <> requested_by),
    -- APPLIED: abaixo do limite (sem segunda decisão) ou após a segunda aprovação
    constraint ck_adjustment_decided check ((status = 'PENDING_SECOND_APPROVAL' and decided_at is null)
                                            or (status = 'REJECTED' and decided_at is not null)
                                            or status = 'APPLIED')
);
create index ix_adjustment_statement on incentive.statement_adjustment (tenant_id, statement_id, status);
select shared.enable_tenant_rls('incentive.statement_adjustment');

-- Exportação para a folha: arquivo armazenado (S3) com hash, para evidência.
create table incentive.statement_export (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    period_start     date           not null,
    format           varchar(10)    not null,
    file_key         varchar(512)   not null,
    file_name        varchar(200)   not null,
    content_type     varchar(100)   not null,
    file_size        bigint         not null,
    sha256           varchar(64)    not null,
    statements_count integer        not null,
    total_amount     numeric(19, 2) not null,
    exported_by      uuid           not null,
    constraint ck_statement_export_format check (format in ('CSV', 'XLSX'))
);
create index ix_statement_export_period on incentive.statement_export (tenant_id, period_start, created_at desc);
select shared.enable_tenant_rls('incentive.statement_export');
create trigger trg_statement_export_append_only before update or delete on incentive.statement_export
    for each row execute function shared.forbid_mutation();

grant select, insert, update, delete on incentive.commission_period, incentive.commission_ledger_entry,
    incentive.commission_statement, incentive.commission_statement_line, incentive.statement_dispute,
    incentive.statement_adjustment, incentive.statement_export to app_runtime;
-- razão: inserção livre; atualização só das colunas do ciclo de liberação e do extrato
revoke update, delete on incentive.commission_ledger_entry from app_runtime;
grant update (status, cleared_at, period_start, statement_id, version, updated_at, updated_by)
    on incentive.commission_ledger_entry to app_runtime;
revoke delete on incentive.commission_period, incentive.commission_statement, incentive.statement_dispute,
    incentive.statement_adjustment from app_runtime;
revoke update, delete on incentive.statement_export from app_runtime;
