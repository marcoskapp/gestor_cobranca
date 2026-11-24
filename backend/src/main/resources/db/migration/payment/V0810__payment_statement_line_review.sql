-- Extrato enviado por uma pessoa é evidência, não confirmação (SEG-20261124-C01, decisão 1450): a linha que cita uma
-- instrução sem pagamento cria um pagamento INFORMED que outra pessoa aprova (motivo STATEMENT_LINE, sem limite nem
-- valor comparado); a linha fica PENDING_REVIEW até a aprovação (CREATED_PAYMENT) ou a recusa (REVIEW_REJECTED). O
-- extrato de quem informou um pagamento externo não o concilia: divergência SELF_RECONCILIATION.
-- Os checks novos aceitam tudo o que os antigos aceitavam. As consultas de revisão por acordo (selo da listagem de
-- acordos, PaymentReviewHolds no job de quebra) passam a filtrar por review_required em vez de source = 'EXTERNAL'
-- (decisão 1451): o índice parcial da V0803 deixaria de servir e é recriado com esse predicado, sem status, para
-- atender pendentes, recusados recentes e revisados com um índice só.
alter table payment.payment
    drop constraint ck_payment_review_reason,
    drop constraint ck_payment_review_trigger,
    add constraint ck_payment_review_reason
        check (review_reason is null
            or review_reason in ('AMOUNT', 'DESTINATION_CUMULATIVE', 'INFORMER_CUMULATIVE', 'STATEMENT_LINE')),
    add constraint ck_payment_review_trigger
        check ((review_reason is null and review_limit is null and review_considered_amount is null)
            or (review_reason = 'STATEMENT_LINE' and source = 'PROVIDER' and review_required
                and informed_by is not null and instruction_id is not null and statement_line_id is not null
                and review_limit is null and review_considered_amount is null)
            or (review_reason <> 'STATEMENT_LINE' and review_required and review_limit is not null
                and review_considered_amount is not null));

alter table payment.statement_line
    drop constraint ck_statement_line_status,
    add constraint ck_statement_line_status
        check (status in ('RECONCILED', 'CREATED_PAYMENT', 'DIVERGENT', 'ALREADY_RECONCILED', 'IGNORED_DUPLICATE',
                          'IGNORED_DEBIT', 'PENDING_REVIEW', 'REVIEW_REJECTED'));

alter table payment.divergence
    drop constraint ck_divergence_type,
    add constraint ck_divergence_type
        check (type in ('AMOUNT_MISMATCH', 'DUPLICATE_PAYMENT', 'UNMATCHED', 'NOT_CONFIRMED', 'PARTIAL_REFUND',
                        'SELF_RECONCILIATION'));

drop index payment.ix_payment_review_agreement;
create index ix_payment_review_agreement
    on payment.payment (tenant_id, target_agreement_id, informed_at)
    where review_required and target_agreement_id is not null;
