-- =============================================================================
-- Tabelas transversais: auditoria, eventos de negócio, log de acesso, eventos de
-- integração, fila de jobs, idempotência e lock de agendamento.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Trilha de auditoria (append-only, encadeada por hash)
-- -----------------------------------------------------------------------------
create table shared.audit_event (
    seq            bigint generated always as identity,
    id             uuid         not null primary key,
    tenant_id      uuid         not null,
    occurred_at    timestamptz  not null,
    actor_type     varchar(20)  not null,
    actor_id       uuid         not null,
    actor_name     varchar(200),
    session_id     uuid,
    request_id     varchar(64),
    client_ip      inet,
    action         varchar(100) not null,
    entity_type    varchar(60)  not null,
    entity_id      uuid         not null,
    creditor_id    uuid,
    reason         text,
    before_state   jsonb        not null default '{}'::jsonb,
    after_state    jsonb        not null default '{}'::jsonb,
    metadata       jsonb        not null default '{}'::jsonb,
    content_hash   char(64)     not null,
    chain_hash     char(64),
    sealed_at      timestamptz,
    constraint uk_audit_event_seq unique (seq)
);
create index ix_audit_event_entity on shared.audit_event (tenant_id, entity_type, entity_id, occurred_at desc);
create index ix_audit_event_actor on shared.audit_event (tenant_id, actor_id, occurred_at desc);
create index ix_audit_event_time on shared.audit_event (tenant_id, occurred_at desc);
create index ix_audit_event_unsealed on shared.audit_event (tenant_id, seq) where chain_hash is null;
select shared.enable_tenant_rls('shared.audit_event');

-- Hash do conteúdo calculado no banco sobre a forma canônica do jsonb, garantindo que a
-- verificação posterior use exatamente a mesma representação.
create or replace function shared.audit_content_hash(e shared.audit_event) returns char(64)
    language sql stable
as $$
    select encode(sha256(convert_to(concat_ws('|',
        e.tenant_id::text,
        to_char(e.occurred_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
        e.actor_id::text,
        e.action,
        e.entity_type,
        e.entity_id::text,
        coalesce(e.creditor_id::text, ''),
        coalesce(e.before_state::text, ''),
        coalesce(e.after_state::text, ''),
        coalesce(e.metadata::text, ''),
        coalesce(e.reason, '')), 'UTF8')), 'hex')
$$;

create or replace function shared.audit_event_hash() returns trigger
    language plpgsql
as $$
begin
    new.content_hash := shared.audit_content_hash(new);
    new.chain_hash := null;
    new.sealed_at := null;
    return new;
end
$$;
create trigger trg_audit_event_hash before insert on shared.audit_event
    for each row execute function shared.audit_event_hash();

-- Somente selagem é permitida: chain_hash/sealed_at preenchidos uma única vez.
create or replace function shared.audit_event_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'audit_event é append-only' using errcode = 'insufficient_privilege';
    end if;
    if old.chain_hash is not null
       or (to_jsonb(new) - 'chain_hash' - 'sealed_at') is distinct from (to_jsonb(old) - 'chain_hash' - 'sealed_at') then
        raise exception 'audit_event só admite selagem única' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_audit_event_guard before update or delete on shared.audit_event
    for each row execute function shared.audit_event_guard();
revoke update, delete on shared.audit_event from app_runtime;
grant update (chain_hash, sealed_at) on shared.audit_event to app_runtime;

-- -----------------------------------------------------------------------------
-- Eventos de negócio (insumo de inteligência)
-- -----------------------------------------------------------------------------
create table shared.business_event (
    id              uuid          not null primary key,
    tenant_id       uuid          not null,
    occurred_at     timestamptz   not null,
    event_type      varchar(80)   not null,
    actor_type      varchar(20)   not null,
    actor_id        uuid          not null,
    session_id      uuid,
    request_id      varchar(64),
    creditor_id     uuid,
    portfolio_id    uuid,
    debtor_id       uuid,
    case_id         uuid,
    reference_type  varchar(60),
    reference_id    uuid,
    channel         varchar(30),
    amount          numeric(19, 2),
    duration_ms     bigint,
    attributes      jsonb         not null default '{}'::jsonb
);
create index ix_business_event_type_time on shared.business_event (tenant_id, event_type, occurred_at desc);
create index ix_business_event_creditor on shared.business_event (tenant_id, creditor_id, occurred_at desc);
create index ix_business_event_actor on shared.business_event (tenant_id, actor_id, occurred_at desc);
create index ix_business_event_case on shared.business_event (tenant_id, case_id) where case_id is not null;
create index brin_business_event_time on shared.business_event using brin (occurred_at);
select shared.enable_tenant_rls('shared.business_event');
create trigger trg_business_event_append_only before update or delete on shared.business_event
    for each row execute function shared.forbid_mutation();
revoke update, delete on shared.business_event from app_runtime;

-- -----------------------------------------------------------------------------
-- Log de acesso e segurança
-- Gravação permitida mesmo sem tenant (ex.: login com tenant inexistente);
-- leitura restrita ao tenant corrente.
-- -----------------------------------------------------------------------------
create table shared.access_log (
    id           uuid          not null primary key,
    tenant_id    uuid,
    occurred_at  timestamptz   not null,
    event_type   varchar(40)   not null,
    success      boolean       not null,
    actor_type   varchar(20)   not null,
    actor_id     uuid,
    session_id   uuid,
    principal    varchar(200),
    client_ip    inet,
    user_agent   varchar(512),
    request_id   varchar(64),
    route        varchar(200),
    details      jsonb         not null default '{}'::jsonb
);
create index ix_access_log_tenant_time on shared.access_log (tenant_id, occurred_at desc);
create index ix_access_log_actor on shared.access_log (tenant_id, actor_id, occurred_at desc);
create index ix_access_log_type on shared.access_log (tenant_id, event_type, occurred_at desc);
create index ix_access_log_ip on shared.access_log (client_ip, occurred_at desc);
alter table shared.access_log enable row level security;
alter table shared.access_log force row level security;
create policy access_log_insert on shared.access_log for insert with check (true);
create policy access_log_read on shared.access_log for select using (tenant_id = shared.current_tenant_id());
create trigger trg_access_log_append_only before update or delete on shared.access_log
    for each row execute function shared.forbid_mutation();
revoke update, delete on shared.access_log from app_runtime;

-- -----------------------------------------------------------------------------
-- Consumo idempotente de eventos de integração
-- -----------------------------------------------------------------------------
create table shared.consumed_event (
    consumer     varchar(120) not null,
    event_id     uuid         not null,
    event_type   varchar(120) not null,
    tenant_id    uuid         not null,
    consumed_at  timestamptz  not null,
    primary key (consumer, event_id)
);
create index ix_consumed_event_time on shared.consumed_event (consumed_at);

-- -----------------------------------------------------------------------------
-- Registro de publicação de eventos (Spring Modulith, JPA)
-- -----------------------------------------------------------------------------
create table public.event_publication (
    id                      uuid          not null primary key,
    listener_id             text          not null,
    event_type              text          not null,
    serialized_event        text          not null,
    publication_date        timestamptz   not null,
    completion_date         timestamptz,
    last_resubmission_date  timestamptz,
    completion_attempts     integer       not null default 0,
    status                  varchar(20)
);
create index ix_event_publication_incomplete on public.event_publication (publication_date) where completion_date is null;
create index ix_event_publication_completed on public.event_publication (completion_date) where completion_date is not null;

-- -----------------------------------------------------------------------------
-- Fila de jobs (sem RLS: o worker reivindica jobs de todos os tenants e executa
-- cada um no contexto do seu tenant)
-- -----------------------------------------------------------------------------
create table shared.processing_job (
    id            uuid          not null primary key,
    tenant_id     uuid          not null,
    type          varchar(80)   not null,
    payload       jsonb         not null default '{}'::jsonb,
    status        varchar(20)   not null,
    priority      smallint      not null default 0,
    run_at        timestamptz   not null,
    attempts      integer       not null default 0,
    dedup_key     varchar(200),
    locked_by     varchar(200),
    locked_until  timestamptz,
    started_at    timestamptz,
    finished_at   timestamptz,
    last_error    text,
    created_at    timestamptz   not null,
    created_by    uuid,
    constraint ck_processing_job_status check (status in ('PENDING', 'RUNNING', 'DONE', 'DEAD', 'CANCELLED'))
);
create index ix_processing_job_ready on shared.processing_job (priority desc, run_at) where status = 'PENDING';
create index ix_processing_job_lease on shared.processing_job (locked_until) where status = 'RUNNING';
create index ix_processing_job_tenant on shared.processing_job (tenant_id, type, created_at desc);
create unique index uk_processing_job_dedup on shared.processing_job (tenant_id, type, dedup_key)
    where dedup_key is not null and status in ('PENDING', 'RUNNING');

-- -----------------------------------------------------------------------------
-- Idempotência de comandos
-- -----------------------------------------------------------------------------
create table shared.idempotency_key (
    tenant_id     uuid          not null,
    scope         varchar(80)   not null,
    idem_key      varchar(128)  not null,
    request_hash  varchar(128)  not null,
    resource_id   uuid          not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    primary key (tenant_id, scope, idem_key)
);
select shared.enable_tenant_rls('shared.idempotency_key');

-- -----------------------------------------------------------------------------
-- ShedLock (agendamento distribuído)
-- -----------------------------------------------------------------------------
create table shared.shedlock (
    name        varchar(64)  not null primary key,
    lock_until  timestamp    not null,
    locked_at   timestamp    not null,
    locked_by   varchar(255) not null
);

grant select, insert, update, delete on all tables in schema shared to app_runtime;
revoke update, delete on shared.audit_event from app_runtime;
grant update (chain_hash, sealed_at) on shared.audit_event to app_runtime;
revoke update, delete on shared.business_event from app_runtime;
revoke update, delete on shared.access_log from app_runtime;
grant select, insert, update, delete on public.event_publication to app_runtime;
