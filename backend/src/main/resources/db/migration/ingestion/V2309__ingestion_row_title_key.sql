-- =============================================================================
-- Chave HMAC do identificador do título nas linhas da carga (SEG-20261125-K02, decisão 1476).
--
-- Na anonimização, o identificador com CPF/CNPJ do título passa a 'anon:' + HMAC no debt. A detecção nos arquivos
-- (linhas do titular sem devedor ligado: prévia, erro, conferência recusada — SEG-20261124-D01h) passa a comparar pela
-- chave gravada na linha (title_key = HMAC do credor e do identificador, shared.crypto.ExternalIdDigest), e não mais
-- pelo identificador cru. A linha continua imutável: a única mudança aceita é preencher a chave das linhas gravadas
-- antes desta versão (job de uso único, enfileirado abaixo). O identificador cru da linha fica para o D01k.
-- =============================================================================

alter table ingestion.import_batch_row
    add column title_key varchar(64),
    add constraint ck_import_batch_row_title_key check (title_key is null or title_key ~ '^[0-9a-f]{64}$');
-- toda linha nova grava a chave (um caminho de gravação que a esqueça falha, em vez de deixar a detecção cega); as
-- anteriores ficam de fora (not valid) até o preenchimento — validar a constraint depois dele
alter table ingestion.import_batch_row
    add constraint ck_import_batch_row_title_key_present check (title_key is not null) not valid;

-- substitui o índice da V2305 (detecção pelo identificador cru), que deixa de ser usado. Pré-produção: na transação
-- da migration (decisão 1494)
drop index ingestion.ix_import_batch_row_by_external_id;
create index ix_import_batch_row_title_key on ingestion.import_batch_row (tenant_id, title_key, batch_id)
    where title_key is not null;
create index ix_import_batch_row_title_key_pending on ingestion.import_batch_row (tenant_id, id)
    where title_key is null;

create function ingestion.import_batch_row_title_key_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'UPDATE' and old.title_key is null and new.title_key ~ '^[0-9a-f]{64}$'
        and (new.id, new.tenant_id, new.version, new.created_at, new.created_by, new.updated_at, new.updated_by,
             new.batch_id, new.external_id, new.row_number, new.title_id, new.debtor_id, new.title_outcome,
             new.debtor_outcome, new.warning_count, new.title_suspended)
            is not distinct from
            (old.id, old.tenant_id, old.version, old.created_at, old.created_by, old.updated_at, old.updated_by,
             old.batch_id, old.external_id, old.row_number, old.title_id, old.debtor_id, old.title_outcome,
             old.debtor_outcome, old.warning_count, old.title_suspended) then
        return new;
    end if;
    raise exception 'Registro imutável: %', tg_table_name using errcode = 'P0001';
end;
$$;

drop trigger trg_import_batch_row_append_only on ingestion.import_batch_row;
create trigger trg_import_batch_row_append_only before update or delete on ingestion.import_batch_row
    for each row execute function ingestion.import_batch_row_title_key_only();
grant update (title_key) on ingestion.import_batch_row to app_runtime;

-- linhas gravadas antes desta versão: o job preenche a chave (o HMAC precisa da chave da aplicação), uma vez por
-- assessoria; idempotente (só as linhas sem chave)
insert into shared.processing_job (id, tenant_id, type, payload, status, priority, run_at, attempts, dedup_key, created_at)
select gen_random_uuid(), t.id, 'ingestion.row-title-key-backfill', '{}'::jsonb, 'PENDING', 0, now(), 0,
       'ingestion.row-title-key-backfill:v1', now()
  from organization.tenant t;
