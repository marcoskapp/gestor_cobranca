-- H07 (decisão 1466): pagamento recebido diretamente pelo credor. O lançamento de pagamento (e o estorno, que repete o
-- receptor do pagamento estornado) grava quem recebeu o dinheiro; o título guarda, como cache do razão na mesma
-- transação, a parte do total pago recebida pelo credor, para o selo "Pago ao credor" e o filtro da lista de títulos.
-- Lançamentos anteriores ficam sem receptor (valem assessoria): o razão é append-only e o sistema ainda não está em
-- produção; o sandbox é recriado pela carga (decisão 1521).
alter table debt.title_ledger_entry
    add column received_by varchar(10),
    add constraint ck_title_ledger_received_by
        check (received_by is null
            or (received_by in ('AGENCY', 'CREDITOR') and entry_type in ('PAYMENT', 'REVERSAL')));

alter table debt.debt_title
    add column creditor_paid_total numeric(19, 2) not null default 0,
    add constraint ck_debt_title_creditor_paid check (creditor_paid_total >= 0 and creditor_paid_total <= paid_total);

-- filtro "Pago ao credor": poucas linhas, sempre com o escopo por credor
create index ix_debt_title_creditor_paid on debt.debt_title (tenant_id, creditor_id, due_date)
    where creditor_paid_total > 0;
