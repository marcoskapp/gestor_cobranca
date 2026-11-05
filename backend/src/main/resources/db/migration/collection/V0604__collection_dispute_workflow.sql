-- =============================================================================
-- Contestações (F21) — rotina completa (docs/negocio/contestacoes.md, desenho em
-- docs/tecnico/desenho/collection.md §7):
--   * responsável (analista) e histórico de atribuição (append-only);
--   * prazo de resposta ao devedor e prazo interno (SLA) por tipo e credor, com a origem do prazo;
--   * pedido formal ao credor por e-mail (modelos versionados, destinatários do cadastro do credor,
--     link seguro com expiração para as evidências), e-mails enviados (pedido, lembrete e cobrança
--     de retorno vencido) e respostas do credor (manual ou API pública);
--   * resposta fundamentada ao devedor (mensagem transacional do communication) e
--   * ajuste sugerido no título a partir da resolução (pendente até ser feito ou dispensado).
-- Textos livres (corpo dos e-mails, parecer, respostas) ficam criptografados (AES-GCM).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Contestação: responsável, prazo interno, aguardando credor, resposta ao devedor, ajuste
-- -----------------------------------------------------------------------------
alter table collection.dispute
    add column assignee_id                uuid,
    add column assigned_at                timestamptz,
    add column internal_due_date          date,
    add column deadline_source            varchar(20),
    add column awaiting_creditor_since    timestamptz,
    add column debtor_notice_status       varchar(20),
    add column adjustment_action          varchar(30),
    add column adjustment_status          varchar(20),
    add column adjustment_baseline        numeric(19, 2),
    add column adjustment_note_encrypted  varchar(24000),
    add column adjustment_closed_at       timestamptz,
    add column adjustment_closed_by       uuid;

-- contestações existentes: prazo interno igual ao de resposta; resposta ao devedor não exigida
-- retroativamente (as resolvidas antes desta versão não tinham o fluxo).
-- dispute tem RLS forçada: sem app.tenant_id o update não enxerga nenhuma linha e o "set not null" abaixo
-- falha. O preenchimento percorre as assessorias, como a aplicação faria (decisões 849 e 851).
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update collection.dispute set internal_due_date = response_due_date, deadline_source = 'TENANT_DEFAULT';
        update collection.dispute set debtor_notice_status = 'NOT_REQUIRED' where status in ('RESOLVED', 'WITHDRAWN');
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

alter table collection.dispute
    alter column internal_due_date set not null,
    alter column deadline_source set not null,
    add constraint ck_dispute_internal_due check (internal_due_date <= response_due_date),
    add constraint ck_dispute_deadline_source check (deadline_source in ('INFORMED', 'TENANT_DEFAULT', 'TYPE',
                                                                         'CREDITOR', 'CREDITOR_TYPE')),
    add constraint ck_dispute_assignee check ((assignee_id is null) = (assigned_at is null)),
    add constraint ck_dispute_notice_status check (debtor_notice_status in ('PENDING', 'SENT', 'NOT_REQUIRED')),
    add constraint ck_dispute_adjustment_action check (adjustment_action in ('CANCEL_TITLES', 'ADJUST_AMOUNT',
                                                                             'SETTLE_BY_PAYMENT')),
    add constraint ck_dispute_adjustment_status check (adjustment_status in ('PENDING', 'DONE', 'DISMISSED')),
    add constraint ck_dispute_adjustment check ((adjustment_action is null) = (adjustment_status is null)
                                                and (adjustment_status is null or adjustment_baseline is not null));

create index ix_dispute_assignee on collection.dispute (tenant_id, assignee_id, internal_due_date)
    where status in ('OPEN', 'UNDER_REVIEW');
create index ix_dispute_unassigned on collection.dispute (tenant_id, creditor_id, internal_due_date)
    where status in ('OPEN', 'UNDER_REVIEW') and assignee_id is null;
create index ix_dispute_notice_pending on collection.dispute (tenant_id, assignee_id, resolved_at)
    where debtor_notice_status = 'PENDING';
create index ix_dispute_adjustment_pending on collection.dispute (tenant_id, assignee_id, resolved_at)
    where adjustment_status = 'PENDING';
create index ix_dispute_opened on collection.dispute (tenant_id, opened_at);

-- -----------------------------------------------------------------------------
-- Histórico de atribuição do responsável (append-only)
-- -----------------------------------------------------------------------------
create table collection.dispute_assignment (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    dispute_id            uuid          not null references collection.dispute (id),
    previous_assignee_id  uuid,
    assignee_id           uuid,
    mode                  varchar(20)   not null,
    reason                varchar(500),
    assigned_by           uuid          not null,
    assigned_at           timestamptz   not null,
    constraint ck_dispute_assignment_mode check (mode in ('MANUAL', 'TAKE', 'AUTOMATIC', 'REDISTRIBUTION',
                                                         'RELEASE')),
    constraint ck_dispute_assignment_change check (previous_assignee_id is distinct from assignee_id)
);
create index ix_dispute_assignment on collection.dispute_assignment (tenant_id, dispute_id, assigned_at);
select shared.enable_tenant_rls('collection.dispute_assignment');
create trigger trg_dispute_assignment_append_only before update or delete on collection.dispute_assignment
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Parâmetros das contestações por assessoria e prazos por tipo/credor
-- -----------------------------------------------------------------------------
create table collection.dispute_settings (
    id                     uuid          not null primary key,
    tenant_id              uuid          not null references organization.tenant (id),
    version                bigint        not null,
    created_at             timestamptz   not null,
    created_by             uuid,
    updated_at             timestamptz   not null,
    updated_by             uuid,
    distribution_mode      varchar(20)   not null,
    internal_days          integer       not null,
    creditor_request_days  integer       not null,
    reminder_days_before   integer       not null,
    evidence_link_days     integer       not null,
    constraint uk_dispute_settings_tenant unique (tenant_id),
    constraint ck_dispute_settings_mode check (distribution_mode in ('MANUAL', 'LEAST_LOADED')),
    constraint ck_dispute_settings_values check (internal_days between 1 and 90
                                                 and creditor_request_days between 1 and 60
                                                 and reminder_days_before between 0 and 10
                                                 and evidence_link_days between 1 and 30)
);
select shared.enable_tenant_rls('collection.dispute_settings');

create table collection.dispute_deadline_rule (
    id             uuid          not null primary key,
    tenant_id      uuid          not null references organization.tenant (id),
    version        bigint        not null,
    created_at     timestamptz   not null,
    created_by     uuid,
    updated_at     timestamptz   not null,
    updated_by     uuid,
    creditor_id    uuid          references organization.creditor (id),
    type           varchar(30),
    response_days  integer       not null,
    internal_days  integer       not null,
    active         boolean       not null,
    constraint ck_dispute_rule_scope check (creditor_id is not null or type is not null),
    constraint ck_dispute_rule_type check (type in ('NOT_RECOGNIZED', 'ALREADY_PAID', 'WRONG_AMOUNT', 'FRAUD',
                                                    'PRESCRIBED', 'PRODUCT_OR_SERVICE', 'OTHER')),
    constraint ck_dispute_rule_days check (response_days between 1 and 90 and internal_days between 1 and 90
                                           and internal_days <= response_days)
);
create unique index uk_dispute_deadline_rule on collection.dispute_deadline_rule
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(type, '*'))
    where active;
select shared.enable_tenant_rls('collection.dispute_deadline_rule');

-- analistas que recebem contestações na distribuição automática (credor nulo = todos os credores)
create table collection.dispute_pool_member (
    id           uuid          not null primary key,
    tenant_id    uuid          not null references organization.tenant (id),
    version      bigint        not null,
    created_at   timestamptz   not null,
    created_by   uuid,
    updated_at   timestamptz   not null,
    updated_by   uuid,
    creditor_id  uuid          references organization.creditor (id),
    user_id      uuid          not null,
    active       boolean       not null
);
create unique index uk_dispute_pool_member on collection.dispute_pool_member
    (tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid), user_id) where active;
select shared.enable_tenant_rls('collection.dispute_pool_member');

-- -----------------------------------------------------------------------------
-- Modelos de e-mail das contestações (versões imutáveis; a maior é a vigente)
-- -----------------------------------------------------------------------------
create table collection.dispute_template (
    id               uuid          not null primary key,
    tenant_id        uuid          not null references organization.tenant (id),
    version          bigint        not null,
    created_at       timestamptz   not null,
    created_by       uuid,
    updated_at       timestamptz   not null,
    updated_by       uuid,
    kind             varchar(20)   not null,
    template_key     varchar(30)   not null,
    template_version integer       not null,
    subject          varchar(200)  not null,
    body             varchar(8000) not null,
    constraint uk_dispute_template unique (tenant_id, kind, template_key, template_version),
    constraint ck_dispute_template_kind check (kind in ('CREDITOR_REQUEST', 'DEBTOR_RESPONSE')),
    constraint ck_dispute_template_version check (template_version >= 1)
);
select shared.enable_tenant_rls('collection.dispute_template');
create trigger trg_dispute_template_append_only before update or delete on collection.dispute_template
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Pedido ao credor, e-mails do pedido e respostas do credor
-- -----------------------------------------------------------------------------
create table collection.dispute_creditor_request (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    dispute_id              uuid           not null references collection.dispute (id),
    creditor_id             uuid           not null references organization.creditor (id),
    sequence_number         integer        not null,
    template_key            varchar(30)    not null,
    template_version        integer        not null,
    to_recipients           varchar(2000)  not null,
    cc_recipients           varchar(2000),
    reply_to                varchar(200),
    subject_encrypted       varchar(2000)  not null,
    body_encrypted          varchar(48000) not null,
    requested_due_date      date           not null,
    status                  varchar(20)    not null,
    link_expires_at         timestamptz,
    link_downloads          integer        not null,
    overdue                 boolean        not null,
    reminder_queued_at      timestamptz,
    overdue_notice_queued_at timestamptz,
    sent_at                 timestamptz,
    answered_at             timestamptz,
    requested_by            uuid           not null,
    constraint uk_dispute_creditor_request unique (tenant_id, dispute_id, sequence_number),
    constraint ck_dispute_request_status check (status in ('QUEUED', 'SENT', 'FAILED', 'ANSWERED', 'CANCELLED')),
    constraint ck_dispute_request_downloads check (link_downloads >= 0)
);
create index ix_dispute_request_dispute on collection.dispute_creditor_request (tenant_id, dispute_id, sequence_number);
create index ix_dispute_request_followup on collection.dispute_creditor_request (tenant_id, requested_due_date)
    where status = 'SENT';
select shared.enable_tenant_rls('collection.dispute_creditor_request');

create table collection.dispute_creditor_request_title (
    request_id  uuid not null references collection.dispute_creditor_request (id) on delete cascade,
    title_id    uuid not null references debt.debt_title (id),
    primary key (request_id, title_id)
);
alter table collection.dispute_creditor_request_title enable row level security;
alter table collection.dispute_creditor_request_title force row level security;
create policy tenant_isolation on collection.dispute_creditor_request_title
    using (exists (select 1 from collection.dispute_creditor_request r where r.id = request_id))
    with check (exists (select 1 from collection.dispute_creditor_request r where r.id = request_id));

create table collection.dispute_creditor_request_evidence (
    request_id   uuid not null references collection.dispute_creditor_request (id) on delete cascade,
    evidence_id  uuid not null references collection.dispute_evidence (id),
    primary key (request_id, evidence_id)
);
alter table collection.dispute_creditor_request_evidence enable row level security;
alter table collection.dispute_creditor_request_evidence force row level security;
create policy tenant_isolation on collection.dispute_creditor_request_evidence
    using (exists (select 1 from collection.dispute_creditor_request r where r.id = request_id))
    with check (exists (select 1 from collection.dispute_creditor_request r where r.id = request_id));

-- e-mails enviados de um pedido: o pedido, o lembrete antes do prazo e a cobrança do retorno vencido
create table collection.dispute_mail (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    request_id      uuid          not null references collection.dispute_creditor_request (id),
    purpose         varchar(20)   not null,
    status          varchar(20)   not null,
    attempts        integer       not null,
    sent_at         timestamptz,
    failed_at       timestamptz,
    failure_code    varchar(60),
    constraint uk_dispute_mail_purpose unique (tenant_id, request_id, purpose),
    constraint ck_dispute_mail_purpose check (purpose in ('REQUEST', 'REMINDER', 'OVERDUE')),
    constraint ck_dispute_mail_status check (status in ('QUEUED', 'SENT', 'FAILED', 'SKIPPED')),
    constraint ck_dispute_mail_failed check ((status = 'FAILED') = (failure_code is not null))
);
create index ix_dispute_mail_request on collection.dispute_mail (tenant_id, request_id);
select shared.enable_tenant_rls('collection.dispute_mail');

create table collection.dispute_creditor_response (
    id              uuid           not null primary key,
    tenant_id       uuid           not null references organization.tenant (id),
    version         bigint         not null,
    created_at      timestamptz    not null,
    created_by      uuid,
    updated_at      timestamptz    not null,
    updated_by      uuid,
    dispute_id      uuid           not null references collection.dispute (id),
    request_id      uuid           references collection.dispute_creditor_request (id),
    source          varchar(20)    not null,
    position        varchar(30)    not null,
    note_encrypted  varchar(24000) not null,
    received_at     timestamptz    not null,
    recorded_by     uuid           not null,
    constraint ck_dispute_response_source check (source in ('MANUAL', 'PUBLIC_API')),
    constraint ck_dispute_response_position check (position in ('CONFIRMS_DEBT', 'ACCEPTS_CLAIM', 'PARTIAL',
                                                                'INFORMATION'))
);
create index ix_dispute_response_dispute on collection.dispute_creditor_response (tenant_id, dispute_id, received_at);
select shared.enable_tenant_rls('collection.dispute_creditor_response');
create trigger trg_dispute_response_append_only before update or delete on collection.dispute_creditor_response
    for each row execute function shared.forbid_mutation();

-- documentos enviados pelo credor ficam como evidências da contestação, ligados à resposta
alter table collection.dispute_evidence
    add column creditor_response_id uuid references collection.dispute_creditor_response (id);

-- rotas dos links seguros das evidências enviadas ao credor: tabela global (o link chega sem
-- assessoria), só com o hash SHA-256 do token aleatório, ids e a validade — sem dado pessoal
create table collection.dispute_link_route (
    token_hash   varchar(64)  not null primary key,
    tenant_id    uuid         not null references organization.tenant (id),
    request_id   uuid         not null,
    expires_at   timestamptz  not null,
    created_at   timestamptz  not null
);
create index ix_dispute_link_route_expiry on collection.dispute_link_route (expires_at);

-- -----------------------------------------------------------------------------
-- Resposta ao devedor (mensagem transacional pelo communication ou registro de outro meio)
-- -----------------------------------------------------------------------------
create table collection.dispute_debtor_notice (
    id                         uuid           not null primary key,
    tenant_id                  uuid           not null references organization.tenant (id),
    version                    bigint         not null,
    created_at                 timestamptz    not null,
    created_by                 uuid,
    updated_at                 timestamptz    not null,
    updated_by                 uuid,
    dispute_id                 uuid           not null references collection.dispute (id),
    channel                    varchar(20)    not null,
    message_id                 uuid,
    status                     varchar(20)    not null,
    failure_detail             varchar(500),
    template_key               varchar(30),
    template_version           integer,
    subject_encrypted          varchar(2000),
    body_encrypted             varchar(48000) not null,
    sent_by                    uuid           not null,
    status_at                  timestamptz    not null,
    constraint ck_dispute_notice_channel check (channel in ('EMAIL', 'WHATSAPP', 'OTHER')),
    constraint ck_dispute_notice_status check (status in ('QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED',
                                                          'BLOCKED', 'REGISTERED')),
    constraint ck_dispute_notice_message check ((channel = 'OTHER') = (message_id is null))
);
create index ix_dispute_notice_dispute on collection.dispute_debtor_notice (tenant_id, dispute_id, created_at);
create unique index uk_dispute_notice_message on collection.dispute_debtor_notice (tenant_id, message_id)
    where message_id is not null;
select shared.enable_tenant_rls('collection.dispute_debtor_notice');

grant select, insert on collection.dispute_assignment, collection.dispute_template,
    collection.dispute_creditor_response to app_runtime;
grant select, insert, update on collection.dispute_settings, collection.dispute_deadline_rule,
    collection.dispute_pool_member, collection.dispute_creditor_request, collection.dispute_mail,
    collection.dispute_debtor_notice to app_runtime;
grant select, insert, delete on collection.dispute_creditor_request_title, collection.dispute_creditor_request_evidence
    to app_runtime;
grant select, insert, delete on collection.dispute_link_route to app_runtime;
