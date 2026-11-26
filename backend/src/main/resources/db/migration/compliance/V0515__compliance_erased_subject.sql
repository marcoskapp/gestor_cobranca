-- =============================================================================
-- Titular eliminado a pedido não volta (SEG-20261124-D01h, decisão 1471, decisões 1498, 1499 e 1600–1608).
--
-- erased_subject_hash: índice cego (HMAC por tenant, finalidade data_subject_request.document) do documento do devedor
-- que foi de fato eliminado, gravado pelo portão da eliminação na mesma transação da anonimização — também quando o
-- requerente não informou documento. Só o índice, nunca o documento (minimização). Escrita única: o gatilho recusa
-- mudar o valor depois de gravado (um bug ou chamador novo não desliga a recusa). Guarda: a do pedido (decisão 1483).
--
-- erased_subject_return: tentativa de trazer de volta o titular (carga, cadastro manual, coobrigado), para revisão
-- humana no compliance (manter a recusa ou liberar o retorno). Sem dado pessoal: pedido, credor, origem, lote e
-- quantidade de linhas.
-- =============================================================================

alter table compliance.data_subject_request
    add column erased_subject_hash varchar(64),
    add column executed_by         uuid,
    add constraint ck_dsr_erased_subject check (erased_subject_hash is null
        or (type = 'ANONYMIZATION' and executed_at is not null));

-- Pedidos já executados (pré-produção): o índice do documento informado pelo requerente (o do devedor localizado na
-- verificação da identidade) e o autor da execução pela auditoria da eliminação. Sem documento informado, fica sem
-- índice (resíduo, decisão 1499); sem autor na auditoria, a liberação é recusada (decisão 1604). RLS forçada: percorre
-- as assessorias com o tenant da transação (decisão 849).
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update compliance.data_subject_request r
           set erased_subject_hash = r.document_hash,
               executed_by = (select a.actor_id from shared.audit_event a
                               where a.entity_type = 'data_subject_request' and a.entity_id = r.id
                                 and a.action = 'data_subject_request.anonymized'
                               order by a.occurred_at limit 1),
               version = r.version + 1,
               updated_at = now()
         where r.type = 'ANONYMIZATION' and r.executed_at is not null and r.document_hash is not null
           and r.erased_subject_hash is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

create index ix_dsr_erased_subject on compliance.data_subject_request (tenant_id, erased_subject_hash)
    where erased_subject_hash is not null;

create function compliance.dsr_erased_subject_write_once() returns trigger
    language plpgsql as
$$
begin
    if old.erased_subject_hash is not null and new.erased_subject_hash is distinct from old.erased_subject_hash then
        raise exception 'Índice do titular eliminado é de escrita única' using errcode = 'P0001';
    end if;
    return new;
end;
$$;
create trigger trg_dsr_erased_subject_write_once before update on compliance.data_subject_request
    for each row execute function compliance.dsr_erased_subject_write_once();

create table compliance.erased_subject_return (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    request_id    uuid          not null references compliance.data_subject_request (id),
    creditor_id   uuid          references organization.creditor (id),
    source        varchar(20)   not null,
    batch_id      uuid,
    lines         integer       not null,
    attempted_at  timestamptz   not null,
    outcome       varchar(20),
    reviewed_at   timestamptz,
    reviewed_by   uuid,
    constraint ck_erased_subject_return_source check (source in ('IMPORT', 'DEBTOR_FORM', 'CO_OBLIGOR_FORM')),
    constraint ck_erased_subject_return_batch check ((source = 'IMPORT') = (batch_id is not null)),
    constraint ck_erased_subject_return_lines check (lines > 0),
    constraint ck_erased_subject_return_outcome check (outcome is null or outcome in ('KEPT', 'RELEASED')),
    constraint ck_erased_subject_return_review check ((outcome is null) = (reviewed_at is null)
        and (reviewed_at is null or reviewed_by is not null))
);
create index ix_erased_subject_return_request on compliance.erased_subject_return (tenant_id, request_id, attempted_at);
create index ix_erased_subject_return_pending on compliance.erased_subject_return (tenant_id, attempted_at)
    where outcome is null;
select shared.enable_tenant_rls('compliance.erased_subject_return');
-- o registro da tentativa é imutável; só a revisão (uma vez) é gravada depois
revoke update, delete on compliance.erased_subject_return from app_runtime;
grant update (outcome, reviewed_at, reviewed_by, version, updated_at, updated_by)
    on compliance.erased_subject_return to app_runtime;
