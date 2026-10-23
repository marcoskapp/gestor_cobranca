-- =============================================================================
-- Módulo settlement: comissão da assessoria junto ao credor e prestação de contas
-- (F17, F22, RB08) — docs/tecnico/desenho/prestacao-de-contas.md.
--
-- O schema "settlement" já existe (V0001). Todas as tabelas têm RLS forçado por
-- tenant; os fatos chegam por eventos de integração (payment, negotiation, legal)
-- e por consultas às portas públicas permitidas (payment, negotiation, organization).
-- Nada financeiro é apagado: estornos e correções são lançamentos compensatórios.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Regras de comissionamento por contrato de serviço (organization.service_contract),
-- uma linha por versão. Versão publicada é imutável; a anterior recebe o fim da
-- vigência. Regras tipadas em jsonb com versão de esquema.
-- -----------------------------------------------------------------------------
create table settlement.commission_rule_set (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    creditor_id           uuid           not null references organization.creditor (id),
    contract_id           uuid           not null references organization.service_contract (id),
    rule_version          integer        not null,
    status                varchar(20)    not null,
    effective_from        date,
    effective_to          date,
    rules                 jsonb          not null,
    rules_schema_version  integer        not null,
    notes                 varchar(2000),
    rules_edited_by       uuid           not null,
    rules_edited_at       timestamptz    not null,
    published_by          uuid,
    published_at          timestamptz,
    constraint uk_commission_rule_set_version unique (tenant_id, contract_id, rule_version),
    constraint ck_commission_rule_set_status check (status in ('DRAFT', 'PUBLISHED', 'DISCARDED')),
    constraint ck_commission_rule_set_published check ((status = 'PUBLISHED') = (published_at is not null)
                                                       and (status <> 'PUBLISHED' or effective_from is not null)),
    constraint ck_commission_rule_set_range check (effective_to is null or effective_to >= effective_from),
    constraint ck_commission_rule_set_segregation check (published_by is null or published_by <> rules_edited_by)
);
create unique index uk_commission_rule_set_draft on settlement.commission_rule_set (tenant_id, contract_id)
    where status = 'DRAFT';
create index ix_commission_rule_set_effective on settlement.commission_rule_set
    (tenant_id, contract_id, effective_from) where status = 'PUBLISHED';
create index ix_commission_rule_set_creditor on settlement.commission_rule_set (tenant_id, creditor_id);
select shared.enable_tenant_rls('settlement.commission_rule_set');

-- versão publicada: regras, vigência inicial e autoria imutáveis (só o fim da vigência é preenchido)
create or replace function settlement.rule_set_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Regras de comissão não podem ser removidas' using errcode = 'insufficient_privilege';
    end if;
    if old.status = 'PUBLISHED'
       and (new.rules is distinct from old.rules or new.effective_from is distinct from old.effective_from
            or new.status <> old.status or new.published_by is distinct from old.published_by
            or new.contract_id <> old.contract_id or new.rule_version <> old.rule_version
            or (old.effective_to is not null and new.effective_to is distinct from old.effective_to)) then
        raise exception 'Versão publicada de regras de comissão é imutável' using errcode = 'insufficient_privilege';
    end if;
    if old.status = 'DISCARDED' and new.status <> old.status then
        raise exception 'Rascunho descartado não pode ser reaberto' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_rule_set_guard before update or delete on settlement.commission_rule_set
    for each row execute function settlement.rule_set_guard();

-- -----------------------------------------------------------------------------
-- Acordos conhecidos por evento (valor negociado e comissão prevista).
-- -----------------------------------------------------------------------------
create table settlement.agreement_fact (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    agreement_id           uuid           not null,
    creditor_id            uuid           not null,
    portfolio_id           uuid,
    status                 varchar(20)    not null,
    agreement_amount       numeric(19, 2) not null,
    days_past_due          integer,
    installment_count      integer,
    renegotiation          boolean        not null,
    formalized_at          timestamptz,
    status_changed_at      timestamptz    not null,
    constraint uk_agreement_fact unique (tenant_id, agreement_id),
    constraint ck_agreement_fact_status check (status in ('FORMALIZED', 'IN_PAYMENT', 'SETTLED', 'BROKEN',
                                                          'CANCELLED', 'RENEGOTIATED'))
);
create index ix_agreement_fact_creditor on settlement.agreement_fact (tenant_id, creditor_id, formalized_at);
select shared.enable_tenant_rls('settlement.agreement_fact');

-- -----------------------------------------------------------------------------
-- Recebimentos (fatos de recuperação confirmada) por alocação do payment
-- (paymentId dos eventos). PAYMENT: título avulso (um por título) ou acordo (valor
-- aplicado pelo negotiation); REVERSAL: compensação de estorno/realocação,
-- valores negativos. Fatos imutáveis; só a apuração (PENDING_RULES → RATED), o
-- contrato resolvido e o vínculo com o demonstrativo mudam.
-- -----------------------------------------------------------------------------
create table settlement.receipt (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    kind                  varchar(10)    not null,
    allocation_id         uuid           not null,
    payment_id            uuid,
    title_id              uuid,
    title_reference       varchar(120),
    reversal_id           uuid,
    reverses_receipt_id   uuid           references settlement.receipt (id),
    reversal_reason       varchar(40),
    creditor_id           uuid           not null,
    portfolio_id          uuid,
    contract_id           uuid,
    agreement_id          uuid,
    debtor_id             uuid,
    amount                numeric(19, 2) not null,
    honorarium_amount     numeric(19, 2) not null,
    days_past_due         integer,
    payment_type          varchar(20)    not null,
    received_by           varchar(10)    not null,
    renegotiation         boolean        not null,
    method                varchar(20),
    source                varchar(20),
    paid_on               date           not null,
    competence_date       date           not null,
    detail                jsonb          not null,
    rating_status         varchar(20)    not null,
    pending_reason        varchar(40),
    rated_at              timestamptz,
    statement_id          uuid,
    constraint ck_receipt_kind check (kind in ('PAYMENT', 'REVERSAL')),
    constraint ck_receipt_sign check ((kind = 'PAYMENT' and amount > 0 and honorarium_amount >= 0
                                       and reversal_id is null and reverses_receipt_id is null)
                                      or (kind = 'REVERSAL' and amount < 0 and honorarium_amount <= 0
                                          and reversal_id is not null and reverses_receipt_id is not null)),
    constraint ck_receipt_honorarium check (abs(honorarium_amount) <= abs(amount)),
    constraint ck_receipt_payment_type check (payment_type in ('LUMP_SUM', 'INSTALLMENTS')),
    constraint ck_receipt_received_by check (received_by in ('AGENCY', 'CREDITOR')),
    constraint ck_receipt_rating check (rating_status in ('PENDING_RULES', 'RATED')),
    constraint ck_receipt_rated check ((rating_status = 'RATED') = (rated_at is not null)),
    constraint ck_receipt_statement check (statement_id is null or rating_status = 'RATED')
);
create unique index uk_receipt_payment on settlement.receipt (tenant_id, allocation_id, title_id) nulls not distinct
    where kind = 'PAYMENT';
create unique index uk_receipt_reversal on settlement.receipt (tenant_id, reversal_id, reverses_receipt_id)
    where kind = 'REVERSAL';
create index ix_receipt_allocation on settlement.receipt (tenant_id, allocation_id);
create index ix_receipt_open on settlement.receipt (tenant_id, creditor_id, competence_date)
    where statement_id is null;
create index ix_receipt_pending on settlement.receipt (tenant_id, creditor_id) where rating_status = 'PENDING_RULES';
create index ix_receipt_statement on settlement.receipt (tenant_id, statement_id) where statement_id is not null;
create index ix_receipt_agreement on settlement.receipt (tenant_id, agreement_id) where agreement_id is not null;
select shared.enable_tenant_rls('settlement.receipt');

create or replace function settlement.receipt_guard() returns trigger
    language plpgsql
as $$
begin
    if (new.kind, new.allocation_id, new.payment_id, new.title_id, new.title_reference, new.reversal_id,
        new.reverses_receipt_id, new.reversal_reason, new.creditor_id, new.portfolio_id, new.agreement_id,
        new.debtor_id, new.amount, new.honorarium_amount, new.days_past_due, new.payment_type, new.received_by,
        new.renegotiation, new.method, new.source, new.paid_on, new.competence_date, new.detail, new.tenant_id,
        new.created_at)
       is distinct from
       (old.kind, old.allocation_id, old.payment_id, old.title_id, old.title_reference, old.reversal_id,
        old.reverses_receipt_id, old.reversal_reason, old.creditor_id, old.portfolio_id, old.agreement_id,
        old.debtor_id, old.amount, old.honorarium_amount, old.days_past_due, old.payment_type, old.received_by,
        old.renegotiation, old.method, old.source, old.paid_on, old.competence_date, old.detail, old.tenant_id,
        old.created_at) then
        raise exception 'Recebimento é imutável: use lançamento compensatório' using errcode = 'insufficient_privilege';
    end if;
    if old.rating_status = 'RATED' and new.rating_status <> 'RATED' then
        raise exception 'Recebimento apurado não volta a pendente' using errcode = 'insufficient_privilege';
    end if;
    if old.contract_id is not null and new.contract_id is distinct from old.contract_id then
        raise exception 'Contrato do recebimento já resolvido' using errcode = 'insufficient_privilege';
    end if;
    if old.statement_id is distinct from new.statement_id and old.statement_id is not null
       and exists (select 1 from settlement.statement s where s.id = old.statement_id
                   and s.status not in ('DRAFT', 'CALCULATED', 'REVIEWED', 'CANCELLED')) then
        raise exception 'Recebimento já incluído em demonstrativo aprovado' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_receipt_guard before update on settlement.receipt
    for each row execute function settlement.receipt_guard();
create trigger trg_receipt_no_delete before delete on settlement.receipt
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Razão de comissão da assessoria (append-only): ACCRUAL por recebimento apurado,
-- REVERSAL proporcional por estorno, com a versão das regras e a memória de cálculo.
-- transfer_effect = efeito no valor a repassar ao credor.
-- -----------------------------------------------------------------------------
create table settlement.commission_entry (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    kind                  varchar(10)    not null,
    receipt_id            uuid           not null references settlement.receipt (id),
    reverses_entry_id     uuid           references settlement.commission_entry (id),
    creditor_id           uuid           not null,
    agreement_id          uuid,
    rule_set_id           uuid           not null references settlement.commission_rule_set (id),
    rule_version          integer        not null,
    rule_code             varchar(60)    not null,
    recovered_amount      numeric(19, 2) not null,
    agency_honorarium     numeric(19, 2) not null,
    creditor_honorarium   numeric(19, 2) not null,
    commission_base       numeric(19, 2) not null,
    rate                  numeric(9, 4)  not null,
    commission_amount     numeric(19, 2) not null,
    fixed_fee             numeric(19, 2) not null,
    transfer_effect       numeric(19, 2) not null,
    calculation           jsonb          not null,
    constraint ck_commission_entry_kind check (kind in ('ACCRUAL', 'REVERSAL')),
    constraint ck_commission_entry_sign check ((kind = 'ACCRUAL' and recovered_amount > 0 and commission_amount >= 0
                                                and fixed_fee >= 0 and agency_honorarium >= 0
                                                and reverses_entry_id is null)
                                               or (kind = 'REVERSAL' and recovered_amount < 0
                                                   and commission_amount <= 0 and fixed_fee <= 0
                                                   and agency_honorarium <= 0 and reverses_entry_id is not null)),
    constraint ck_commission_entry_rate check (rate between 0 and 100)
);
create unique index uk_commission_entry_receipt on settlement.commission_entry (tenant_id, receipt_id);
create index ix_commission_entry_reverses on settlement.commission_entry (tenant_id, reverses_entry_id)
    where reverses_entry_id is not null;
create index ix_commission_entry_agreement on settlement.commission_entry (tenant_id, agreement_id)
    where agreement_id is not null;
select shared.enable_tenant_rls('settlement.commission_entry');
create trigger trg_commission_entry_append_only before update or delete on settlement.commission_entry
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Estornos recebidos (PaymentReversed), guardados mesmo antes do recebimento
-- correspondente chegar (entrega fora de ordem); applied_amount = já compensado.
-- -----------------------------------------------------------------------------
create table settlement.reversal_notice (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    reversal_id      uuid           not null,
    allocation_id    uuid           not null,
    creditor_id      uuid           not null,
    agreement_id     uuid,
    amount           numeric(19, 2) not null,
    applied_amount   numeric(19, 2) not null,
    reason           varchar(40),
    reversed_on      date           not null,
    constraint uk_reversal_notice unique (tenant_id, reversal_id),
    constraint ck_reversal_notice_amounts check (amount > 0 and applied_amount >= 0 and applied_amount <= amount)
);
create index ix_reversal_notice_allocation on settlement.reversal_notice (tenant_id, allocation_id);
select shared.enable_tenant_rls('settlement.reversal_notice');

-- -----------------------------------------------------------------------------
-- Custas e despesas do jurídico no demonstrativo (LegalExpenseChanged).
-- CHARGE: despesa lançada; CANCELLATION: compensação de despesa já incluída em
-- demonstrativo. effect DEDUCT = reembolsável adiantada pela assessoria/escritório
-- (deduzida do repasse); INFORMATIVE = paga pelo credor ou não reembolsável.
-- -----------------------------------------------------------------------------
create table settlement.reimbursable_expense (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    kind                  varchar(20)    not null,
    legal_expense_id      uuid           not null,
    matter_id             uuid           not null,
    creditor_id           uuid           not null,
    expense_type          varchar(30)    not null,
    amount                numeric(19, 2) not null,
    incurred_on           date           not null,
    advanced_by           varchar(20)    not null,
    reimbursable          boolean        not null,
    has_receipt           boolean        not null,
    effect                varchar(20)    not null,
    competence_date       date           not null,
    status                varchar(30)    not null,
    reverses_expense_id   uuid           references settlement.reimbursable_expense (id),
    statement_id          uuid,
    constraint uk_reimbursable_expense unique (tenant_id, legal_expense_id, kind),
    constraint ck_reimbursable_expense_kind check (kind in ('CHARGE', 'CANCELLATION')),
    constraint ck_reimbursable_expense_sign check ((kind = 'CHARGE' and amount > 0 and reverses_expense_id is null)
                                                   or (kind = 'CANCELLATION' and amount < 0
                                                       and reverses_expense_id is not null)),
    constraint ck_reimbursable_expense_effect check (effect in ('DEDUCT', 'INFORMATIVE')),
    constraint ck_reimbursable_expense_advanced check (advanced_by in ('CREDITOR', 'AGENCY', 'LAW_FIRM')),
    constraint ck_reimbursable_expense_status check (status in ('ACTIVE', 'CANCELLED', 'REIMBURSED_OUTSIDE'))
);
create index ix_reimbursable_expense_open on settlement.reimbursable_expense (tenant_id, creditor_id, competence_date)
    where statement_id is null;
create index ix_reimbursable_expense_statement on settlement.reimbursable_expense (tenant_id, statement_id)
    where statement_id is not null;
select shared.enable_tenant_rls('settlement.reimbursable_expense');

-- -----------------------------------------------------------------------------
-- Demonstrativo (prestação de contas) por credor e período.
-- DRAFT → CALCULATED → REVIEWED → APPROVED → SENT → ACKNOWLEDGED | CONTESTED;
-- DRAFT/CALCULATED/REVIEWED → CANCELLED. Aprovado é imutável (gatilho).
-- -----------------------------------------------------------------------------
create table settlement.statement (
    id                              uuid           not null primary key,
    tenant_id                       uuid           not null references organization.tenant (id),
    version                         bigint         not null,
    created_at                      timestamptz    not null,
    created_by                      uuid,
    updated_at                      timestamptz    not null,
    updated_by                      uuid,
    creditor_id                     uuid           not null references organization.creditor (id),
    sequence_number                 integer        not null,
    period_start                    date           not null,
    period_end                      date           not null,
    status                          varchar(20)    not null,
    received_agency_amount          numeric(19, 2) not null,
    received_creditor_amount        numeric(19, 2) not null,
    reversed_amount                 numeric(19, 2) not null,
    recovered_amount                numeric(19, 2) not null,
    reconciled_amount               numeric(19, 2) not null,
    agency_honorarium               numeric(19, 2) not null,
    creditor_honorarium             numeric(19, 2) not null,
    commission_amount               numeric(19, 2) not null,
    fixed_fee_amount                numeric(19, 2) not null,
    expenses_amount                 numeric(19, 2) not null,
    adjustments_amount              numeric(19, 2) not null,
    net_transfer_amount             numeric(19, 2) not null,
    transferred_amount              numeric(19, 2) not null,
    negotiated_amount               numeric(19, 2) not null,
    forecast_commission             numeric(19, 2) not null,
    pending_reconciliation_amount   numeric(19, 2) not null,
    pending_rules_amount            numeric(19, 2) not null,
    open_divergences                integer        not null,
    receipts_count                  integer        not null,
    calculation                     jsonb,
    calculation_schema              integer        not null,
    calculated_at                   timestamptz,
    calculated_by                   uuid,
    reviewed_at                     timestamptz,
    reviewed_by                     uuid,
    approved_at                     timestamptz,
    approved_by                     uuid,
    sent_at                         timestamptz,
    sent_by                         uuid,
    sent_channel                    varchar(20),
    sent_reference                  varchar(200),
    creditor_response_at            timestamptz,
    creditor_response_by            uuid,
    creditor_response_note          varchar(2000),
    cancelled_reason                varchar(500),
    constraint uk_statement_sequence unique (tenant_id, creditor_id, sequence_number),
    constraint ck_statement_status check (status in ('DRAFT', 'CALCULATED', 'REVIEWED', 'APPROVED', 'SENT',
                                                     'ACKNOWLEDGED', 'CONTESTED', 'CANCELLED')),
    constraint ck_statement_period check (period_end >= period_start),
    constraint ck_statement_calculated check (status in ('DRAFT', 'CANCELLED') or calculated_at is not null),
    constraint ck_statement_approved check ((status in ('APPROVED', 'SENT', 'ACKNOWLEDGED', 'CONTESTED'))
                                            = (approved_at is not null)),
    constraint ck_statement_segregation check (approved_by is null or approved_by <> calculated_by),
    constraint ck_statement_transferred check (transferred_amount >= 0)
);
-- um único demonstrativo em aberto por credor e nenhum período sobreposto entre os não cancelados
create unique index uk_statement_open on settlement.statement (tenant_id, creditor_id)
    where status in ('DRAFT', 'CALCULATED', 'REVIEWED');
create index ix_statement_creditor on settlement.statement (tenant_id, creditor_id, period_start desc);
select shared.enable_tenant_rls('settlement.statement');

alter table settlement.statement add constraint ex_statement_period_overlap
    exclude using gist (tenant_id with =, creditor_id with =, daterange(period_start, period_end, '[]') with &&)
    where (status <> 'CANCELLED');

create or replace function settlement.statement_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Demonstrativo não pode ser removido' using errcode = 'insufficient_privilege';
    end if;
    if old.status in ('APPROVED', 'SENT', 'ACKNOWLEDGED', 'CONTESTED')
       and ((old.received_agency_amount, old.received_creditor_amount, old.reversed_amount, old.recovered_amount,
             old.reconciled_amount, old.agency_honorarium, old.creditor_honorarium, old.commission_amount,
             old.fixed_fee_amount, old.expenses_amount, old.adjustments_amount, old.net_transfer_amount,
             old.negotiated_amount, old.forecast_commission, old.pending_reconciliation_amount,
             old.pending_rules_amount, old.open_divergences, old.receipts_count, old.calculation, old.period_start,
             old.period_end, old.creditor_id, old.calculated_by, old.approved_by, old.approved_at)
            is distinct from
            (new.received_agency_amount, new.received_creditor_amount, new.reversed_amount, new.recovered_amount,
             new.reconciled_amount, new.agency_honorarium, new.creditor_honorarium, new.commission_amount,
             new.fixed_fee_amount, new.expenses_amount, new.adjustments_amount, new.net_transfer_amount,
             new.negotiated_amount, new.forecast_commission, new.pending_reconciliation_amount,
             new.pending_rules_amount, new.open_divergences, new.receipts_count, new.calculation, new.period_start,
             new.period_end, new.creditor_id, new.calculated_by, new.approved_by, new.approved_at)
            or new.status not in ('APPROVED', 'SENT', 'ACKNOWLEDGED', 'CONTESTED')) then
        raise exception 'Demonstrativo aprovado é imutável: correções entram no período seguinte'
            using errcode = 'insufficient_privilege';
    end if;
    if old.status = 'CANCELLED' and new.status <> 'CANCELLED' then
        raise exception 'Demonstrativo cancelado não reabre' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_statement_guard before update or delete on settlement.statement
    for each row execute function settlement.statement_guard();

-- -----------------------------------------------------------------------------
-- Ajustes do demonstrativo (motivo obrigatório; segunda aprovação acima do limite).
-- amount = efeito com sinal no valor a repassar (positivo: a favor do credor).
-- -----------------------------------------------------------------------------
create table settlement.statement_adjustment (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    statement_id           uuid           not null references settlement.statement (id),
    category               varchar(40)    not null,
    amount                 numeric(19, 2) not null,
    reason                 varchar(2000)  not null,
    related_statement_id   uuid           references settlement.statement (id),
    status                 varchar(30)    not null,
    requested_by           uuid           not null,
    requested_at           timestamptz    not null,
    decided_by             uuid,
    decided_at             timestamptz,
    decision_note          varchar(2000),
    constraint ck_statement_adjustment_category check (category in ('COMMISSION_CORRECTION', 'PRIOR_PERIOD_CORRECTION',
                                                                    'EXPENSE_REIMBURSEMENT', 'CONTESTATION_SETTLEMENT',
                                                                    'OTHER')),
    constraint ck_statement_adjustment_status check (status in ('APPLIED', 'PENDING_SECOND_APPROVAL', 'REJECTED')),
    constraint ck_statement_adjustment_amount check (amount <> 0),
    constraint ck_statement_adjustment_segregation check (decided_by is null or decided_by <> requested_by),
    -- APPLIED: abaixo do limite (sem segunda decisão) ou após a segunda aprovação
    constraint ck_statement_adjustment_decided check ((status = 'PENDING_SECOND_APPROVAL' and decided_at is null)
                                                      or (status = 'REJECTED' and decided_at is not null)
                                                      or status = 'APPLIED')
);
create index ix_statement_adjustment_statement on settlement.statement_adjustment (tenant_id, statement_id, status);
select shared.enable_tenant_rls('settlement.statement_adjustment');

create or replace function settlement.adjustment_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Ajuste não pode ser removido' using errcode = 'insufficient_privilege';
    end if;
    if new.amount <> old.amount or new.statement_id <> old.statement_id or new.requested_by <> old.requested_by
       or new.reason <> old.reason or new.category <> old.category or old.status <> 'PENDING_SECOND_APPROVAL' then
        raise exception 'Ajuste decidido é imutável' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_adjustment_guard before update or delete on settlement.statement_adjustment
    for each row execute function settlement.adjustment_guard();

-- -----------------------------------------------------------------------------
-- Exportações conciliáveis (CSV/XLSX) e resumo (PDF) guardados no S3 com hash.
-- -----------------------------------------------------------------------------
create table settlement.statement_export (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    statement_id    uuid           not null references settlement.statement (id),
    format          varchar(10)    not null,
    file_key        varchar(512)   not null,
    file_name       varchar(200)   not null,
    content_type    varchar(100)   not null,
    file_size       bigint         not null,
    sha256          varchar(64)    not null,
    rows_count      integer        not null,
    generated_by    uuid           not null,
    constraint ck_statement_export_format check (format in ('CSV', 'XLSX', 'PDF'))
);
create index ix_statement_export_statement on settlement.statement_export (tenant_id, statement_id, created_at desc);
select shared.enable_tenant_rls('settlement.statement_export');
create trigger trg_statement_export_append_only before update or delete on settlement.statement_export
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Repasses ao credor (ou recebimentos do credor quando o líquido é negativo), com
-- comprovante como evidência. Colunas financeiras imutáveis; só o cancelamento.
-- -----------------------------------------------------------------------------
create table settlement.creditor_transfer (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    statement_id        uuid           not null references settlement.statement (id),
    creditor_id         uuid           not null,
    direction           varchar(20)    not null,
    amount              numeric(19, 2) not null,
    transferred_on      date           not null,
    bank_reference      varchar(120)   not null,
    note                varchar(1000),
    evidence_key        varchar(512)   not null,
    evidence_name       varchar(200)   not null,
    evidence_type       varchar(100)   not null,
    evidence_size       bigint         not null,
    evidence_sha256     varchar(64)    not null,
    registered_by       uuid           not null,
    status              varchar(20)    not null,
    cancelled_at        timestamptz,
    cancelled_by        uuid,
    cancel_reason       varchar(500),
    constraint ck_creditor_transfer_direction check (direction in ('AGENCY_TO_CREDITOR', 'CREDITOR_TO_AGENCY')),
    constraint ck_creditor_transfer_amount check (amount > 0),
    constraint ck_creditor_transfer_status check (status in ('REGISTERED', 'CANCELLED')),
    constraint ck_creditor_transfer_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null))
);
create index ix_creditor_transfer_statement on settlement.creditor_transfer (tenant_id, statement_id);
create index ix_creditor_transfer_creditor on settlement.creditor_transfer (tenant_id, creditor_id, transferred_on desc);
select shared.enable_tenant_rls('settlement.creditor_transfer');

grant select, insert, update, delete on all tables in schema settlement to app_runtime;
-- razão e exportações: somente inserção
revoke update, delete on settlement.commission_entry, settlement.statement_export from app_runtime;
-- fatos: sem exclusão; atualização só das colunas de ciclo
revoke update, delete on settlement.receipt from app_runtime;
grant update (contract_id, rating_status, pending_reason, rated_at, statement_id, version, updated_at, updated_by)
    on settlement.receipt to app_runtime;
revoke update, delete on settlement.reimbursable_expense from app_runtime;
grant update (status, statement_id, version, updated_at, updated_by) on settlement.reimbursable_expense to app_runtime;
revoke update, delete on settlement.creditor_transfer from app_runtime;
grant update (status, cancelled_at, cancelled_by, cancel_reason, version, updated_at, updated_by)
    on settlement.creditor_transfer to app_runtime;
revoke delete on settlement.commission_rule_set, settlement.statement, settlement.statement_adjustment,
    settlement.reversal_notice, settlement.agreement_fact from app_runtime;
