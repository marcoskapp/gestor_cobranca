-- Remoção pendente do arquivo com origem própria (parecer AQ-01 da fatia 5 do D01h): vinda da anonimização (evento) ou
-- do bloqueio achado na própria carga (o lote que recusou a linha do titular, decisão 1496) — nunca um id no lugar do
-- outro, para a auditoria do descarte citar a origem certa.
alter table ingestion.pending_file_erasure
    alter column source_event_id drop not null,
    add column origin_batch_id uuid references ingestion.import_batch (id),
    add constraint ck_pending_file_erasure_origin check ((source_event_id is null) <> (origin_batch_id is null));
