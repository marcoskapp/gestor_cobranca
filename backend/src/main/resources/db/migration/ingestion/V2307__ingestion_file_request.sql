-- =============================================================================
-- Pedido de novo arquivo ao credor (SEG-20261124-D01h, fatia 5; decisões 1469 e 1497): o lote bloqueado por titular
-- anonimizado mostra um texto pronto do pedido (linhas com a referência mascarada, sem nome nem documento) e quem
-- importa registra que pediu — quando e por qual canal, uma vez só, sem texto livre. Retenção: enquanto o lote existir.
-- =============================================================================
create table ingestion.import_file_request (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    batch_id       uuid          not null references ingestion.import_batch (id),
    creditor_id    uuid          not null references organization.creditor (id),
    sent_at        timestamptz   not null,
    channel        varchar(20)   not null,
    sent_by        uuid          not null,
    constraint uk_import_file_request_batch unique (tenant_id, batch_id),
    constraint ck_import_file_request_channel check (channel in ('EMAIL', 'PHONE', 'LETTER', 'MEETING', 'OTHER'))
);
select shared.enable_tenant_rls('ingestion.import_file_request');
grant select, insert on ingestion.import_file_request to app_runtime;
revoke update, delete on ingestion.import_file_request from app_runtime;
