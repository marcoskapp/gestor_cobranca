-- =============================================================================
-- SLA de primeira resposta configurável por assessoria e canal (caixa de entrada) e
-- cursor da varredura de estouros (cada conversa estourada é contada uma única vez).
-- Sem registro, valem os padrões da plataforma: WhatsApp/SMS 15/60 min, e-mail 4/24 h.
-- =============================================================================

create table communication.response_target (
    id               uuid          not null primary key,
    tenant_id        uuid          not null references organization.tenant (id),
    version          bigint        not null,
    created_at       timestamptz   not null,
    created_by       uuid,
    updated_at       timestamptz   not null,
    updated_by       uuid,
    channel          varchar(20)   not null,
    warning_minutes  integer       not null,
    breach_minutes   integer       not null,
    constraint uk_response_target unique (tenant_id, channel),
    constraint ck_response_target_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS')),
    constraint ck_response_target_minutes check (warning_minutes between 1 and 10080
                                                 and breach_minutes between 1 and 10080
                                                 and warning_minutes < breach_minutes)
);
select shared.enable_tenant_rls('communication.response_target');

create table communication.sla_scan_cursor (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    channel         varchar(20)   not null,
    scanned_until   timestamptz   not null,
    constraint uk_sla_scan_cursor unique (tenant_id, channel),
    constraint ck_sla_scan_cursor_channel check (channel in ('WHATSAPP', 'EMAIL', 'SMS'))
);
select shared.enable_tenant_rls('communication.sla_scan_cursor');

-- varredura: conversas abertas aguardando resposta por canal e início da espera
create index ix_conversation_awaiting on communication.conversation (tenant_id, channel, awaiting_since)
    where status = 'OPEN' and awaiting_since is not null;

grant select, insert, update on communication.response_target, communication.sla_scan_cursor to app_runtime;
