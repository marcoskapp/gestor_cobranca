-- =============================================================================
-- Lote bloqueado por titular anonimizado (SEG-20261124-D01h, decisões 1469 e 1488–1493): o lote com linha do titular
-- (pelo devedor gravado ou pelo título do credor) fica bloqueado — sem reprocessar, sem nova conferência, sem baixar o
-- arquivo, e o mesmo arquivo (SHA-256) é recusado no reenvio ao credor. O registro do bloqueio guarda as linhas
-- encontradas, com a referência do título mascarada, para o pedido de novo arquivo ao credor (fatia 5).
-- Sem dado pessoal: ids, número da linha, referência mascarada e como a linha foi encontrada. Retenção: enquanto o lote
-- existir (o lote não se apaga), como evidência do bloqueio; o aviso do compliance pode sair sem quebrar o registro.
-- =============================================================================

alter table ingestion.import_batch
    add column blocked_reason varchar(30),
    add column blocked_at     timestamptz,
    add constraint ck_import_batch_blocked check ((blocked_reason is null) = (blocked_at is null)),
    add constraint ck_import_batch_blocked_reason check (blocked_reason is null or blocked_reason in ('SUBJECT_ANONYMIZED'));
-- reenvio do mesmo arquivo de lote bloqueado (recusado mesmo com confirmação)
create index ix_import_batch_blocked_sha on ingestion.import_batch (tenant_id, creditor_id, file_sha256)
    where blocked_reason is not null;
-- detecção pelo título do credor também nas linhas sem gravação (prévia, erro). Pré-produção: criado na transação da
-- migration; antes de haver dados, índice em tabela grande vai em migration própria com concurrently (decisão 1494)
create index ix_import_batch_row_by_external_id on ingestion.import_batch_row (tenant_id, external_id, batch_id);

create table ingestion.import_subject_block (
    id               uuid          not null primary key,
    tenant_id        uuid          not null references organization.tenant (id),
    version          bigint        not null,
    created_at       timestamptz   not null,
    created_by       uuid,
    updated_at       timestamptz   not null,
    updated_by       uuid,
    batch_id         uuid          not null references ingestion.import_batch (id),
    creditor_id      uuid          not null references organization.creditor (id),
    notice_id        uuid          references compliance.creditor_erasure_notice (id) on delete set null,
    source_event_id  uuid          not null,
    effect           varchar(20)   not null,
    review_id        uuid,
    lines            integer       not null,
    constraint uk_import_subject_block unique (tenant_id, batch_id, source_event_id),
    constraint ck_import_subject_block_effect check (effect in ('CANCELLED_NOW', 'STOP_REQUESTED', 'ALREADY_FINISHED')),
    constraint ck_import_subject_block_lines check (lines >= 0)
);
create index ix_import_subject_block_batch on ingestion.import_subject_block (tenant_id, batch_id);
select shared.enable_tenant_rls('ingestion.import_subject_block');
grant select, insert on ingestion.import_subject_block to app_runtime;
revoke update, delete on ingestion.import_subject_block from app_runtime;

-- Linhas do titular no arquivo (append-only; isolamento pelo bloqueio).
create table ingestion.import_subject_block_line (
    block_id    uuid           not null references ingestion.import_subject_block (id),
    position    integer        not null,
    row_number  bigint         not null,
    reference   varchar(1440)  not null,
    matched_by  varchar(20)    not null,
    primary key (block_id, position),
    constraint ck_import_subject_block_line_row check (row_number > 0),
    constraint ck_import_subject_block_line_match check (matched_by in ('DEBTOR', 'TITLE_REFERENCE'))
);
alter table ingestion.import_subject_block_line enable row level security;
alter table ingestion.import_subject_block_line force row level security;
create policy tenant_isolation on ingestion.import_subject_block_line
    using (exists (select 1 from ingestion.import_subject_block b where b.id = block_id))
    with check (exists (select 1 from ingestion.import_subject_block b where b.id = block_id));
grant select, insert on ingestion.import_subject_block_line to app_runtime;
revoke update, delete on ingestion.import_subject_block_line from app_runtime;
