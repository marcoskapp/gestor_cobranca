-- Revisão de segurança A5: soma acumulada dos pagamentos externos confirmados sem revisão, por
-- usuário que informa e por devedor no credor, numa janela de tempo (PaymentService#externalReview).
create index ix_payment_external_auto_informer
    on payment.payment (tenant_id, informed_by, informed_at)
    where source = 'EXTERNAL' and not review_required;

create index ix_payment_external_auto_debtor
    on payment.payment (tenant_id, creditor_id, debtor_id, informed_at)
    where source = 'EXTERNAL' and not review_required;
