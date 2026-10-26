-- =============================================================================
-- Suspensão da quebra do acordo enquanto há pagamento externo informado aguardando revisão
-- (decisão do responsável de 26/10/2026, decisões 494–503; negociacao-e-alcadas.md §10.6.1).
-- Uma linha por suspensão: começa no job diário quando a regra de quebra é atingida com pagamento
-- em revisão para o acordo e termina na revisão (aprovado/recusado), no prazo máximo ou no
-- encerramento do acordo. No máximo uma suspensão em vigor por acordo.
-- =============================================================================
create table negotiation.agreement_break_suspension (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    agreement_id           uuid           not null references negotiation.agreement (id),
    creditor_id            uuid           not null,
    trigger_installment_id uuid           not null references negotiation.agreement_installment (id),
    status                 varchar(20)    not null,
    started_on             date           not null,
    started_at             timestamptz    not null,
    suspended_until        date           not null,
    pending_payments       integer        not null,
    pending_amount         numeric(19, 2) not null,
    end_reason             varchar(20),
    ended_at               timestamptz,
    grace_until            date,
    reviewed_payment_id    uuid,
    constraint ck_break_suspension_status check (status in ('ACTIVE', 'ENDED')),
    constraint ck_break_suspension_reason check (end_reason is null
        or end_reason in ('APPROVED', 'REJECTED', 'DEADLINE', 'AGREEMENT_ENDED')),
    constraint ck_break_suspension_end check ((status = 'ACTIVE' and end_reason is null and ended_at is null)
        or (status = 'ENDED' and end_reason is not null and ended_at is not null)),
    constraint ck_break_suspension_grace check (grace_until is null or end_reason = 'REJECTED'),
    constraint ck_break_suspension_dates check (suspended_until >= started_on),
    constraint ck_break_suspension_pending check (pending_payments > 0 and pending_amount > 0)
);
create unique index uk_break_suspension_active on negotiation.agreement_break_suspension (tenant_id, agreement_id)
    where status = 'ACTIVE';
create index ix_break_suspension_agreement on negotiation.agreement_break_suspension
    (tenant_id, agreement_id, started_at desc);
create index ix_break_suspension_trigger on negotiation.agreement_break_suspension (tenant_id, trigger_installment_id)
    where end_reason in ('REJECTED', 'DEADLINE');
select shared.enable_tenant_rls('negotiation.agreement_break_suspension');

-- histórico: a linha só muda para encerrar a suspensão (nunca é apagada)
grant select, insert, update on negotiation.agreement_break_suspension to app_runtime;
