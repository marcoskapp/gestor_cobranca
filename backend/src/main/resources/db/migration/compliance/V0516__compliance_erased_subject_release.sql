-- =============================================================================
-- Liberação do retorno do titular eliminado a pedido (decisões 1498 e 1604): quem atende titulares, e não quem executou
-- a eliminação, libera com motivo o retorno do titular (ex.: voltou a contratar, nova base legal). Vale para a
-- assessoria inteira a partir da liberação; a eliminação já feita não se desfaz. Registro imutável, um por pedido.
-- O motivo é texto livre de quem libera (orientado a não trazer dado pessoal) e fica com a guarda do pedido.
-- =============================================================================

create table compliance.erased_subject_release (
    id           uuid           not null primary key,
    tenant_id    uuid           not null references organization.tenant (id),
    version      bigint         not null,
    created_at   timestamptz    not null,
    created_by   uuid,
    updated_at   timestamptz    not null,
    updated_by   uuid,
    request_id   uuid           not null references compliance.data_subject_request (id),
    reason       varchar(500)   not null,
    released_by  uuid           not null,
    released_at  timestamptz    not null,
    constraint uk_erased_subject_release unique (tenant_id, request_id),
    constraint ck_erased_subject_release_reason check (length(btrim(reason)) >= 10)
);
select shared.enable_tenant_rls('compliance.erased_subject_release');
create trigger trg_erased_subject_release_immutable before update or delete on compliance.erased_subject_release
    for each row execute function shared.forbid_mutation();
revoke update, delete on compliance.erased_subject_release from app_runtime;
