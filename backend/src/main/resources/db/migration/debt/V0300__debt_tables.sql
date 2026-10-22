-- =============================================================================
-- Módulo debt: devedores, contatos, contratos de origem, títulos, razão do título,
-- saldos informados pelo credor e histórico de titularidade (RB01, RB03, M02, F05).
--
-- Dados pessoais (documento, contatos) ficam criptografados (AES-256-GCM, coluna
-- *_encrypted) com blind index (*_hash, HMAC por tenant) para busca por igualdade e
-- deduplicação. Razão do título e saldos informados são append-only.
-- =============================================================================

-- Busca de devedores por trecho do nome (operadores procuram por sobrenome).
-- pg_trgm é extensão confiável (PG13+): o dono do banco pode criá-la.
create extension if not exists pg_trgm with schema shared;

-- -----------------------------------------------------------------------------
-- Devedor: identidade pelo documento (CPF/CNPJ), nunca pelo nome.
-- Após anonimização (LGPD) documento e hash ficam nulos; o registro permanece
-- porque títulos e lançamentos financeiros o referenciam.
-- -----------------------------------------------------------------------------
create table debt.debtor (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    person_type         varchar(20)    not null,
    name                varchar(200)   not null,
    document_encrypted  varchar(512),
    document_hash       varchar(64),
    birth_date          date,
    status              varchar(20)    not null,
    notes               varchar(2000),
    anonymized_at       timestamptz,
    constraint uk_debtor_document_hash unique (tenant_id, document_hash),
    constraint ck_debtor_person_type check (person_type in ('INDIVIDUAL', 'COMPANY')),
    constraint ck_debtor_status check (status in ('ACTIVE', 'ANONYMIZED')),
    constraint ck_debtor_identity check (
        (status = 'ACTIVE' and document_encrypted is not null and document_hash is not null)
        or (status = 'ANONYMIZED' and document_encrypted is null and document_hash is null and anonymized_at is not null))
);
create index ix_debtor_name_trgm on debt.debtor using gin (lower(name) shared.gin_trgm_ops);
select shared.enable_tenant_rls('debt.debtor');

-- -----------------------------------------------------------------------------
-- Contatos do devedor. value_hash = blind index do valor normalizado (telefone E.164,
-- e-mail minúsculo, endereço sem acentos/espaços repetidos); evita duplicidade por
-- (devedor, tipo, valor), inclusive em reimportação.
-- -----------------------------------------------------------------------------
create table debt.debtor_contact (
    id                uuid           not null primary key,
    tenant_id         uuid           not null references organization.tenant (id),
    version           bigint         not null,
    created_at        timestamptz    not null,
    created_by        uuid,
    updated_at        timestamptz    not null,
    updated_by        uuid,
    debtor_id         uuid           not null references debt.debtor (id),
    type              varchar(20)    not null,
    value_encrypted   varchar(2048)  not null,
    value_hash        varchar(64)    not null,
    label             varchar(20)    not null,
    whatsapp          boolean        not null,
    source            varchar(20)    not null,
    status            varchar(20)    not null,
    priority          integer        not null,
    last_verified_at  timestamptz,
    invalid_reason    varchar(500),
    constraint uk_debtor_contact_value unique (tenant_id, debtor_id, type, value_hash),
    constraint ck_debtor_contact_type check (type in ('PHONE', 'EMAIL', 'ADDRESS')),
    constraint ck_debtor_contact_label check (label in ('MOBILE', 'HOME', 'WORK', 'OTHER')),
    constraint ck_debtor_contact_source check (source in ('IMPORT', 'MANUAL', 'DEBTOR', 'PROVIDER')),
    constraint ck_debtor_contact_status check (status in ('UNVERIFIED', 'VALID', 'INVALID')),
    constraint ck_debtor_contact_whatsapp check (not whatsapp or type = 'PHONE'),
    constraint ck_debtor_contact_priority check (priority >= 0)
);
create index ix_debtor_contact_debtor on debt.debtor_contact (tenant_id, debtor_id, type, status);
-- localizar o devedor a partir de um telefone/e-mail recebido (ex.: mensagem de entrada)
create index ix_debtor_contact_hash on debt.debtor_contact (tenant_id, type, value_hash);
select shared.enable_tenant_rls('debt.debtor_contact');

-- -----------------------------------------------------------------------------
-- Contrato de origem no credor (agrupa títulos/parcelas de uma mesma operação).
-- -----------------------------------------------------------------------------
create table debt.origin_contract (
    id               uuid            not null primary key,
    tenant_id        uuid            not null references organization.tenant (id),
    version          bigint          not null,
    created_at       timestamptz     not null,
    created_by       uuid,
    updated_at       timestamptz     not null,
    updated_by       uuid,
    creditor_id      uuid            not null references organization.creditor (id),
    portfolio_id     uuid            not null references organization.portfolio (id),
    debtor_id        uuid            not null references debt.debtor (id),
    external_id      varchar(80)     not null,
    product          varchar(120),
    signed_at        date,
    original_amount  numeric(19, 2),
    constraint uk_origin_contract_external unique (tenant_id, creditor_id, external_id),
    constraint ck_origin_contract_amount check (original_amount is null or original_amount >= 0)
);
create index ix_origin_contract_debtor on debt.origin_contract (tenant_id, debtor_id);
select shared.enable_tenant_rls('debt.origin_contract');

-- -----------------------------------------------------------------------------
-- Título (parcela original). open_balance/paid_total são cache do razão, mantidos
-- na mesma transação dos lançamentos.
-- -----------------------------------------------------------------------------
create table debt.debt_title (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    creditor_id         uuid            not null references organization.creditor (id),
    portfolio_id        uuid            not null references organization.portfolio (id),
    debtor_id           uuid            not null references debt.debtor (id),
    origin_contract_id  uuid            references debt.origin_contract (id),
    external_id         varchar(80)     not null,
    installment_number  integer,
    due_date            date            not null,
    principal_amount    numeric(19, 2)  not null,
    status              varchar(20)     not null,
    status_reason       varchar(500),
    dispute_blocked     boolean         not null,
    dispute_id          uuid,
    open_balance        numeric(19, 2)  not null,
    paid_total          numeric(19, 2)  not null,
    last_payment_at     timestamptz,
    import_batch_id     uuid,
    constraint uk_debt_title_external unique (tenant_id, creditor_id, external_id),
    constraint ck_debt_title_status check (status in ('OPEN', 'SETTLED', 'CANCELLED', 'SUSPENDED', 'RETURNED')),
    constraint ck_debt_title_principal check (principal_amount > 0),
    constraint ck_debt_title_balance check (open_balance >= 0),
    constraint ck_debt_title_paid check (paid_total >= 0),
    constraint ck_debt_title_installment check (installment_number is null or installment_number > 0),
    constraint ck_debt_title_dispute check (not dispute_blocked or dispute_id is not null)
);
create index ix_debt_title_debtor on debt.debt_title (tenant_id, debtor_id);
create index ix_debt_title_portfolio on debt.debt_title (tenant_id, portfolio_id, status, due_date);
create index ix_debt_title_creditor_due on debt.debt_title (tenant_id, creditor_id, status, due_date);
create index ix_debt_title_import_batch on debt.debt_title (tenant_id, import_batch_id) where import_batch_id is not null;
create index ix_debt_title_contract on debt.debt_title (tenant_id, origin_contract_id) where origin_contract_id is not null;
select shared.enable_tenant_rls('debt.debt_title');

-- -----------------------------------------------------------------------------
-- Razão do título (append-only). Convenção de sinal: débitos ao devedor
-- (OPENING, CHARGE, REVERSAL) são positivos e aumentam o saldo; créditos
-- (PAYMENT, DISCOUNT, WRITE_OFF) são negativos e o reduzem; ADJUSTMENT (correção do
-- principal informada pelo credor) tem o sinal da diferença. Saldo = soma dos lançamentos.
-- A chave única garante idempotência por referência externa (pagamento, acordo).
-- -----------------------------------------------------------------------------
create table debt.title_ledger_entry (
    id              uuid            not null primary key,
    tenant_id       uuid            not null references organization.tenant (id),
    version         bigint          not null,
    created_at      timestamptz     not null,
    created_by      uuid,
    updated_at      timestamptz     not null,
    updated_by      uuid,
    title_id        uuid            not null references debt.debt_title (id),
    entry_type      varchar(20)     not null,
    component       varchar(20)     not null,
    amount          numeric(19, 2)  not null,
    occurred_at     timestamptz     not null,
    reference_type  varchar(40)     not null,
    reference_id    uuid            not null,
    reason          varchar(500),
    constraint uk_title_ledger_reference unique (tenant_id, title_id, entry_type, reference_type, reference_id, component),
    constraint ck_title_ledger_type check (entry_type in ('OPENING', 'CHARGE', 'ADJUSTMENT', 'DISCOUNT', 'WRITE_OFF',
                                                          'PAYMENT', 'REVERSAL')),
    constraint ck_title_ledger_component check (component in ('PRINCIPAL', 'INTEREST', 'FINE', 'FEES', 'HONORARIUM')),
    constraint ck_title_ledger_sign check (
        (entry_type in ('OPENING', 'CHARGE', 'REVERSAL') and amount > 0)
        or (entry_type in ('DISCOUNT', 'WRITE_OFF', 'PAYMENT') and amount < 0)
        or (entry_type = 'ADJUSTMENT' and amount <> 0))
);
create index ix_title_ledger_title on debt.title_ledger_entry (tenant_id, title_id, occurred_at);
select shared.enable_tenant_rls('debt.title_ledger_entry');
create trigger trg_title_ledger_append_only before update or delete on debt.title_ledger_entry
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Saldo informado pelo credor em cada carga (append-only), para conciliação com o razão.
-- -----------------------------------------------------------------------------
create table debt.title_balance_snapshot (
    id                uuid            not null primary key,
    tenant_id         uuid            not null references organization.tenant (id),
    version           bigint          not null,
    created_at        timestamptz     not null,
    created_by        uuid,
    updated_at        timestamptz     not null,
    updated_by        uuid,
    title_id          uuid            not null references debt.debt_title (id),
    reference_date    date            not null,
    reported_balance  numeric(19, 2)  not null,
    import_batch_id   uuid            not null,
    constraint uk_title_balance_snapshot_batch unique (tenant_id, title_id, import_batch_id),
    constraint ck_title_balance_snapshot_amount check (reported_balance >= 0)
);
create index ix_title_balance_snapshot_title on debt.title_balance_snapshot (tenant_id, title_id, reference_date desc);
select shared.enable_tenant_rls('debt.title_balance_snapshot');
create trigger trg_title_balance_snapshot_append_only before update or delete on debt.title_balance_snapshot
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Histórico de titularidade: em que credor/carteira o título esteve e até quando.
-- No máximo um período aberto por título.
-- -----------------------------------------------------------------------------
create table debt.title_ownership (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    title_id        uuid           not null references debt.debt_title (id),
    creditor_id     uuid           not null references organization.creditor (id),
    portfolio_id    uuid           not null references organization.portfolio (id),
    assigned_at     timestamptz    not null,
    released_at     timestamptz,
    release_type    varchar(20),
    release_reason  varchar(500),
    constraint ck_title_ownership_release_type check (release_type in ('TRANSFER', 'RETURN')),
    constraint ck_title_ownership_release check (
        (released_at is null and release_type is null)
        or (released_at is not null and release_type is not null and released_at >= assigned_at))
);
create unique index uk_title_ownership_open on debt.title_ownership (tenant_id, title_id) where released_at is null;
create index ix_title_ownership_title on debt.title_ownership (tenant_id, title_id, assigned_at);
select shared.enable_tenant_rls('debt.title_ownership');

grant select, insert, update, delete on all tables in schema debt to app_runtime;
-- histórico financeiro e de carga é imutável
revoke update, delete on debt.title_ledger_entry, debt.title_balance_snapshot from app_runtime;
-- devedores (anonimizados, não removidos), contratos, títulos e titularidade não são removidos fisicamente;
-- contatos podem ser removidos (anonimização LGPD)
revoke delete on debt.debtor, debt.origin_contract, debt.debt_title, debt.title_ownership from app_runtime;
