-- Aprovação de pagamentos num padrão único com as demais filas (revisão de produto 11/2026, item 3.1): quem decide vê
-- por que o pagamento informado foi para a aprovação e quanto passa do limite. O motivo, o limite ultrapassado e o valor
-- comparado com ele (o pagamento ou a soma da janela) eram calculados no registro e só iam para a auditoria e o log de
-- negócio; passam a ficar no pagamento, gravados uma vez no registro (a política de revisão continua no servidor).
-- Pagamentos anteriores ficam sem motivo (nulo): a tela compara o valor com o limite atual por pagamento. Sem backfill
-- (a auditoria guarda o motivo, mas não o limite e a soma de cada registro).
alter table payment.payment
    add column review_reason            varchar(30),
    add column review_limit             numeric(19, 2),
    add column review_considered_amount numeric(19, 2),
    add constraint ck_payment_review_reason
        check (review_reason is null or review_reason in ('AMOUNT', 'DESTINATION_CUMULATIVE', 'INFORMER_CUMULATIVE')),
    add constraint ck_payment_review_trigger
        check ((review_reason is null and review_limit is null and review_considered_amount is null)
            or (review_reason is not null and review_required and review_limit is not null
                and review_considered_amount is not null));
