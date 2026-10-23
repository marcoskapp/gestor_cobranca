-- =============================================================================
-- Módulo ingestion: layouts de importação por credor, lotes de carga (CSV, XLSX,
-- API), ocorrências por linha e registro das linhas de cada lote (M02, F04, 5.2).
--
-- O arquivo original fica no armazenamento de arquivos (S3, categoria IMPORT); aqui
-- ficam a chave, o nome, o tamanho e o SHA-256, que identifica reenvios do mesmo
-- conteúdo na mesma carteira. Ocorrências e linhas registradas são append-only.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Layout de importação: formato do arquivo e mapeamento coluna → campo canônico,
-- por credor e, opcionalmente, restrito a uma carteira (portfolio_id).
-- Cada versão é imutável; publicar uma versão nova desativa a anterior (único campo
-- alterável). "version" é o lock otimista (BaseEntity); a versão do layout é
-- layout_version.
-- -----------------------------------------------------------------------------
create table ingestion.import_layout (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    creditor_id     uuid           not null references organization.creditor (id),
    portfolio_id    uuid           references organization.portfolio (id),
    code            varchar(40)    not null,
    layout_version  integer        not null,
    name            varchar(150)   not null,
    format          varchar(10)    not null,
    csv_delimiter   varchar(1),
    csv_encoding    varchar(20),
    has_header      boolean        not null,
    sheet_name      varchar(31),
    active          boolean        not null,
    constraint uk_import_layout_version unique (tenant_id, creditor_id, code, layout_version),
    constraint ck_import_layout_format check (format in ('CSV', 'XLSX')),
    constraint ck_import_layout_version check (layout_version > 0),
    constraint ck_import_layout_csv check (
        (format = 'CSV' and csv_delimiter is not null and csv_encoding is not null and sheet_name is null)
        or (format = 'XLSX' and csv_delimiter is null and csv_encoding is null))
);
-- no máximo uma versão ativa por código de layout do credor
create unique index uk_import_layout_active on ingestion.import_layout (tenant_id, creditor_id, code) where active;
create index ix_import_layout_creditor on ingestion.import_layout (tenant_id, creditor_id, code, layout_version desc);
select shared.enable_tenant_rls('ingestion.import_layout');

-- Colunas do layout: cabeçalho (arquivo com cabeçalho) ou posição 1..n (sem cabeçalho).
-- RLS pela linha pai.
create table ingestion.import_layout_column (
    layout_id  uuid          not null references ingestion.import_layout (id),
    field      varchar(40)   not null,
    header     varchar(120),
    position   integer,
    primary key (layout_id, field),
    constraint ck_import_layout_column_ref check ((header is null) <> (position is null)),
    constraint ck_import_layout_column_position check (position is null or position > 0)
);
alter table ingestion.import_layout_column enable row level security;
alter table ingestion.import_layout_column force row level security;
create policy tenant_isolation on ingestion.import_layout_column
    using (exists (select 1 from ingestion.import_layout l where l.id = layout_id))
    with check (exists (select 1 from ingestion.import_layout l where l.id = layout_id));

-- -----------------------------------------------------------------------------
-- Lote de importação. last_processed_row é o checkpoint: última linha do arquivo cujo
-- bloco foi confirmado (no mesmo commit das ocorrências e contadores do bloco).
-- Lotes sem layout próprio usam o layout canônico (layout_id nulo, código PADRAO).
-- forced: o usuário confirmou o reprocessamento de um arquivo já importado com
-- sucesso na carteira (duplicate_of_batch_id), com justificativa.
-- -----------------------------------------------------------------------------
create table ingestion.import_batch (
    id                     uuid            not null primary key,
    tenant_id              uuid            not null references organization.tenant (id),
    version                bigint          not null,
    created_at             timestamptz     not null,
    created_by             uuid,
    updated_at             timestamptz     not null,
    updated_by             uuid,
    creditor_id            uuid            not null references organization.creditor (id),
    portfolio_id           uuid            not null references organization.portfolio (id),
    layout_id              uuid            references ingestion.import_layout (id),
    layout_code            varchar(40)     not null,
    layout_version         integer         not null,
    source                 varchar(10)     not null,
    mode                   varchar(20)     not null,
    status                 varchar(30)     not null,
    status_reason          varchar(500),
    file_key               varchar(300)    not null,
    file_name              varchar(200)    not null,
    file_content_type      varchar(120),
    file_size              bigint          not null,
    file_sha256            varchar(64)     not null,
    forced                 boolean         not null,
    force_reason           varchar(500),
    duplicate_of_batch_id  uuid            references ingestion.import_batch (id),
    reprocess_of_batch_id  uuid            references ingestion.import_batch (id),
    rows_read              integer         not null,
    rows_valid             integer         not null,
    rows_with_error        integer         not null,
    errors                 integer         not null,
    warnings               integer         not null,
    debtors_created        integer         not null,
    debtors_updated        integer         not null,
    titles_created         integer         not null,
    titles_updated         integer         not null,
    titles_unchanged       integer         not null,
    titles_skipped         integer         not null,
    titles_suspended       integer         not null,
    principal_total        numeric(19, 2)  not null,
    issues_recorded        integer         not null,
    last_processed_row     bigint          not null,
    total_rows             bigint,
    cancel_requested_at    timestamptz,
    cancel_requested_by    uuid,
    started_at             timestamptz,
    finished_at            timestamptz,
    constraint ck_import_batch_source check (source in ('CSV', 'XLSX', 'API')),
    constraint ck_import_batch_mode check (mode in ('VALIDATE_ONLY', 'IMPORT')),
    constraint ck_import_batch_status check (status in ('RECEIVED', 'VALIDATING', 'VALIDATED', 'PROCESSING', 'COMPLETED',
                                                        'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')),
    -- pré-visualização só valida; importação grava sem passar pelos estados de validação
    constraint ck_import_batch_mode_status check (
        (mode = 'IMPORT' and status not in ('VALIDATING', 'VALIDATED'))
        or (mode = 'VALIDATE_ONLY' and status not in ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS'))),
    constraint ck_import_batch_sha256 check (file_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_import_batch_size check (file_size >= 0),
    constraint ck_import_batch_forced check (
        (forced and duplicate_of_batch_id is not null and force_reason is not null)
        or (not forced and duplicate_of_batch_id is null and force_reason is null)),
    constraint ck_import_batch_counters check (
        rows_read >= 0 and rows_valid >= 0 and rows_with_error >= 0 and errors >= rows_with_error and warnings >= 0
        and debtors_created >= 0 and debtors_updated >= 0 and titles_created >= 0 and titles_updated >= 0
        and titles_unchanged >= 0 and titles_skipped >= 0 and titles_suspended >= 0 and principal_total >= 0
        and issues_recorded >= 0 and last_processed_row >= 0 and (total_rows is null or total_rows >= 0)),
    constraint ck_import_batch_rows check (rows_read = rows_valid + rows_with_error),
    constraint ck_import_batch_validate_only check (
        mode = 'IMPORT' or (debtors_created = 0 and debtors_updated = 0 and titles_created = 0
                            and titles_updated = 0 and titles_unchanged = 0 and titles_skipped = 0
                            and titles_suspended = 0)),
    constraint ck_import_batch_finished check (
        (status in ('VALIDATED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED')) = (finished_at is not null)),
    constraint ck_import_batch_dates check (finished_at is null or started_at is null or finished_at >= started_at),
    -- leitura prévia concluída antes de processar
    constraint ck_import_batch_started check (status in ('RECEIVED', 'CANCELLED', 'FAILED') or total_rows is not null),
    constraint ck_import_batch_cancel check ((cancel_requested_at is null) = (cancel_requested_by is null))
);
create index ix_import_batch_portfolio on ingestion.import_batch (tenant_id, portfolio_id, status, created_at desc);
create index ix_import_batch_creditor on ingestion.import_batch (tenant_id, creditor_id, created_at desc);
create index ix_import_batch_created on ingestion.import_batch (tenant_id, created_at desc);
-- reenvio do mesmo arquivo na mesma carteira
create index ix_import_batch_sha256 on ingestion.import_batch (tenant_id, portfolio_id, file_sha256);
-- o mesmo arquivo não é importado duas vezes ao mesmo tempo na carteira (envios simultâneos)
create unique index uk_import_batch_in_flight on ingestion.import_batch (tenant_id, portfolio_id, file_sha256)
    where mode = 'IMPORT' and status in ('RECEIVED', 'PROCESSING');
select shared.enable_tenant_rls('ingestion.import_batch');

-- -----------------------------------------------------------------------------
-- Ocorrências por linha (append-only). row_number = 0 indica problema do arquivo
-- (cabeçalho, estrutura). value_excerpt é sempre mascarado pela aplicação.
-- -----------------------------------------------------------------------------
create table ingestion.import_row_issue (
    id             uuid           not null primary key,
    tenant_id      uuid           not null references organization.tenant (id),
    version        bigint         not null,
    created_at     timestamptz    not null,
    created_by     uuid,
    updated_at     timestamptz    not null,
    updated_by     uuid,
    batch_id       uuid           not null references ingestion.import_batch (id),
    row_number     bigint         not null,
    severity       varchar(10)    not null,
    field          varchar(60),
    code           varchar(80)    not null,
    message        varchar(500)   not null,
    value_excerpt  varchar(120),
    constraint ck_import_row_issue_row check (row_number >= 0),
    constraint ck_import_row_issue_severity check (severity in ('ERROR', 'WARNING'))
);
create index ix_import_row_issue_batch on ingestion.import_row_issue (tenant_id, batch_id, row_number, severity);
select shared.enable_tenant_rls('ingestion.import_row_issue');
create trigger trg_import_row_issue_append_only before update or delete on ingestion.import_row_issue
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Linhas do lote por identificador externo do título (append-only):
--   * title_id/debtor_id referenciam o módulo debt apenas por id (sem chave estrangeira:
--     o ingestion conversa com o debt só pela porta DebtIngestionPort);
--   * duplicidade de título no mesmo arquivo, inclusive entre blocos e após retomada;
--   * na importação, a linha gravada no módulo debt é registrada na MESMA transação,
--     com o resultado: no reprocessamento após uma queda a linha é reconhecida e o
--     resultado é reaproveitado, sem nova gravação nem contagem dupla;
--   * rastreabilidade: qual linha de qual carga criou ou alterou cada título (RB01).
-- Linhas com erro e da pré-visualização ficam sem resultado de gravação.
-- -----------------------------------------------------------------------------
create table ingestion.import_batch_row (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    batch_id        uuid           not null references ingestion.import_batch (id),
    external_id     varchar(80)    not null,
    row_number      bigint         not null,
    title_id        uuid,
    debtor_id       uuid,
    title_outcome   varchar(10),
    debtor_outcome  varchar(10),
    warning_count   integer        not null,
    title_suspended boolean        not null,
    constraint uk_import_batch_row_external unique (tenant_id, batch_id, external_id),
    constraint uk_import_batch_row_number unique (tenant_id, batch_id, row_number),
    constraint ck_import_batch_row_number check (row_number > 0),
    constraint ck_import_batch_row_warnings check (warning_count >= 0),
    constraint ck_import_batch_row_outcome check (
        -- registrada sem gravação (erro ou pré-visualização)
        (title_id is null and debtor_id is null and title_outcome is null and debtor_outcome is null
         and not title_suspended)
        -- gravada
        or (title_id is not null and debtor_id is not null
            and title_outcome in ('CREATED', 'UPDATED', 'UNCHANGED')
            and debtor_outcome in ('CREATED', 'UPDATED', 'UNCHANGED'))
        -- não gravada por decisão da carga (título informado como pago/cancelado pelo credor)
        or (title_outcome = 'SKIPPED' and debtor_id is null and debtor_outcome is null
            and (not title_suspended or title_id is not null)))
);
create index ix_import_batch_row_title on ingestion.import_batch_row (tenant_id, title_id) where title_id is not null;
-- título já carregado pelo identificador externo (tratamento de títulos informados como pagos/cancelados)
create index ix_import_batch_row_external_title on ingestion.import_batch_row (tenant_id, external_id, created_at desc)
    where title_id is not null;
create index ix_import_batch_row_debtor on ingestion.import_batch_row (tenant_id, batch_id, debtor_id) where debtor_id is not null;
select shared.enable_tenant_rls('ingestion.import_batch_row');
create trigger trg_import_batch_row_append_only before update or delete on ingestion.import_batch_row
    for each row execute function shared.forbid_mutation();

grant select, insert, update, delete on all tables in schema ingestion to app_runtime;
-- histórico da carga é imutável
revoke update, delete on ingestion.import_row_issue, ingestion.import_batch_row from app_runtime;
-- lotes e layouts não são removidos (rastreabilidade das cargas, 5.2); colunas de layout são imutáveis
revoke delete on ingestion.import_batch, ingestion.import_layout from app_runtime;
revoke update, delete on ingestion.import_layout_column from app_runtime;
