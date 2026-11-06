-- Nova tentativa da importação de extrato com falha (decisão do responsável à 873): a importação FAILED pode voltar a
-- RECEIVED e o job retoma de onde parou (as linhas gravadas são puladas pelo índice único de V0805). A linha do
-- arquivo onde a falha ocorreu é gravada só como número — nada do conteúdo da linha (pode ter nome ou documento do
-- pagador) — para a tela apontar o problema; retry_count conta as novas tentativas pedidas.
alter table payment.statement_import
    add column failure_line integer,
    add column retry_count  integer not null default 0,
    add constraint ck_statement_import_failure_line check (failure_line is null or failure_line > 0),
    add constraint ck_statement_import_retry_count check (retry_count >= 0);
