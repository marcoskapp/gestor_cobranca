-- Processamento do extrato em lotes com retomada (decisão 866): cada lote grava as suas linhas na própria
-- transação, e a nova tentativa pula as já gravadas por (import_id, line_number). O índice único garante que
-- nenhuma execução (inclusive concorrente, por lease perdido) grave a mesma linha duas vezes — e, com ela, uma
-- segunda baixa. Substitui o índice simples de mesma chave.
create unique index uq_statement_line_import_number
    on payment.statement_line (tenant_id, import_id, line_number);
drop index payment.ix_statement_line_import;
