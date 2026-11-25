-- LGPD (SEG-20261124-D01, decisões 1460 e 1461): o arquivo de carga (ou o PDF de origem) com linha do titular
-- anonimizado sai inteiro; se algum lote com o arquivo ainda está em processamento ou em conferência aberta, a
-- remoção fica pendente aqui e o job diário ingestion.file-retention a conclui quando o lote encerra (em vez de o
-- consumidor do evento falhar e ser reentregue sem limite). Só a chave do arquivo e ids: nenhum dado pessoal.
create table ingestion.pending_file_erasure (
    id               uuid         not null primary key,
    tenant_id        uuid         not null references organization.tenant (id),
    file_key         varchar(500),
    pdf_import_id    uuid         references ingestion.pdf_import (id),
    source_event_id  uuid         not null,
    created_at       timestamptz  not null,
    constraint ck_pending_file_erasure_target check ((file_key is null) <> (pdf_import_id is null))
);
create unique index uk_pending_file_erasure_key on ingestion.pending_file_erasure (tenant_id, file_key)
    where file_key is not null;
create unique index uk_pending_file_erasure_pdf on ingestion.pending_file_erasure (tenant_id, pdf_import_id)
    where pdf_import_id is not null;
select shared.enable_tenant_rls('ingestion.pending_file_erasure');
grant select, insert, delete on ingestion.pending_file_erasure to app_runtime;

-- consultas da anonimização partindo do devedor (não dos lotes da assessoria)
create index ix_import_batch_row_by_debtor on ingestion.import_batch_row (tenant_id, debtor_id, batch_id)
    where debtor_id is not null;
