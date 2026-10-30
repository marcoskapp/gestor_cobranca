-- =============================================================================
-- Módulo ingestion: Importação PDF inteligente — PDF recebido, mapa de extração
-- (versionado), mapas salvos por credor (impressão digital do layout) e o vínculo
-- com a conferência da importação gerada na aprovação. Desenho em
-- docs/tecnico/desenho/importacao-pdf.md. Faixa de versões V2300–V2399.
--
-- Nenhuma coluna guarda valor do devedor em claro: o mapa descreve rótulos e
-- posições; os títulos são recalculados do arquivo; a leitura visual (PDF sem
-- texto) e a justificativa ficam cifradas pela aplicação (AES-256-GCM).
-- =============================================================================

create table ingestion.pdf_layout_template (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    version            bigint        not null,
    created_at         timestamptz   not null,
    created_by         uuid,
    updated_at         timestamptz   not null,
    updated_by         uuid,
    creditor_id        uuid          not null references organization.creditor (id),
    name               varchar(120)  not null,
    fingerprint        varchar(64)   not null,
    template_version   integer       not null,
    map                jsonb         not null,
    source_import_id   uuid,
    active             boolean       not null,
    uses               integer       not null,
    last_used_at       timestamptz,
    deactivated_at     timestamptz,
    deactivated_by     uuid,
    constraint ck_pdf_layout_template_version check (template_version >= 1 and uses >= 0),
    constraint ck_pdf_layout_template_fingerprint check (fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_pdf_layout_template_active check (active = (deactivated_at is null)),
    constraint uq_pdf_layout_template_version unique (tenant_id, creditor_id, fingerprint, template_version)
);
-- um mapa ativo por layout e credor
create unique index uq_pdf_layout_template_active on ingestion.pdf_layout_template (tenant_id, creditor_id, fingerprint)
    where active;
create index ix_pdf_layout_template_creditor on ingestion.pdf_layout_template (tenant_id, creditor_id, active);
select shared.enable_tenant_rls('ingestion.pdf_layout_template');

create table ingestion.pdf_import (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    creditor_id           uuid           not null references organization.creditor (id),
    portfolio_id          uuid           not null references organization.portfolio (id),
    channel               varchar(20)    not null,
    status                varchar(30)    not null,
    file_name             varchar(255)   not null,
    file_size             bigint         not null,
    file_sha256           varchar(64)    not null,
    file_key              varchar(500),
    file_deleted_at       timestamptz,
    page_count            integer        not null,
    text_layer            boolean        not null,
    idempotency_key       varchar(80),
    map                   jsonb,
    map_origin            varchar(20),
    map_revision          integer        not null,
    template_id           uuid           references ingestion.pdf_layout_template (id),
    fingerprint           varchar(64),
    visual_result         text,
    summary               jsonb,
    decisions             jsonb          not null,
    ai_calls              integer        not null,
    ai_input_tokens       bigint         not null,
    ai_output_tokens      bigint         not null,
    ai_cost               numeric(19, 6) not null,
    ai_currency           varchar(3),
    ai_model              varchar(200),
    ai_latency_ms         bigint         not null,
    failure_code          varchar(80),
    failure_message       varchar(500),
    totals_justification  text,
    approved_by           uuid,
    approved_at           timestamptz,
    review_id             uuid           references ingestion.import_review (id),
    discarded_by          uuid,
    discarded_at          timestamptz,
    discard_reason        varchar(500),
    constraint ck_pdf_import_channel check (channel in ('SCREEN', 'PUBLIC_API')),
    constraint ck_pdf_import_status check (status in ('RECEIVED', 'MAPPING', 'AWAITING_MAP', 'AWAITING_VALIDATION',
                                                       'APPROVED', 'DISCARDED', 'FAILED')),
    constraint ck_pdf_import_map_origin check (map_origin is null or map_origin in ('AI', 'TEMPLATE', 'USER', 'VISUAL')),
    constraint ck_pdf_import_sha check (file_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_pdf_import_counters check (page_count >= 1 and file_size >= 0 and map_revision >= 0 and ai_calls >= 0
                                             and ai_input_tokens >= 0 and ai_output_tokens >= 0 and ai_cost >= 0
                                             and ai_latency_ms >= 0),
    constraint ck_pdf_import_map check (status not in ('AWAITING_VALIDATION', 'APPROVED') or map is not null),
    -- aprovada = gerou a conferência; aprovação humana sempre (nunca pelo canal de integração sozinho)
    constraint ck_pdf_import_approved check ((status = 'APPROVED') = (approved_at is not null and approved_by is not null
                                                                      and review_id is not null)),
    constraint ck_pdf_import_discarded check ((status = 'DISCARDED') = (discarded_at is not null)),
    constraint ck_pdf_import_file check ((file_key is null) = (file_deleted_at is not null))
);
-- idempotência pelo conteúdo: um PDF em aberto ou aprovado por carteira
create unique index uq_pdf_import_active_file on ingestion.pdf_import (tenant_id, portfolio_id, file_sha256)
    where status in ('RECEIVED', 'MAPPING', 'AWAITING_MAP', 'AWAITING_VALIDATION', 'APPROVED');
create unique index uq_pdf_import_idempotency on ingestion.pdf_import (tenant_id, creditor_id, idempotency_key)
    where idempotency_key is not null;
create index ix_pdf_import_portfolio on ingestion.pdf_import (tenant_id, portfolio_id, status, created_at desc);
create index ix_pdf_import_creditor on ingestion.pdf_import (tenant_id, creditor_id, status, created_at desc);
create index ix_pdf_import_retention on ingestion.pdf_import (status, updated_at) where file_key is not null;
select shared.enable_tenant_rls('ingestion.pdf_import');

-- histórico do mapa (append-only): quem escreveu cada revisão e o resultado das verificações
create table ingestion.pdf_map_revision (
    id              uuid         not null primary key,
    tenant_id       uuid         not null references organization.tenant (id),
    version         bigint       not null,
    created_at      timestamptz  not null,
    created_by      uuid,
    updated_at      timestamptz  not null,
    updated_by      uuid,
    pdf_import_id   uuid         not null references ingestion.pdf_import (id),
    revision        integer      not null,
    origin          varchar(20)  not null,
    map             jsonb        not null,
    records         integer      not null,
    totals_match    boolean      not null,
    blocking        boolean      not null,
    template_id     uuid         references ingestion.pdf_layout_template (id),
    constraint ck_pdf_map_revision_origin check (origin in ('AI', 'TEMPLATE', 'USER', 'VISUAL')),
    constraint ck_pdf_map_revision_counters check (revision >= 1 and records >= 0),
    constraint uq_pdf_map_revision unique (tenant_id, pdf_import_id, revision)
);
select shared.enable_tenant_rls('ingestion.pdf_map_revision');
create trigger trg_pdf_map_revision_append_only before update or delete on ingestion.pdf_map_revision
    for each row execute function shared.forbid_mutation();

grant select, insert, update on ingestion.pdf_import, ingestion.pdf_layout_template to app_runtime;
grant select, insert on ingestion.pdf_map_revision to app_runtime;
