-- H07 parte 2b: lista "Baixas informadas pelo credor" (aba dos pagamentos). Pagamentos externos recebidos
-- diretamente pelo credor, filtrados pelo dia do pagamento, sempre com o escopo por credor. Só índice (expand): a
-- versão anterior da aplicação continua funcionando sem ele.
create index ix_payment_creditor_received on payment.payment (tenant_id, creditor_id, paid_at desc, id)
    where source = 'EXTERNAL' and external_origin in ('CREDITOR_DIRECT', 'CREDITOR_REPORT');
