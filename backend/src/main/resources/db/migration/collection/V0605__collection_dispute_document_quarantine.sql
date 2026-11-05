-- =============================================================================
-- Quarentena dos documentos enviados pelo credor pela API pública na resposta da
-- contestação (decisões 814–819). O arquivo fica na categoria QUARANTINE do
-- armazenamento até a verificação de malware; limpo (ou com a verificação
-- desligada, NOT_SCANNED) vira evidência da contestação (dispute_evidence, append-only);
-- infectado tem o conteúdo apagado e o registro fica com a assinatura detectada.
-- =============================================================================
create table collection.dispute_document_quarantine (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    dispute_id           uuid          not null references collection.dispute (id),
    creditor_id          uuid          not null,
    creditor_response_id uuid          not null references collection.dispute_creditor_response (id),
    file_key             varchar(500)  not null,
    file_name            varchar(200)  not null,
    content_type         varchar(100)  not null,
    size_bytes           bigint        not null,
    sha256               varchar(64)   not null,
    status               varchar(20)   not null,
    verdict              varchar(20),
    engine               varchar(30),
    signature            varchar(200),
    attempts             int           not null,
    scanned_at           timestamptz,
    evidence_id          uuid references collection.dispute_evidence (id),
    constraint ck_dispute_quarantine_status check (status in ('PENDING', 'RELEASED', 'INFECTED', 'FAILED')),
    constraint ck_dispute_quarantine_verdict check (verdict is null or verdict in ('CLEAN', 'INFECTED', 'NOT_SCANNED')),
    constraint ck_dispute_quarantine_type check (content_type in ('application/pdf', 'image/png', 'image/jpeg')),
    constraint ck_dispute_quarantine_size check (size_bytes > 0),
    constraint ck_dispute_quarantine_released check ((status = 'RELEASED') = (evidence_id is not null))
);
create index ix_dispute_quarantine_response on collection.dispute_document_quarantine (tenant_id, creditor_response_id);
create index ix_dispute_quarantine_pending on collection.dispute_document_quarantine (tenant_id, created_at)
    where status = 'PENDING';
select shared.enable_tenant_rls('collection.dispute_document_quarantine');
grant select, insert, update on collection.dispute_document_quarantine to app_runtime;
