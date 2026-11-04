-- =============================================================================
-- Controle dos tokens pela plataforma e tráfego recusado (docs/negocio/api-publica.md
-- §9.1 e §9.3; decisões 760–775).
--
-- * integration_token: nova situação PAUSED — pausa reversível imposta pela
--   equipe da plataforma (motivo obrigatório, quem e quando; retomada automática
--   opcional em resume_at). Só a plataforma pausa e retoma; a assessoria continua
--   podendo revogar o próprio token (a revogação encerra a pausa). A revogação
--   pela plataforma fica marcada em revoked_by_platform.
-- * usage_rejected_hourly: chamadas recusadas na autenticação de um token
--   identificado (segredo correto) — pausado, revogado, expirado, assessoria
--   suspensa, credor inativo, IP fora da lista — por (credor, token, hora, motivo,
--   método). Só medição de tráfego: nunca cobradas e fora de usage_hourly (que
--   continua só com chamadas autenticadas, base da cobrança).
-- =============================================================================
alter table publicapi.integration_token
    add column paused_at            timestamptz,
    add column paused_by            uuid,
    add column pause_reason         varchar(500),
    add column resume_at            timestamptz,
    add column revoked_by_platform  boolean not null default false;

alter table publicapi.integration_token drop constraint ck_integration_token_status;
alter table publicapi.integration_token add constraint ck_integration_token_status
    check (status in ('ACTIVE', 'PAUSED', 'REVOKED'));
alter table publicapi.integration_token add constraint ck_integration_token_paused
    check ((status = 'PAUSED') = (paused_at is not null)
        and (paused_at is null) = (pause_reason is null)
        and (paused_at is null) = (paused_by is null)
        and (resume_at is null or (status = 'PAUSED' and resume_at > paused_at)));
alter table publicapi.integration_token add constraint ck_integration_token_revoked_by_platform
    check (not revoked_by_platform or status = 'REVOKED');

-- retomadas automáticas pendentes (job a cada poucos minutos, por assessoria)
create index ix_integration_token_resume on publicapi.integration_token (tenant_id, resume_at)
    where status = 'PAUSED' and resume_at is not null;

create table publicapi.usage_rejected_hourly (
    tenant_id    uuid          not null references organization.tenant (id),
    creditor_id  uuid          not null references organization.creditor (id),
    token_id     uuid          not null references publicapi.integration_token (id),
    hour_start   timestamptz   not null,
    reason       varchar(30)   not null,
    method       varchar(10)   not null,
    attempts     bigint        not null,
    updated_at   timestamptz   not null,
    primary key (tenant_id, creditor_id, token_id, hour_start, reason, method),
    constraint ck_usage_rejected_hour check (date_trunc('hour', hour_start) = hour_start),
    constraint ck_usage_rejected_attempts check (attempts > 0),
    constraint ck_usage_rejected_reason check (reason in ('paused', 'revoked', 'expired', 'tenant_inactive',
        'creditor_inactive', 'ip_not_allowed'))
);
create index ix_usage_rejected_tenant_hour on publicapi.usage_rejected_hourly (tenant_id, hour_start);
select shared.enable_tenant_rls('publicapi.usage_rejected_hourly');

create or replace function publicapi.usage_rejected_hourly_guard() returns trigger
    language plpgsql
as $$
begin
    if new.attempts < old.attempts then
        raise exception 'Tráfego recusado da API pública só pode ser somado' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_usage_rejected_hourly_guard before update on publicapi.usage_rejected_hourly
    for each row execute function publicapi.usage_rejected_hourly_guard();

grant select, insert, update on publicapi.usage_rejected_hourly to app_runtime;
revoke delete on publicapi.usage_rejected_hourly from app_runtime;
