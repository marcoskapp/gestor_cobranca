-- =============================================================================
-- Módulo ingestion: conferência da importação (assistente em passos) — formato
-- numérico por coluna, totais de controle, alertas de valor (ticket médio) e
-- efetivação com aprovação de segunda pessoa. Desenho em
-- docs/tecnico/desenho/importacao.md.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Conferência: estado do assistente no servidor. current_batch_id é a prévia
-- (VALIDATE_ONLY) corrente; mudar o formato depois da validação cria outra prévia
-- do mesmo arquivo. Confirmações valem para a prévia em que foram feitas.
-- -----------------------------------------------------------------------------
create table ingestion.import_review (
    id                         uuid            not null primary key,
    tenant_id                  uuid            not null references organization.tenant (id),
    version                    bigint          not null,
    created_at                 timestamptz     not null,
    created_by                 uuid,
    updated_at                 timestamptz     not null,
    updated_by                 uuid,
    creditor_id                uuid            not null references organization.creditor (id),
    portfolio_id               uuid            not null references organization.portfolio (id),
    status                     varchar(20)     not null,
    current_step               integer         not null,
    -- referências cruzadas com import_batch.review_id: verificadas no commit
    current_batch_id           uuid            not null references ingestion.import_batch (id) deferrable initially deferred,
    previews                   integer         not null,
    format_confirmed_batch_id  uuid            references ingestion.import_batch (id),
    format_confirmed_at        timestamptz,
    format_confirmed_by        uuid,
    totals_last_batch_id       uuid            references ingestion.import_batch (id),
    totals_matched_batch_id    uuid            references ingestion.import_batch (id),
    totals_checked_at          timestamptz,
    totals_checks              integer         not null,
    totals_mismatches          integer         not null,
    analysis_batch_id          uuid            references ingestion.import_batch (id),
    analysis_status            varchar(20)     not null,
    analysis_finished_at       timestamptz,
    alerts_total               integer         not null,
    alerts_truncated           boolean         not null,
    analysis_summary           jsonb,
    commit_requested_by        uuid,
    commit_requested_at        timestamptz,
    impact                     jsonb,
    approval_required          boolean         not null,
    approval_threshold         numeric(19, 2),
    force_reason               varchar(500),
    decided_by                 uuid,
    decided_at                 timestamptz,
    decision_note              varchar(500),
    import_batch_id            uuid            references ingestion.import_batch (id),
    cancelled_at               timestamptz,
    cancelled_by               uuid,
    cancel_reason              varchar(500),
    constraint ck_import_review_status check (status in ('OPEN', 'AWAITING_APPROVAL', 'COMMITTED', 'REJECTED', 'CANCELLED')),
    constraint ck_import_review_analysis check (analysis_status in ('NOT_STARTED', 'PENDING', 'DONE', 'FAILED')),
    constraint ck_import_review_step check (current_step between 1 and 6),
    constraint ck_import_review_counters check (previews >= 1 and totals_checks >= 0 and totals_mismatches >= 0
                                                and totals_mismatches <= totals_checks and alerts_total >= 0),
    constraint ck_import_review_committed check ((status = 'COMMITTED') = (import_batch_id is not null)),
    constraint ck_import_review_commit check (status = 'OPEN' or status = 'CANCELLED' or commit_requested_at is not null),
    constraint ck_import_review_approval check (
        not approval_required or status not in ('COMMITTED', 'REJECTED') or decided_by is not null),
    -- segregação: quem aprova/recusa não enviou o arquivo nem pediu a efetivação
    constraint ck_import_review_second_person check (
        decided_by is null or (decided_by <> commit_requested_by and (created_by is null or decided_by <> created_by))),
    constraint ck_import_review_cancel check ((status = 'CANCELLED') = (cancelled_at is not null))
);
create index ix_import_review_portfolio on ingestion.import_review (tenant_id, portfolio_id, status, created_at desc);
create index ix_import_review_creditor on ingestion.import_review (tenant_id, creditor_id, status, created_at desc);
create index ix_import_review_status on ingestion.import_review (tenant_id, status, created_at desc);
select shared.enable_tenant_rls('ingestion.import_review');

-- -----------------------------------------------------------------------------
-- Lote: conferência vinculada, formato numérico aplicado e analisado, totais de
-- controle da integração e linhas excluídas na conferência. AWAITING_FORMAT:
-- prévia de conferência parada após a leitura prévia, aguardando a decisão sobre
-- colunas ambíguas ou misturadas (nenhuma linha foi validada).
-- -----------------------------------------------------------------------------
alter table ingestion.import_batch
    add column review_id        uuid references ingestion.import_review (id) deferrable initially deferred,
    add column number_format    jsonb,
    add column format_analysis  jsonb,
    add column control_totals   jsonb,
    add column rows_excluded    integer not null default 0;

alter table ingestion.import_batch drop constraint ck_import_batch_status;
alter table ingestion.import_batch add constraint ck_import_batch_status check (
    status in ('RECEIVED', 'AWAITING_FORMAT', 'VALIDATING', 'VALIDATED', 'PROCESSING', 'COMPLETED',
               'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'));
alter table ingestion.import_batch drop constraint ck_import_batch_mode_status;
alter table ingestion.import_batch add constraint ck_import_batch_mode_status check (
    (mode = 'IMPORT' and status not in ('AWAITING_FORMAT', 'VALIDATING', 'VALIDATED'))
    or (mode = 'VALIDATE_ONLY' and status not in ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS')));
alter table ingestion.import_batch add constraint ck_import_batch_awaiting_format check (
    status <> 'AWAITING_FORMAT' or (review_id is not null and format_analysis is not null));
alter table ingestion.import_batch add constraint ck_import_batch_excluded check (
    rows_excluded >= 0 and (mode = 'IMPORT' or rows_excluded = 0));
create index ix_import_batch_review on ingestion.import_batch (tenant_id, review_id) where review_id is not null;

-- -----------------------------------------------------------------------------
-- Conferências de totais (append-only): informado × calculado e as dicas
-- apresentadas (razão 10^n, interpretação alternativa, linhas suspeitas).
-- -----------------------------------------------------------------------------
create table ingestion.import_totals_check (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    review_id           uuid            not null references ingestion.import_review (id),
    batch_id            uuid            not null references ingestion.import_batch (id),
    entered_rows        bigint,
    entered_principal   numeric(19, 2),
    entered_balance     numeric(19, 2),
    entered_contract    numeric(19, 2),
    computed_rows       bigint          not null,
    computed_principal  numeric(19, 2)  not null,
    computed_balance    numeric(19, 2)  not null,
    computed_contract   numeric(19, 2)  not null,
    matched             boolean         not null,
    scale_exponent      integer,
    hints               jsonb,
    constraint ck_import_totals_check_entered check (
        entered_rows is not null or entered_principal is not null or entered_balance is not null
        or entered_contract is not null),
    constraint ck_import_totals_check_scale check (scale_exponent is null or scale_exponent between -3 and 3)
);
create index ix_import_totals_check_review on ingestion.import_totals_check (tenant_id, review_id, created_at desc);
select shared.enable_tenant_rls('ingestion.import_totals_check');
create trigger trg_import_totals_check_append_only before update or delete on ingestion.import_totals_check
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Alertas de valor da prévia. Só as colunas de decisão mudam depois de criado.
-- row_number = 0: alerta do arquivo como um todo (não exclui linhas).
-- -----------------------------------------------------------------------------
create table ingestion.import_review_alert (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    review_id           uuid            not null references ingestion.import_review (id),
    batch_id            uuid            not null references ingestion.import_batch (id),
    row_number          bigint          not null,
    external_id         varchar(80),
    type                varchar(40)     not null,
    priority            integer         not null,
    field               varchar(60),
    amount              numeric(19, 2),
    expected_low        numeric(19, 2),
    expected_high       numeric(19, 2),
    reference           varchar(30),
    reference_size      bigint,
    suggested_exponent  integer,
    detail              varchar(500)    not null,
    status              varchar(20)     not null,
    decided_by          uuid,
    decided_at          timestamptz,
    justification       varchar(500),
    constraint ck_import_review_alert_type check (type in ('DECIMAL_SCALE', 'FILE_SCALE_SHIFT', 'CENTS_PATTERN',
        'AMOUNT_HIGH', 'BALANCE_ABOVE_PRINCIPAL', 'CONTRACT_INSTALLMENTS_MISMATCH', 'DEBTOR_MAGNITUDE_SPREAD',
        'AMOUNT_LOW', 'DUE_DATE_TOO_FUTURE', 'DUE_DATE_TOO_OLD')),
    constraint ck_import_review_alert_reference check (reference is null or reference in ('HISTORY_PRODUCT',
        'HISTORY_PORTFOLIO', 'HISTORY_CREDITOR', 'FILE_PRODUCT', 'FILE')),
    constraint ck_import_review_alert_status check (status in ('OPEN', 'CONFIRMED', 'EXCLUDED')),
    constraint ck_import_review_alert_row check (row_number >= 0 and (row_number > 0 or status <> 'EXCLUDED')),
    constraint ck_import_review_alert_decision check (
        (status = 'OPEN' and decided_by is null and justification is null)
        or (status <> 'OPEN' and decided_at is not null and justification is not null))
);
create index ix_import_review_alert_batch on ingestion.import_review_alert (tenant_id, batch_id, status, priority desc, row_number);
create index ix_import_review_alert_row on ingestion.import_review_alert (tenant_id, batch_id, row_number);
select shared.enable_tenant_rls('ingestion.import_review_alert');

-- -----------------------------------------------------------------------------
-- Valores por linha da prévia de conferência (append-only, sem dado pessoal):
-- base dos totais por dimensão e da análise de valores. debtor_key é o blind
-- index (HMAC por assessoria) do documento — nunca o documento.
-- -----------------------------------------------------------------------------
create table ingestion.import_row_value (
    tenant_id            uuid            not null references organization.tenant (id),
    batch_id             uuid            not null references ingestion.import_batch (id),
    row_number           bigint          not null,
    valid                boolean         not null,
    principal            numeric(19, 2),
    principal_invalid    boolean         not null,
    principal_ambiguous  boolean         not null,
    balance              numeric(19, 2),
    balance_invalid      boolean         not null,
    contract_amount      numeric(19, 2),
    contract_invalid     boolean         not null,
    due_date             date,
    situation            varchar(12),
    product              varchar(120),
    contract_number      varchar(80),
    external_id          varchar(80),
    debtor_key           varchar(64),
    primary key (tenant_id, batch_id, row_number),
    constraint ck_import_row_value_row check (row_number > 0)
);
create index ix_import_row_value_debtor on ingestion.import_row_value (tenant_id, batch_id, debtor_key)
    where debtor_key is not null and valid;
create index ix_import_row_value_contract on ingestion.import_row_value (tenant_id, batch_id, contract_number)
    where contract_number is not null and valid;
select shared.enable_tenant_rls('ingestion.import_row_value');
create trigger trg_import_row_value_append_only before update or delete on ingestion.import_row_value
    for each row execute function shared.forbid_mutation();

grant select, insert, update on ingestion.import_review, ingestion.import_review_alert to app_runtime;
grant select, insert on ingestion.import_totals_check, ingestion.import_row_value to app_runtime;
-- alertas: só a decisão muda depois de criado
revoke update on ingestion.import_review_alert from app_runtime;
grant update (version, updated_at, updated_by, status, decided_by, decided_at, justification)
    on ingestion.import_review_alert to app_runtime;
