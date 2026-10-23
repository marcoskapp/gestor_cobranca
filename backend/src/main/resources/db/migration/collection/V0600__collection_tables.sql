-- =============================================================================
-- Módulo collection (F06, F07, F21, RB02): casos de cobrança, elegibilidade,
-- filas, atribuição e histórico de dono do atendimento, tabulações, interações,
-- tarefas/retornos, promessas de pagamento e contestações.
--
-- Textos livres que podem conter dados pessoais (observações de interação e
-- tarefa, descrição e parecer de contestação) ficam criptografados (*_encrypted).
-- Interações, histórico de atribuição e evidências são append-only.
-- Usuários e equipes (identity) são referenciados por id, sem chave estrangeira,
-- como nos demais módulos (o histórico sobrevive à inativação de usuários).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Parâmetros da operação por assessoria (ausente = padrões de app.collection.*)
-- -----------------------------------------------------------------------------
create table collection.settings (
    id                        uuid         not null primary key,
    tenant_id                 uuid         not null references organization.tenant (id),
    version                   bigint       not null,
    created_at                timestamptz  not null,
    created_by                uuid,
    updated_at                timestamptz  not null,
    updated_by                uuid,
    promise_tolerance_days    integer      not null,
    promise_max_days          integer      not null,
    callback_tolerance_minutes integer     not null,
    dispute_response_days     integer      not null,
    constraint uk_settings_tenant unique (tenant_id),
    constraint ck_settings_values check (promise_tolerance_days between 0 and 30
                                         and promise_max_days between 1 and 180
                                         and callback_tolerance_minutes between 0 and 1440
                                         and dispute_response_days between 1 and 90)
);
select shared.enable_tenant_rls('collection.settings');

-- -----------------------------------------------------------------------------
-- Catálogo de tabulações por assessoria
-- -----------------------------------------------------------------------------
create table collection.tabulation (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    version            bigint        not null,
    created_at         timestamptz   not null,
    created_by         uuid,
    updated_at         timestamptz   not null,
    updated_by         uuid,
    code               varchar(40)   not null,
    name               varchar(120)  not null,
    effective_contact  boolean       not null,
    promise            boolean       not null,
    callback           boolean       not null,
    requires_note      boolean       not null,
    display_order      integer       not null,
    active             boolean       not null,
    constraint uk_tabulation_code unique (tenant_id, code),
    constraint ck_tabulation_promise check (not promise or effective_contact),
    constraint ck_tabulation_flags check (not (promise and callback))
);
select shared.enable_tenant_rls('collection.tabulation');

-- -----------------------------------------------------------------------------
-- Filas: critérios (nulo = sem restrição), ordem de entrega e distribuição
-- -----------------------------------------------------------------------------
create table collection.queue (
    id                  uuid            not null primary key,
    tenant_id           uuid            not null references organization.tenant (id),
    version             bigint          not null,
    created_at          timestamptz     not null,
    created_by          uuid,
    updated_at          timestamptz     not null,
    updated_by          uuid,
    code                varchar(40)     not null,
    name                varchar(120)    not null,
    description         varchar(500),
    active              boolean         not null,
    priority            integer         not null,
    creditor_id         uuid            references organization.creditor (id),
    portfolio_id        uuid            references organization.portfolio (id),
    min_days_overdue    integer,
    max_days_overdue    integer,
    min_balance         numeric(19, 2),
    max_balance         numeric(19, 2),
    min_score           integer,
    max_score           integer,
    ordering            varchar(30)     not null,
    strategy            varchar(20)     not null,
    max_cases_per_user  integer,
    round_robin_cursor  uuid,
    constraint uk_queue_code unique (tenant_id, code),
    constraint ck_queue_priority check (priority >= 0),
    constraint ck_queue_portfolio check (portfolio_id is null or creditor_id is not null),
    constraint ck_queue_overdue check ((min_days_overdue is null or min_days_overdue >= 0)
        and (max_days_overdue is null or max_days_overdue >= 0)
        and (min_days_overdue is null or max_days_overdue is null or min_days_overdue <= max_days_overdue)),
    constraint ck_queue_balance check ((min_balance is null or min_balance >= 0)
        and (max_balance is null or max_balance >= 0)
        and (min_balance is null or max_balance is null or min_balance <= max_balance)),
    constraint ck_queue_score check (min_score is null or max_score is null or min_score <= max_score),
    constraint ck_queue_capacity check (max_cases_per_user is null or max_cases_per_user > 0),
    constraint ck_queue_ordering check (ordering in ('OLDEST_OVERDUE', 'HIGHEST_BALANCE', 'LOWEST_BALANCE',
                                                     'LEAST_RECENTLY_WORKED', 'HIGHEST_SCORE')),
    constraint ck_queue_strategy check (strategy in ('PULL', 'ROUND_ROBIN', 'LEAST_LOADED'))
);
create index ix_queue_active on collection.queue (tenant_id, priority) where active;
select shared.enable_tenant_rls('collection.queue');

-- equipes que atendem a fila (vazio = qualquer operador)
create table collection.queue_team (
    queue_id  uuid not null references collection.queue (id) on delete cascade,
    team_id   uuid not null,
    primary key (queue_id, team_id)
);
alter table collection.queue_team enable row level security;
alter table collection.queue_team force row level security;
create policy tenant_isolation on collection.queue_team
    using (exists (select 1 from collection.queue q where q.id = queue_id))
    with check (exists (select 1 from collection.queue q where q.id = queue_id));

-- -----------------------------------------------------------------------------
-- Caso de cobrança: no máximo um ativo por (devedor, carteira)
-- -----------------------------------------------------------------------------
create table collection.collection_case (
    id                         uuid            not null primary key,
    tenant_id                  uuid            not null references organization.tenant (id),
    version                    bigint          not null,
    created_at                 timestamptz     not null,
    created_by                 uuid,
    updated_at                 timestamptz     not null,
    updated_by                 uuid,
    debtor_id                  uuid            not null references debt.debtor (id),
    creditor_id                uuid            not null references organization.creditor (id),
    portfolio_id               uuid            not null references organization.portfolio (id),
    status                     varchar(20)     not null,
    resume_status              varchar(20),
    status_reason              varchar(500),
    opened_at                  timestamptz     not null,
    closed_at                  timestamptz,
    close_reason               varchar(20),
    open_balance               numeric(19, 2)  not null,
    eligible_balance           numeric(19, 2)  not null,
    open_titles                integer         not null,
    suspended_titles           integer         not null,
    disputed_titles            integer         not null,
    oldest_due_date            date,
    balance_updated_at         timestamptz     not null,
    eligible                   boolean         not null,
    eligibility_reasons        varchar(400),
    contactable_channels       varchar(100),
    eligibility_evaluated_at   timestamptz,
    queue_id                   uuid            references collection.queue (id),
    owner_type                 varchar(20)     not null,
    assignee_user_id           uuid,
    assignee_team_id           uuid,
    assigned_at                timestamptz,
    next_action_at             timestamptz,
    last_interaction_at        timestamptz,
    last_effective_contact_at  timestamptz,
    interaction_count          integer         not null,
    effective_contact_count    integer         not null,
    score                      integer,
    constraint ck_case_status check (status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE', 'AGREEMENT', 'DISPUTED',
                                                'SUSPENDED', 'LEGAL', 'CLOSED')),
    constraint ck_case_resume_status check (resume_status in ('OPEN', 'IN_NEGOTIATION', 'PROMISE', 'AGREEMENT')),
    constraint ck_case_resume check (resume_status is null or status in ('DISPUTED', 'SUSPENDED', 'LEGAL')),
    constraint ck_case_close_reason check (close_reason in ('SETTLED', 'RETURNED', 'CANCELLED', 'NO_OPEN_TITLES',
                                                            'MANUAL')),
    constraint ck_case_closed check ((status = 'CLOSED') = (closed_at is not null)
                                     and (status = 'CLOSED') = (close_reason is not null)),
    constraint ck_case_owner_type check (owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE')),
    constraint ck_case_owner_user check ((owner_type = 'USER') = (assignee_user_id is not null)),
    constraint ck_case_balance check (open_balance >= 0 and eligible_balance >= 0 and eligible_balance <= open_balance
                                      and open_titles >= 0 and suspended_titles >= 0
                                      and disputed_titles >= 0 and disputed_titles <= open_titles),
    constraint ck_case_counts check (interaction_count >= 0 and effective_contact_count >= 0
                                     and effective_contact_count <= interaction_count),
    constraint ck_case_eligible check (not eligible or (status <> 'CLOSED' and eligibility_reasons is null))
);
create unique index uk_case_active on collection.collection_case (tenant_id, debtor_id, portfolio_id)
    where status <> 'CLOSED';
-- "próximo caso": casos elegíveis sem operador por fila
create index ix_case_queue_available on collection.collection_case (tenant_id, queue_id, status)
    where assignee_user_id is null and eligible;
create index ix_case_assignee on collection.collection_case (tenant_id, assignee_user_id, queue_id)
    where status <> 'CLOSED' and assignee_user_id is not null;
create index ix_case_debtor on collection.collection_case (tenant_id, debtor_id);
create index ix_case_active on collection.collection_case (tenant_id, id) where status <> 'CLOSED';
create index ix_case_portfolio_active on collection.collection_case (tenant_id, portfolio_id, id)
    where status <> 'CLOSED';
create index ix_case_creditor_status on collection.collection_case (tenant_id, creditor_id, status, id);
select shared.enable_tenant_rls('collection.collection_case');

-- -----------------------------------------------------------------------------
-- Histórico de dono do atendimento com vigência [valid_from, valid_to): cada
-- mudança de dono encerra o período vigente e abre outro. Somente o fim de
-- vigência pode ser gravado, uma única vez (privilégio de coluna + gatilho).
-- -----------------------------------------------------------------------------
create table collection.case_assignment (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    case_id              uuid          not null references collection.collection_case (id),
    creditor_id          uuid          not null references organization.creditor (id),
    mode                 varchar(30)   not null,
    previous_owner_type  varchar(20)   not null,
    previous_user_id     uuid,
    owner_type           varchar(20)   not null,
    user_id              uuid,
    team_id              uuid,
    queue_id             uuid          references collection.queue (id),
    reason               varchar(500),
    valid_from           timestamptz   not null,
    valid_to             timestamptz,
    constraint ck_case_assignment_period check (valid_to is null or valid_to >= valid_from),
    constraint ck_case_assignment_user check ((owner_type = 'USER') = (user_id is not null)),
    constraint ck_case_assignment_mode check (mode in ('MANUAL', 'NEXT_CASE', 'DISTRIBUTION', 'REDISTRIBUTION',
                                                       'RELEASE', 'INTERACTION',
                                                       'ASSISTANT_HANDOFF', 'TAKE_OVER', 'OPENED', 'ROUTING',
                                                       'CLOSED')),
    constraint ck_case_assignment_owner check (owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE')
                                               and previous_owner_type in ('ASSISTANT', 'QUEUE', 'USER', 'NONE'))
);
create index ix_case_assignment_case on collection.case_assignment (tenant_id, case_id, valid_from desc);
create unique index uk_case_assignment_current on collection.case_assignment (tenant_id, case_id)
    where valid_to is null;
create index ix_case_assignment_user on collection.case_assignment (tenant_id, user_id, valid_from)
    where user_id is not null;
select shared.enable_tenant_rls('collection.case_assignment');

create or replace function collection.case_assignment_close_only() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Histórico de atribuição é imutável: DELETE não permitido'
            using errcode = 'insufficient_privilege';
    end if;
    if old.valid_to is not null or new.valid_to is null
       or (to_jsonb(new) - array['valid_to', 'version', 'updated_at', 'updated_by'])
          <> (to_jsonb(old) - array['valid_to', 'version', 'updated_at', 'updated_by']) then
        raise exception 'Histórico de atribuição é imutável: somente o fim de vigência pode ser gravado, uma vez'
            using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_case_assignment_close_only before update or delete on collection.case_assignment
    for each row execute function collection.case_assignment_close_only();

-- -----------------------------------------------------------------------------
-- Interações (append-only); external_ref garante idempotência das integrações
-- -----------------------------------------------------------------------------
create table collection.interaction (
    id                 uuid           not null primary key,
    tenant_id          uuid           not null references organization.tenant (id),
    version            bigint         not null,
    created_at         timestamptz    not null,
    created_by         uuid,
    updated_at         timestamptz    not null,
    updated_by         uuid,
    case_id            uuid           not null references collection.collection_case (id),
    debtor_id          uuid           not null references debt.debtor (id),
    creditor_id        uuid           not null references organization.creditor (id),
    portfolio_id       uuid           not null references organization.portfolio (id),
    channel            varchar(20)    not null,
    direction          varchar(10)    not null,
    tabulation_id      uuid           not null references collection.tabulation (id),
    outcome_code       varchar(40)    not null,
    effective_contact  boolean        not null,
    note_encrypted     varchar(12000),
    contact_id         uuid           references debt.debtor_contact (id),
    started_at         timestamptz    not null,
    ended_at           timestamptz,
    duration_seconds   integer,
    operator_id        uuid,
    actor_type         varchar(20)    not null,
    source             varchar(20)    not null,
    external_ref       varchar(120),
    constraint ck_interaction_channel check (channel in ('PHONE', 'WHATSAPP', 'EMAIL', 'SMS', 'IN_PERSON', 'PORTAL',
                                                         'LETTER')),
    constraint ck_interaction_direction check (direction in ('OUTBOUND', 'INBOUND')),
    constraint ck_interaction_source check (source in ('MANUAL', 'TELEPHONY', 'COMMUNICATION', 'ASSISTANT', 'SYSTEM')),
    constraint ck_interaction_period check (ended_at is null or ended_at >= started_at),
    constraint ck_interaction_duration check ((ended_at is null) = (duration_seconds is null)
                                              and (duration_seconds is null or duration_seconds >= 0))
);
create unique index uk_interaction_external on collection.interaction (tenant_id, source, external_ref)
    where external_ref is not null;
create index ix_interaction_case on collection.interaction (tenant_id, case_id, started_at desc);
create index ix_interaction_operator on collection.interaction (tenant_id, operator_id, started_at)
    where operator_id is not null;
select shared.enable_tenant_rls('collection.interaction');
create trigger trg_interaction_append_only before update or delete on collection.interaction
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Tarefas e retornos
-- -----------------------------------------------------------------------------
create table collection.task (
    id                         uuid           not null primary key,
    tenant_id                  uuid           not null references organization.tenant (id),
    version                    bigint         not null,
    created_at                 timestamptz    not null,
    created_by                 uuid,
    updated_at                 timestamptz    not null,
    updated_by                 uuid,
    case_id                    uuid           not null references collection.collection_case (id),
    creditor_id                uuid           not null references organization.creditor (id),
    type                       varchar(20)    not null,
    status                     varchar(20)    not null,
    due_at                     timestamptz    not null,
    tolerance_until            timestamptz    not null,
    assignee_user_id           uuid,
    note_encrypted             varchar(6000),
    origin_interaction_id      uuid           references collection.interaction (id),
    completed_at               timestamptz,
    completed_by               uuid,
    completion_interaction_id  uuid           references collection.interaction (id),
    missed_at                  timestamptz,
    cancel_reason              varchar(500),
    constraint ck_task_type check (type in ('CALLBACK', 'FOLLOW_UP')),
    constraint ck_task_status check (status in ('PENDING', 'DONE', 'CANCELLED', 'MISSED')),
    constraint ck_task_done check ((status = 'DONE') = (completed_at is not null)),
    constraint ck_task_cancelled check ((status = 'CANCELLED') = (cancel_reason is not null)),
    constraint ck_task_missed check (status <> 'MISSED' or missed_at is not null),
    constraint ck_task_tolerance check (tolerance_until >= due_at)
);
create index ix_task_case on collection.task (tenant_id, case_id, due_at);
create index ix_task_agenda on collection.task (tenant_id, assignee_user_id, due_at)
    where status in ('PENDING', 'MISSED');
create index ix_task_pending_due on collection.task (tenant_id, tolerance_until) where status = 'PENDING';
select shared.enable_tenant_rls('collection.task');

-- -----------------------------------------------------------------------------
-- Promessas de pagamento e pagamentos confirmados considerados
-- -----------------------------------------------------------------------------
create table collection.promise (
    id                     uuid            not null primary key,
    tenant_id              uuid            not null references organization.tenant (id),
    version                bigint          not null,
    created_at             timestamptz     not null,
    created_by             uuid,
    updated_at             timestamptz     not null,
    updated_by             uuid,
    case_id                uuid            not null references collection.collection_case (id),
    debtor_id              uuid            not null references debt.debtor (id),
    creditor_id            uuid            not null references organization.creditor (id),
    portfolio_id           uuid            not null references organization.portfolio (id),
    amount                 numeric(19, 2)  not null,
    due_date               date            not null,
    breaks_after           date            not null,
    promised_at            timestamptz     not null,
    status                 varchar(20)     not null,
    paid_amount            numeric(19, 2)  not null,
    origin_interaction_id  uuid            references collection.interaction (id),
    operator_id            uuid,
    resolved_at            timestamptz,
    resolution_reason      varchar(500),
    constraint ck_promise_status check (status in ('PENDING', 'KEPT', 'BROKEN', 'CANCELLED')),
    constraint ck_promise_amount check (amount > 0 and paid_amount >= 0),
    constraint ck_promise_dates check (breaks_after >= due_date),
    constraint ck_promise_resolved check ((status = 'PENDING') = (resolved_at is null))
);
-- uma promessa pendente por caso
create unique index uk_promise_pending on collection.promise (tenant_id, case_id) where status = 'PENDING';
create index ix_promise_case on collection.promise (tenant_id, case_id, promised_at desc);
create index ix_promise_debtor on collection.promise (tenant_id, debtor_id, creditor_id, status);
create index ix_promise_breaking on collection.promise (tenant_id, breaks_after) where status = 'PENDING';
select shared.enable_tenant_rls('collection.promise');

-- payment_id sem chave estrangeira: o módulo payment não é dependência de collection
create table collection.promise_payment (
    promise_id  uuid            not null references collection.promise (id) on delete cascade,
    payment_id  uuid            not null,
    amount      numeric(19, 2)  not null,
    paid_at     timestamptz     not null,
    primary key (promise_id, payment_id)
);
create index ix_promise_payment_payment on collection.promise_payment (payment_id);
alter table collection.promise_payment enable row level security;
alter table collection.promise_payment force row level security;
create policy tenant_isolation on collection.promise_payment
    using (exists (select 1 from collection.promise p where p.id = promise_id))
    with check (exists (select 1 from collection.promise p where p.id = promise_id));

-- -----------------------------------------------------------------------------
-- Contestações (F21), títulos contestados e evidências (append-only)
-- -----------------------------------------------------------------------------
create table collection.dispute (
    id                         uuid            not null primary key,
    tenant_id                  uuid            not null references organization.tenant (id),
    version                    bigint          not null,
    created_at                 timestamptz     not null,
    created_by                 uuid,
    updated_at                 timestamptz     not null,
    updated_by                 uuid,
    case_id                    uuid            not null references collection.collection_case (id),
    debtor_id                  uuid            not null references debt.debtor (id),
    creditor_id                uuid            not null references organization.creditor (id),
    portfolio_id               uuid            not null references organization.portfolio (id),
    type                       varchar(30)     not null,
    origin                     varchar(20)     not null,
    status                     varchar(20)     not null,
    description_encrypted      varchar(24000)  not null,
    blocks_collection          boolean         not null,
    disputed_amount            numeric(19, 2)  not null,
    opened_at                  timestamptz     not null,
    response_due_date          date            not null,
    overdue                    boolean         not null,
    outcome                    varchar(20),
    resolution_note_encrypted  varchar(24000),
    resolved_at                timestamptz,
    resolved_by                uuid,
    constraint ck_dispute_type check (type in ('NOT_RECOGNIZED', 'ALREADY_PAID', 'WRONG_AMOUNT', 'FRAUD', 'PRESCRIBED',
                                               'PRODUCT_OR_SERVICE', 'OTHER')),
    constraint ck_dispute_origin check (origin in ('OPERATOR', 'DEBTOR', 'CREDITOR', 'LEGAL')),
    constraint ck_dispute_status check (status in ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'WITHDRAWN')),
    constraint ck_dispute_outcome check (outcome in ('UPHELD', 'REJECTED', 'PARTIALLY_UPHELD')),
    constraint ck_dispute_resolved check ((status = 'RESOLVED') = (outcome is not null)
                                          and (status in ('RESOLVED', 'WITHDRAWN')) = (resolved_at is not null)),
    constraint ck_dispute_amount check (disputed_amount >= 0)
);
create index ix_dispute_case on collection.dispute (tenant_id, case_id, opened_at desc);
create index ix_dispute_open on collection.dispute (tenant_id, creditor_id, response_due_date)
    where status in ('OPEN', 'UNDER_REVIEW');
select shared.enable_tenant_rls('collection.dispute');

create table collection.dispute_title (
    dispute_id  uuid not null references collection.dispute (id) on delete cascade,
    title_id    uuid not null references debt.debt_title (id),
    primary key (dispute_id, title_id)
);
create index ix_dispute_title_title on collection.dispute_title (title_id);
alter table collection.dispute_title enable row level security;
alter table collection.dispute_title force row level security;
create policy tenant_isolation on collection.dispute_title
    using (exists (select 1 from collection.dispute d where d.id = dispute_id))
    with check (exists (select 1 from collection.dispute d where d.id = dispute_id));

create table collection.dispute_evidence (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    dispute_id    uuid          not null references collection.dispute (id),
    creditor_id   uuid          not null references organization.creditor (id),
    file_key      varchar(512)  not null,
    file_name     varchar(200)  not null,
    content_type  varchar(100)  not null,
    size_bytes    bigint        not null,
    sha256        varchar(64)   not null,
    constraint ck_dispute_evidence_size check (size_bytes > 0)
);
create index ix_dispute_evidence_dispute on collection.dispute_evidence (tenant_id, dispute_id, created_at);
select shared.enable_tenant_rls('collection.dispute_evidence');
create trigger trg_dispute_evidence_append_only before update or delete on collection.dispute_evidence
    for each row execute function shared.forbid_mutation();

grant select, insert, update, delete on all tables in schema collection to app_runtime;
-- histórico imutável: interações e evidências; atribuições só recebem o fim de vigência
revoke update, delete on collection.case_assignment, collection.interaction, collection.dispute_evidence
    from app_runtime;
grant update (valid_to, version, updated_at, updated_by) on collection.case_assignment to app_runtime;
-- casos, tarefas, promessas, contestações e configurações são encerrados/desativados, nunca removidos
revoke delete on collection.collection_case, collection.task, collection.promise, collection.dispute,
    collection.dispute_title, collection.tabulation, collection.queue from app_runtime;
