-- Descarte dos arquivos de importação pela política IMPORT_FILES do compliance (LGPD, arts. 15 e 16 — docs/tecnico/
-- desenho/importacao.md, "Retenção"): o lote fica (contadores, hash, ocorrências mascaradas e trilha); o arquivo sai
-- do armazenamento e a chave é apagada em todos os lotes que o compartilham (prévia, importação e reprocessamento).
-- Só DDL, sem leitura de dados.

alter table ingestion.import_batch alter column file_key drop not null;
alter table ingestion.import_batch add column file_deleted_at timestamptz;
alter table ingestion.import_batch add constraint ck_import_batch_file_deleted
    check ((file_key is null) = (file_deleted_at is not null));
create index ix_import_batch_file_retention on ingestion.import_batch (tenant_id, file_key) where file_key is not null;
