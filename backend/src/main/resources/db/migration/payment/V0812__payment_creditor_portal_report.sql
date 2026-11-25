-- Portal do credor (ADR 0011, fatia 6): pagamento recebido diretamente pelo credor e informado pela própria pessoa do
-- credor no portal. Sempre revisado (RS-33): o canal CREDITOR_PORTAL só existe com revisão obrigatória, origem
-- CREDITOR_DIRECT e motivo de revisão CREDITOR_PORTAL (sem limite nem valor comparado), e só sai de INFORMED pela
-- decisão de uma pessoa da assessoria (reviewed_by). O comprovante chega já verificado (malware e conteúdo ativo): o
-- veredito fica gravado e aparece para quem revisa. Na recusa, o credor vê só um motivo codificado (nunca a nota
-- interna da revisão).

alter table payment.payment
    add column informed_channel          varchar(20),
    add column creditor_title_numbers    varchar(2000),
    add column evidence_scan_verdict     varchar(20),
    add column evidence_scan_engine      varchar(40),
    add column evidence_scanned_at       timestamptz,
    add column creditor_rejection_reason varchar(30),
    drop constraint ck_payment_review_reason,
    drop constraint ck_payment_review_trigger,
    add constraint ck_payment_review_reason
        check (review_reason is null
            or review_reason in ('AMOUNT', 'DESTINATION_CUMULATIVE', 'INFORMER_CUMULATIVE', 'STATEMENT_LINE',
                                 'CREDITOR_PORTAL')),
    add constraint ck_payment_review_trigger
        check ((review_reason is null and review_limit is null and review_considered_amount is null)
            or (review_reason = 'STATEMENT_LINE' and source = 'PROVIDER' and review_required
                and informed_by is not null and instruction_id is not null and statement_line_id is not null
                and review_limit is null and review_considered_amount is null)
            or (review_reason = 'CREDITOR_PORTAL' and source = 'EXTERNAL' and review_required
                and informed_by is not null and review_limit is null and review_considered_amount is null)
            or (review_reason not in ('STATEMENT_LINE', 'CREDITOR_PORTAL') and review_required
                and review_limit is not null and review_considered_amount is not null)),
    add constraint ck_payment_informed_channel check (informed_channel is null or informed_channel = 'CREDITOR_PORTAL'),
    add constraint ck_payment_creditor_portal_review
        check (informed_channel is null
            or (review_required and source = 'EXTERNAL' and external_origin = 'CREDITOR_DIRECT'
                and review_reason = 'CREDITOR_PORTAL' and creditor_title_numbers is not null
                and evidence_scan_verdict is not null and evidence_scanned_at is not null)),
    add constraint ck_payment_creditor_portal_decided
        check (informed_channel is null or status in ('INFORMED', 'REJECTED') or reviewed_by is not null),
    add constraint ck_payment_evidence_scan_verdict
        check (evidence_scan_verdict is null or evidence_scan_verdict in ('CLEAN', 'NOT_SCANNED')),
    add constraint ck_payment_creditor_rejection_reason
        check (creditor_rejection_reason is null
            or (creditor_rejection_reason in ('PROOF_INVALID', 'AMOUNT_OR_DATE_MISMATCH', 'WRONG_TITLES', 'DUPLICATE',
                                              'OTHER')
                and informed_channel = 'CREDITOR_PORTAL' and status = 'REJECTED')),
    add constraint ck_payment_creditor_rejection_required
        check (informed_channel is null or status <> 'REJECTED' or creditor_rejection_reason is not null);

-- informes do portal por credor (lista "Pagamentos informados" e limite de informes em análise)
create index ix_payment_creditor_portal on payment.payment (tenant_id, creditor_id, informed_at desc)
    where informed_channel = 'CREDITOR_PORTAL';
