-- =============================================================================
-- Correção manual do valor do título pela assessoria (docs/negocio/correcao-de-valor.md,
-- decisões 802–813).
--   * title_amount_adjustment: pedido de correção com motivo (lista + texto), componentes
--     afetados (valor anterior e novo por componente), vínculo opcional com a contestação
--     e a evidência; aplicado na hora até o limite da assessoria ou depois da aprovação de
--     outra pessoa (RB06). Nunca removido; o desfecho é gravado uma única vez.
--   * title_amount_divergence: carga do credor com principal diferente do corrigido; a
--     correção é mantida e a divergência fica para alguém resolver.
--   * amount_adjustment_settings: limite da assessoria para aplicar sem segunda pessoa.
--   * debt_title: marca de principal corrigido manualmente, último principal informado pelo
--     credor e o principal do credor já reconhecido (não reabre divergência).
-- =============================================================================

alter table debt.debt_title
    add column manual_principal       boolean       not null default false,
    add column creditor_principal     numeric(19, 2),
    add column acknowledged_principal numeric(19, 2);
update debt.debt_title set creditor_principal = principal_amount;
alter table debt.debt_title alter column creditor_principal set not null;

create table debt.amount_adjustment_settings (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    version            bigint        not null,
    created_at         timestamptz   not null,
    created_by           uuid,
    updated_at         timestamptz   not null,
    updated_by           uuid,
    approval_threshold numeric(19, 2) not null,
    constraint uq_amount_adjustment_settings unique (tenant_id),
    constraint ck_amount_adjustment_threshold check (approval_threshold >= 0)
);
select shared.enable_tenant_rls('debt.amount_adjustment_settings');

create table debt.title_amount_adjustment (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by           uuid,
    updated_at             timestamptz    not null,
    updated_by           uuid,
    title_id               uuid           not null references debt.debt_title (id),
    creditor_id            uuid           not null,
    status                 varchar(20)    not null,
    approval_mode          varchar(20)    not null,
    reason_code            varchar(30)    not null,
    reason_note_encrypted  varchar(24000) not null,
    dispute_id             uuid,
    evidence_key           varchar(500),
    evidence_name          varchar(255),
    evidence_content_type  varchar(100),
    evidence_size          bigint,
    evidence_sha256        varchar(64),
    previous_principal     numeric(19, 2) not null,
    new_principal          numeric(19, 2) not null,
    previous_open_balance  numeric(19, 2) not null,
    new_open_balance       numeric(19, 2) not null,
    delta                  numeric(19, 2) not null,
    requested_by           uuid           not null,
    requested_by_name      varchar(200),
    requested_at           timestamptz    not null,
    decided_by             uuid,
    decided_by_name        varchar(200),
    decided_at             timestamptz,
    decision_note_encrypted varchar(24000),
    applied_at             timestamptz,
    constraint ck_amount_adjustment_status check (status in ('PENDING_APPROVAL', 'APPLIED', 'REJECTED', 'CANCELLED')),
    constraint ck_amount_adjustment_mode check (approval_mode in ('WITHIN_LIMIT', 'DUAL_CONTROL')),
    constraint ck_amount_adjustment_reason check (reason_code in ('CALCULATION_ERROR', 'CREDITOR_CONFIRMATION',
                                                                  'DISPUTE_UPHELD', 'DUPLICATE_CHARGE',
                                                                  'CONTRACT_TERMS', 'PARTIAL_WAIVER', 'OTHER')),
    constraint ck_amount_adjustment_delta check (delta <> 0),
    constraint ck_amount_adjustment_positive check (new_open_balance > 0 and new_principal >= 0),
    -- segregação: quem pede não decide (RB06), inclusive o administrador
    constraint ck_amount_adjustment_segregation check (approval_mode <> 'DUAL_CONTROL' or decided_by is null
                                                       or status = 'CANCELLED' or decided_by <> requested_by),
    constraint ck_amount_adjustment_evidence check (evidence_key is null
                                                    or (evidence_name is not null and evidence_sha256 is not null)),
    constraint ck_amount_adjustment_applied check ((status = 'APPLIED') = (applied_at is not null))
);
create index ix_amount_adjustment_title on debt.title_amount_adjustment (tenant_id, title_id, requested_at);
create index ix_amount_adjustment_pending on debt.title_amount_adjustment (tenant_id, requested_at)
    where status = 'PENDING_APPROVAL';
-- no máximo um pedido aguardando aprovação por título
create unique index uq_amount_adjustment_pending on debt.title_amount_adjustment (title_id)
    where status = 'PENDING_APPROVAL';
select shared.enable_tenant_rls('debt.title_amount_adjustment');

create table debt.title_amount_adjustment_line (
    adjustment_id    uuid           not null references debt.title_amount_adjustment (id),
    component        varchar(20)    not null,
    previous_balance numeric(19, 2) not null,
    new_balance      numeric(19, 2) not null,
    primary key (adjustment_id, component),
    constraint ck_amount_adjustment_line_component check (component in ('PRINCIPAL', 'INTEREST', 'FINE', 'FEES',
                                                                         'HONORARIUM')),
    constraint ck_amount_adjustment_line_change check (previous_balance <> new_balance and new_balance >= 0)
);
-- as linhas seguem o pedido (RLS pela linha pai) e nunca mudam
alter table debt.title_amount_adjustment_line enable row level security;
alter table debt.title_amount_adjustment_line force row level security;
create policy tenant_isolation on debt.title_amount_adjustment_line
    using (exists (select 1 from debt.title_amount_adjustment a where a.id = adjustment_id))
    with check (exists (select 1 from debt.title_amount_adjustment a where a.id = adjustment_id));
create trigger trg_amount_adjustment_line_append_only before update or delete on debt.title_amount_adjustment_line
    for each row execute function shared.forbid_mutation();

-- o desfecho é gravado uma única vez: pedido decidido não muda nem é removido
create or replace function debt.amount_adjustment_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'debt.title_amount_adjustment é append-only' using errcode = '42501';
    end if;
    if old.status <> 'PENDING_APPROVAL' then
        raise exception 'pedido de correção já decidido não pode ser alterado' using errcode = '42501';
    end if;
    if new.title_id <> old.title_id or new.requested_by <> old.requested_by or new.delta <> old.delta
        or new.reason_code <> old.reason_code or new.requested_at <> old.requested_at then
        raise exception 'dados do pedido de correção são imutáveis' using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_amount_adjustment_guard before update or delete on debt.title_amount_adjustment
    for each row execute function debt.amount_adjustment_guard();

create table debt.title_amount_divergence (
    id                       uuid           not null primary key,
    tenant_id                uuid           not null references organization.tenant (id),
    version                  bigint         not null,
    created_at               timestamptz    not null,
    created_by           uuid,
    updated_at               timestamptz    not null,
    updated_by           uuid,
    title_id                 uuid           not null references debt.debt_title (id),
    creditor_id              uuid           not null,
    status                   varchar(25)    not null,
    current_principal        numeric(19, 2) not null,
    reported_principal       numeric(19, 2) not null,
    import_batch_id          uuid,
    detected_at              timestamptz    not null,
    last_seen_at             timestamptz    not null,
    occurrences              int            not null,
    resolved_by              uuid,
    resolved_by_name         varchar(200),
    resolved_at              timestamptz,
    resolution_note_encrypted varchar(24000),
    constraint ck_amount_divergence_status check (status in ('OPEN', 'KEPT_MANUAL', 'ACCEPTED_CREDITOR',
                                                             'RESOLVED_BY_CREDITOR', 'SUPERSEDED')),
    constraint ck_amount_divergence_values check (current_principal <> reported_principal),
    constraint ck_amount_divergence_resolved check ((status = 'OPEN') = (resolved_at is null))
);
create unique index uq_amount_divergence_open on debt.title_amount_divergence (title_id) where status = 'OPEN';
create index ix_amount_divergence_open on debt.title_amount_divergence (tenant_id, detected_at) where status = 'OPEN';
create index ix_amount_divergence_title on debt.title_amount_divergence (tenant_id, title_id, detected_at);
select shared.enable_tenant_rls('debt.title_amount_divergence');

grant select, insert, update on debt.amount_adjustment_settings, debt.title_amount_adjustment,
    debt.title_amount_divergence to app_runtime;
grant select, insert on debt.title_amount_adjustment_line to app_runtime;
