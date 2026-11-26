-- =============================================================================
-- SEG-20261126-M08a, opção (a) decidida pelo responsável em 26/11/2026 (decisão 1496): o lote que recusa, no próprio
-- processamento, linha de titular anonimizado também fica bloqueado (as demais linhas seguem gravadas) e o arquivo sai
-- no encerramento. O registro do bloqueio passa a ter a origem "achado na carga", sem evento de anonimização.
-- =============================================================================
alter table ingestion.import_subject_block
    alter column source_event_id drop not null,
    drop constraint ck_import_subject_block_effect,
    add constraint ck_import_subject_block_effect
        check (effect in ('CANCELLED_NOW', 'STOP_REQUESTED', 'ALREADY_FINISHED', 'FOUND_IN_LOAD')),
    add constraint ck_import_subject_block_origin check ((effect = 'FOUND_IN_LOAD') = (source_event_id is null));
