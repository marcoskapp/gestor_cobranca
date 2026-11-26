-- H07 parte 3 (RS-120): depois do estorno de um pagamento recebido pelo credor, os informes do mesmo credor para o mesmo
-- devedor (origem CREDITOR_DIRECT ou CREDITOR_REPORT, qualquer canal) vão sempre à revisão por segunda pessoa durante a
-- janela app.payment.external-review.reversal-window (padrão 90 dias), sem confirmação automática por baixo risco.
-- Motivo novo RECENT_CREDITOR_REVERSAL; o limite gravado é zero (qualquer valor) e o valor considerado é o pagamento.
--
-- Expand: só amplia a lista do check; a versão anterior da aplicação nunca grava o motivo novo.
alter table payment.payment
    drop constraint ck_payment_review_reason,
    add constraint ck_payment_review_reason
        check (review_reason is null
            or review_reason in ('AMOUNT', 'DESTINATION_CUMULATIVE', 'INFORMER_CUMULATIVE', 'CREDITOR_CUMULATIVE',
                                 'RECENT_CREDITOR_REVERSAL', 'STATEMENT_LINE', 'CREDITOR_PORTAL'));

-- estornos recentes de pagamento recebido pelo credor, por devedor no credor (poucas linhas)
create index ix_payment_creditor_received_reversed on payment.payment (tenant_id, creditor_id, debtor_id, reversed_at)
    where status = 'REVERSED' and external_origin in ('CREDITOR_DIRECT', 'CREDITOR_REPORT');
