-- =============================================================================
-- Evidência do pedido de novo arquivo no aviso da eliminação ao credor (SEG-20261124-D01h, decisão 1497): o aviso
-- (compliance) lista os lotes bloqueados ligados a ele pela porta compliance :: spi NewFileRequests, que o ingestion
-- implementa buscando os registros de bloqueio pelo aviso. Pré-produção: criado na transação da migration (decisão 1494).
-- =============================================================================
create index ix_import_subject_block_notice on ingestion.import_subject_block (tenant_id, notice_id, created_at)
    where notice_id is not null;
