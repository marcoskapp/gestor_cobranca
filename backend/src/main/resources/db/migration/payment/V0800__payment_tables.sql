-- =============================================================================
-- Módulo payment (M07 parcial, F10, F14, F15, RB06): contas Pix por assessoria/credor
-- (API Pix do BACEN, credenciais e certificado mTLS criptografados pela aplicação),
-- instruções de pagamento (Pix cob/cobv, boleto e link), pagamentos integrados e
-- externos, alocação em parcelas de acordo e títulos, estornos (compensatórios),
-- webhooks, importação de extrato/arquivo de retorno e divergências de conciliação.
--
-- Nada financeiro é apagado: pagamentos, alocações, estornos, extratos e divergências
-- não têm DELETE para app_runtime; estornos e linhas de extrato são append-only.
-- Ver docs/tecnico/desenho/pagamentos.md.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Parâmetros da assessoria (linha única por tenant; ausente = padrões app.payment.defaults).
-- -----------------------------------------------------------------------------
create table payment.settings (
    id                                  uuid           not null primary key,
    tenant_id                           uuid           not null references organization.tenant (id),
    version                             bigint         not null,
    created_at                          timestamptz    not null,
    created_by                          uuid,
    updated_at                          timestamptz    not null,
    updated_by                          uuid,
    external_review_threshold           numeric(19, 2) not null,
    pix_expiration_seconds              integer        not null,
    pix_due_validity_days               integer        not null,
    reconciliation_date_tolerance_days  integer        not null,
    constraint uk_payment_settings unique (tenant_id),
    constraint ck_payment_settings_values check (external_review_threshold >= 0
        and pix_expiration_seconds between 300 and 2592000
        and pix_due_validity_days between 0 and 60
        and reconciliation_date_tolerance_days between 0 and 15)
);
select shared.enable_tenant_rls('payment.settings');

-- -----------------------------------------------------------------------------
-- Conta Pix (PSP) da assessoria ou de um credor. Credenciais OAuth2, segredo do
-- webhook e senha do certificado em JSON criptografado; certificado mTLS (PKCS#12)
-- criptografado em coluna própria (AES-256-GCM, FieldEncryptor). creditor_id nulo =
-- conta padrão da assessoria.
-- -----------------------------------------------------------------------------
create table payment.pix_account (
    id                          uuid           not null primary key,
    tenant_id                   uuid           not null references organization.tenant (id),
    version                     bigint         not null,
    created_at                  timestamptz    not null,
    created_by                  uuid,
    updated_at                  timestamptz    not null,
    updated_by                  uuid,
    creditor_id                 uuid           references organization.creditor (id),
    name                        varchar(120)   not null,
    provider                    varchar(20)    not null,
    pix_key                     varchar(77)    not null,
    api_base_url                varchar(500)   not null,
    token_url                   varchar(500)   not null,
    credentials_encrypted       varchar(8000),
    certificate_encrypted       varchar(32000),
    certificate_sha256          varchar(64),
    certificate_expires_at      timestamptz,
    credentials_updated_at      timestamptz,
    webhook_auth                varchar(10)    not null,
    webhook_client_fingerprints varchar(1000),
    webhook_registered_at       timestamptz,
    routing_key                 varchar(64)    not null,
    active                      boolean        not null,
    constraint ck_pix_account_provider check (provider in ('BACEN_API', 'SANDBOX')),
    constraint ck_pix_account_webhook_auth check (webhook_auth in ('MTLS', 'HMAC')),
    constraint uk_pix_account_routing unique (routing_key)
);
create unique index uk_pix_account_active on payment.pix_account
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid)) where active;
select shared.enable_tenant_rls('payment.pix_account');

-- Rotas de webhook: tabela global (sem RLS), consultada antes de existir tenant. Só a
-- chave aleatória (256 bits) e ids; o segredo fica na conta (RLS + criptografia).
create table payment.webhook_route (
    routing_key     varchar(64)   not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    pix_account_id  uuid          not null references payment.pix_account (id),
    provider        varchar(20)   not null,
    active          boolean       not null,
    created_at      timestamptz   not null
);

-- Recibos do corpo bruto das notificações (reentrega idêntica não tem efeito).
create table payment.webhook_receipt (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    pix_account_id  uuid          not null references payment.pix_account (id),
    payload_sha256  varchar(64)   not null,
    received_at     timestamptz   not null,
    constraint uk_payment_webhook_receipt unique (tenant_id, pix_account_id, payload_sha256)
);
create index ix_payment_webhook_receipt_received on payment.webhook_receipt (tenant_id, received_at);
select shared.enable_tenant_rls('payment.webhook_receipt');

-- -----------------------------------------------------------------------------
-- Instrução de pagamento: Pix imediato (cob) ou com vencimento (cobv), boleto ou link,
-- para UMA parcela de acordo ou UM título avulso.
-- -----------------------------------------------------------------------------
create table payment.payment_instruction (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    type                varchar(20)    not null,
    status              varchar(20)    not null,
    creditor_id         uuid           not null references organization.creditor (id),
    portfolio_id        uuid           references organization.portfolio (id),
    debtor_id           uuid           not null,
    case_id             uuid,
    agreement_id        uuid,
    installment_id      uuid,
    installment_number  integer,
    title_id            uuid,
    amount              numeric(19, 2) not null,
    due_date            date,
    expires_at          timestamptz    not null,
    pix_account_id      uuid           references payment.pix_account (id),
    txid                varchar(35),
    provider_revision   integer,
    location            varchar(300),
    pix_copy_paste      varchar(1000),
    registered_at       timestamptz,
    failure_code        varchar(60),
    failure_detail      varchar(300),
    paid_at             timestamptz,
    payment_id          uuid,
    cancelled_at        timestamptz,
    cancel_reason       varchar(200),
    channel             varchar(20),
    constraint ck_instruction_type check (type in ('PIX_IMMEDIATE', 'PIX_DUE', 'BOLETO', 'LINK')),
    constraint ck_instruction_status check (status in ('PENDING', 'ACTIVE', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')),
    constraint ck_instruction_target check (
        (agreement_id is not null and installment_id is not null and title_id is null)
        or (title_id is not null and agreement_id is null and installment_id is null)),
    constraint ck_instruction_amount check (amount > 0),
    constraint uk_instruction_txid unique (tenant_id, txid)
);
create index ix_instruction_installment on payment.payment_instruction (tenant_id, installment_id) where installment_id is not null;
create index ix_instruction_title on payment.payment_instruction (tenant_id, title_id) where title_id is not null;
create index ix_instruction_agreement on payment.payment_instruction (tenant_id, agreement_id) where agreement_id is not null;
create index ix_instruction_open on payment.payment_instruction (tenant_id, expires_at) where status in ('PENDING', 'ACTIVE');
create index ix_instruction_debtor on payment.payment_instruction (tenant_id, debtor_id, created_at desc);
select shared.enable_tenant_rls('payment.payment_instruction');

-- -----------------------------------------------------------------------------
-- Pagamento: INFORMED → CONFIRMED → RECONCILED; INFORMED → REJECTED; CONFIRMED/
-- RECONCILED → REVERSED (estorno compensatório em payment_reversal). Integrado (PROVIDER:
-- webhook/arquivo de retorno, idempotente por provider_transaction_id = endToEndId) ou
-- externo (EXTERNAL: informado por operador com evidência obrigatória no FileStorage).
-- -----------------------------------------------------------------------------
create table payment.payment (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by               uuid,
    updated_at               timestamptz    not null,
    updated_by               uuid,
    source                   varchar(20)    not null,
    method                   varchar(20)    not null,
    status                   varchar(20)    not null,
    creditor_id              uuid           not null references organization.creditor (id),
    debtor_id                uuid           not null,
    amount                   numeric(19, 2) not null,
    paid_at                  timestamptz    not null,
    instruction_id           uuid           references payment.payment_instruction (id),
    provider_transaction_id  varchar(64),
    external_reference       varchar(100),
    external_origin          varchar(30),
    target_kind              varchar(20),
    target_agreement_id      uuid,
    target_items             jsonb,
    target_strategy          varchar(20),
    note_encrypted           varchar(4000),
    informed_by              uuid,
    informed_at              timestamptz,
    review_required          boolean        not null,
    review_mode              varchar(20)    not null,
    reviewed_by              uuid,
    reviewed_at              timestamptz,
    review_note_encrypted    varchar(4000),
    confirmed_at             timestamptz,
    reconciled_at            timestamptz,
    reconciliation_mode      varchar(20),
    reconciled_by            uuid,
    statement_line_id        uuid,
    reversed_at              timestamptz,
    allocated_amount         numeric(19, 2) not null,
    evidence_key             varchar(500),
    evidence_name            varchar(255),
    evidence_content_type    varchar(100),
    evidence_size            bigint,
    evidence_sha256          varchar(64),
    constraint ck_payment_source check (source in ('PROVIDER', 'EXTERNAL')),
    constraint ck_payment_method check (method in ('PIX', 'BOLETO', 'CARD', 'TRANSFER', 'CASH', 'OTHER')),
    constraint ck_payment_status check (status in ('INFORMED', 'CONFIRMED', 'RECONCILED', 'REJECTED', 'REVERSED')),
    constraint ck_payment_external_origin check (external_origin is null
        or external_origin in ('AGENCY_ACCOUNT', 'CREDITOR_DIRECT', 'CREDITOR_REPORT', 'OTHER')),
    constraint ck_payment_target_kind check (target_kind is null or target_kind in ('AGREEMENT', 'TITLES')),
    constraint ck_payment_target_strategy check (target_strategy is null or target_strategy in ('OLDEST_FIRST', 'PROPORTIONAL')),
    constraint ck_payment_review_mode check (review_mode in ('PROVIDER', 'AUTO_LOW_RISK', 'DUAL_CONTROL', 'STATEMENT')),
    constraint ck_payment_reconciliation_mode check (reconciliation_mode is null
        or reconciliation_mode in ('PROVIDER_API', 'STATEMENT', 'MANUAL')),
    constraint ck_payment_amounts check (amount > 0 and allocated_amount >= 0 and allocated_amount <= amount),
    constraint ck_payment_external_evidence check (source <> 'EXTERNAL' or evidence_sha256 is not null)
);
create unique index uk_payment_provider_tx on payment.payment (tenant_id, provider_transaction_id)
    where provider_transaction_id is not null;
create index ix_payment_status on payment.payment (tenant_id, status, paid_at desc);
create index ix_payment_debtor on payment.payment (tenant_id, debtor_id, paid_at desc);
create index ix_payment_instruction on payment.payment (tenant_id, instruction_id) where instruction_id is not null;
create index ix_payment_external_reference on payment.payment (tenant_id, external_reference) where external_reference is not null;
create index ix_payment_review_queue on payment.payment (tenant_id, informed_at) where status = 'INFORMED';
select shared.enable_tenant_rls('payment.payment');

-- -----------------------------------------------------------------------------
-- Alocação: uma parte do pagamento para UM destino (parcelas de um acordo, títulos
-- avulsos ou devolução ao pagador). Cada alocação a acordo/títulos é publicada como um
-- PaymentConfirmed cujo paymentId é o id da alocação (unidade de baixa).
-- -----------------------------------------------------------------------------
create table payment.payment_allocation (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    payment_id            uuid           not null references payment.payment (id),
    kind                  varchar(20)    not null,
    origin                varchar(20)    not null,
    status                varchar(20)    not null,
    agreement_id          uuid,
    portfolio_id          uuid,
    case_id               uuid,
    amount                numeric(19, 2) not null,
    released_amount       numeric(19, 2) not null,
    compensated_amount    numeric(19, 2) not null,
    outcome               varchar(20)    not null,
    applied_amount        numeric(19, 2),
    unapplied_amount      numeric(19, 2),
    outcome_at            timestamptz,
    source_allocation_id  uuid           references payment.payment_allocation (id),
    refund_reference      varchar(100),
    reversal_event_id     uuid,
    reversed_at           timestamptz,
    constraint ck_allocation_kind check (kind in ('AGREEMENT', 'TITLES', 'REFUND')),
    constraint ck_allocation_origin check (origin in ('INITIAL', 'REALLOCATION')),
    constraint ck_allocation_status check (status in ('ACTIVE', 'REVERSED')),
    constraint ck_allocation_outcome check (outcome in ('PENDING', 'APPLIED', 'PARTIAL', 'UNAPPLIED', 'NOT_APPLICABLE')),
    constraint ck_allocation_agreement check ((kind = 'AGREEMENT') = (agreement_id is not null)),
    constraint ck_allocation_amounts check (amount > 0 and released_amount >= 0 and released_amount <= amount
        and compensated_amount >= 0 and compensated_amount <= amount)
);
create index ix_allocation_payment on payment.payment_allocation (tenant_id, payment_id);
create index ix_allocation_agreement on payment.payment_allocation (tenant_id, agreement_id) where agreement_id is not null;
create index ix_allocation_unapplied on payment.payment_allocation (tenant_id)
    where status = 'ACTIVE' and unapplied_amount > released_amount;
select shared.enable_tenant_rls('payment.payment_allocation');

create table payment.payment_allocation_line (
    allocation_id   uuid           not null references payment.payment_allocation (id),
    line_order      integer        not null,
    installment_id  uuid,
    title_id        uuid,
    amount          numeric(19, 2) not null,
    primary key (allocation_id, line_order),
    constraint ck_allocation_line_target check (installment_id is not null or title_id is not null),
    constraint ck_allocation_line_amount check (amount > 0)
);
create index ix_allocation_line_installment on payment.payment_allocation_line (installment_id) where installment_id is not null;
create index ix_allocation_line_title on payment.payment_allocation_line (title_id) where title_id is not null;
alter table payment.payment_allocation_line enable row level security;
alter table payment.payment_allocation_line force row level security;
create policy tenant_isolation on payment.payment_allocation_line
    using (exists (select 1 from payment.payment_allocation a where a.id = allocation_id))
    with check (exists (select 1 from payment.payment_allocation a where a.id = allocation_id));

-- -----------------------------------------------------------------------------
-- Estorno: evento compensatório (append-only). provider_reference = id da devolução
-- Pix (idempotência de notificações repetidas).
-- -----------------------------------------------------------------------------
create table payment.payment_reversal (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    payment_id          uuid           not null references payment.payment (id),
    amount              numeric(19, 2) not null,
    reason              varchar(30)    not null,
    source              varchar(20)    not null,
    provider_reference  varchar(64),
    note_encrypted      varchar(4000),
    previous_status     varchar(20)    not null,
    reversed_at         timestamptz    not null,
    constraint ck_reversal_reason check (reason in ('PROVIDER_REFUND', 'CHARGEBACK', 'BANK_RETURN', 'REGISTRATION_ERROR',
        'FRAUD', 'OTHER')),
    constraint ck_reversal_source check (source in ('PROVIDER', 'MANUAL')),
    constraint ck_reversal_amount check (amount > 0),
    constraint uk_reversal_payment unique (tenant_id, payment_id)
);
create unique index uk_reversal_provider_reference on payment.payment_reversal (tenant_id, provider_reference)
    where provider_reference is not null;
select shared.enable_tenant_rls('payment.payment_reversal');
create trigger trg_payment_reversal_append_only before update or delete on payment.payment_reversal
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Importação de extrato / arquivo de retorno (F15) e suas linhas (append-only quanto aos
-- dados do arquivo; só o resultado da conciliação muda). Linha repetida (mesma impressão
-- digital) em outro arquivo não gera nova baixa.
-- -----------------------------------------------------------------------------
create table payment.statement_import (
    id                uuid           not null primary key,
    tenant_id         uuid           not null references organization.tenant (id),
    version           bigint         not null,
    created_at        timestamptz    not null,
    created_by        uuid,
    updated_at        timestamptz    not null,
    updated_by        uuid,
    format            varchar(20)    not null,
    status            varchar(20)    not null,
    creditor_id       uuid           references organization.creditor (id),
    file_key          varchar(500)   not null,
    file_name         varchar(255)   not null,
    file_size         bigint         not null,
    file_sha256       varchar(64)    not null,
    requested_by      uuid           not null,
    started_at        timestamptz,
    finished_at       timestamptz,
    total_lines       integer        not null,
    reconciled_lines  integer        not null,
    created_payments  integer        not null,
    divergent_lines   integer        not null,
    ignored_lines     integer        not null,
    failure_code      varchar(60),
    constraint ck_statement_import_format check (format in ('CSV_GENERIC', 'CNAB_240', 'CNAB_400')),
    constraint ck_statement_import_status check (status in ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED')),
    constraint uk_statement_import_file unique (tenant_id, file_sha256)
);
create index ix_statement_import_created on payment.statement_import (tenant_id, created_at desc);
select shared.enable_tenant_rls('payment.statement_import');

create table payment.statement_line (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    import_id              uuid           not null references payment.statement_import (id),
    line_number            integer        not null,
    paid_on                date           not null,
    amount                 numeric(19, 2) not null,
    direction              varchar(1)     not null,
    transaction_id         varchar(64),
    reference              varchar(100),
    description_encrypted  varchar(2000),
    fingerprint            varchar(64)    not null,
    status                 varchar(30)    not null,
    payment_id             uuid           references payment.payment (id),
    constraint ck_statement_line_direction check (direction in ('C', 'D')),
    constraint ck_statement_line_status check (status in ('RECONCILED', 'CREATED_PAYMENT', 'DIVERGENT', 'ALREADY_RECONCILED',
        'IGNORED_DUPLICATE', 'IGNORED_DEBIT')),
    constraint ck_statement_line_amount check (amount > 0)
);
create index ix_statement_line_import on payment.statement_line (tenant_id, import_id, line_number);
create index ix_statement_line_fingerprint on payment.statement_line (tenant_id, fingerprint);
create index ix_statement_line_transaction on payment.statement_line (tenant_id, transaction_id) where transaction_id is not null;
select shared.enable_tenant_rls('payment.statement_line');

-- -----------------------------------------------------------------------------
-- Divergências para análise manual (fila de conciliação), com resolução auditada.
-- creditor_id nulo = sem correspondência (visível só a quem tem todos os credores).
-- -----------------------------------------------------------------------------
create table payment.divergence (
    id                   uuid           not null primary key,
    tenant_id            uuid           not null references organization.tenant (id),
    version              bigint         not null,
    created_at           timestamptz    not null,
    created_by           uuid,
    updated_at           timestamptz    not null,
    updated_by           uuid,
    type                 varchar(30)    not null,
    source               varchar(20)    not null,
    status               varchar(20)    not null,
    creditor_id          uuid           references organization.creditor (id),
    payment_id           uuid           references payment.payment (id),
    instruction_id       uuid           references payment.payment_instruction (id),
    statement_line_id    uuid           references payment.statement_line (id),
    provider_reference   varchar(64),
    amount               numeric(19, 2) not null,
    expected_amount      numeric(19, 2),
    occurred_on          date           not null,
    resolution           varchar(30),
    resolution_note_encrypted varchar(4000),
    resolved_payment_id  uuid           references payment.payment (id),
    resolved_by          uuid,
    resolved_at          timestamptz,
    constraint ck_divergence_type check (type in ('AMOUNT_MISMATCH', 'DUPLICATE_PAYMENT', 'UNMATCHED', 'NOT_CONFIRMED',
        'PARTIAL_REFUND')),
    constraint ck_divergence_source check (source in ('STATEMENT', 'WEBHOOK')),
    constraint ck_divergence_status check (status in ('OPEN', 'RESOLVED', 'DISMISSED')),
    constraint ck_divergence_resolution check (resolution is null or resolution in ('MATCHED_PAYMENT', 'ACCEPTED', 'DISMISSED'))
);
create index ix_divergence_open on payment.divergence (tenant_id, created_at) where status = 'OPEN';
create unique index uk_divergence_provider_reference on payment.divergence (tenant_id, type, provider_reference)
    where provider_reference is not null;
select shared.enable_tenant_rls('payment.divergence');

grant usage on schema payment to app_runtime;
grant select, insert, update, delete on all tables in schema payment to app_runtime;
-- histórico financeiro: nada é removido pela aplicação
revoke delete on payment.payment, payment.payment_allocation, payment.payment_allocation_line, payment.payment_instruction,
    payment.statement_import, payment.statement_line, payment.divergence, payment.pix_account, payment.webhook_route,
    payment.settings from app_runtime;
revoke update, delete on payment.payment_reversal from app_runtime;
-- linhas de alocação são imutáveis (a alocação inteira é revertida, nunca editada)
revoke update on payment.payment_allocation_line from app_runtime;
