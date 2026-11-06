-- Cancelamento do processamento da importação de extrato (pedido do responsável; decisão 909): a importação na fila
-- ou em processamento pode ser levada por uma pessoa a CANCELLED, situação terminal que mantém as linhas e baixas já
-- gravadas e aceita "Tentar novamente" (volta a RECEIVED, como a FAILED). Só o check da situação muda: quem cancelou e
-- o motivo ficam na auditoria (statement_import.cancelled) e o instante em finished_at. Sem backfill.
alter table payment.statement_import
    drop constraint ck_statement_import_status,
    add constraint ck_statement_import_status
        check (status in ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'CANCELLED'));
