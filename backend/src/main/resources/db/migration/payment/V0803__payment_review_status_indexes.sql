-- Situação de revisão dos pagamentos externos no acordo (PaymentReviewStatusService): pendentes de
-- aprovação e recusados recentes por acordo de destino (detalhe e selo da listagem de acordos).
-- A consulta por devedor no credor (ficha do caso) usa ix_payment_debtor.
create index ix_payment_review_agreement
    on payment.payment (tenant_id, target_agreement_id, informed_at)
    where source = 'EXTERNAL' and status in ('INFORMED', 'REJECTED') and target_agreement_id is not null;
