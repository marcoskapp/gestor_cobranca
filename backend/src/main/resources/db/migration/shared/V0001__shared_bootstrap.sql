-- =============================================================================
-- Bootstrap do banco: papel de runtime, schemas por módulo e funções de RLS.
--
-- Papéis:
--   * dono das tabelas  = usuário que executa o Flyway (ex.: app_owner). Não é usado pela aplicação em runtime.
--   * app_runtime       = papel com que a aplicação conecta. Sem BYPASSRLS, não é dono de nenhuma tabela.
-- A senha de app_runtime é definida fora das migrations (scripts/db/bootstrap-roles.sql / Secrets).
-- =============================================================================

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'app_runtime') then
        create role app_runtime nologin nobypassrls;
    end if;
end
$$;

-- Um schema por módulo (bounded context)
create schema if not exists shared;
create schema if not exists organization;
create schema if not exists identity;
create schema if not exists debt;
create schema if not exists ingestion;
create schema if not exists compliance;
create schema if not exists collection;
create schema if not exists negotiation;
create schema if not exists payment;
create schema if not exists settlement;
create schema if not exists communication;
create schema if not exists campaign;
create schema if not exists telephony;
create schema if not exists portal;
create schema if not exists scoring;
create schema if not exists assistant;
create schema if not exists analytics;

do $$
declare
    s text;
begin
    foreach s in array array['public', 'shared', 'organization', 'identity', 'debt', 'ingestion', 'compliance', 'collection',
                             'negotiation', 'payment', 'settlement', 'communication', 'campaign', 'telephony',
                             'portal', 'scoring', 'assistant', 'analytics']
    loop
        execute format('grant usage on schema %I to app_runtime', s);
        execute format('alter default privileges in schema %I grant select, insert, update, delete on tables to app_runtime', s);
        execute format('alter default privileges in schema %I grant usage, select on sequences to app_runtime', s);
        execute format('alter default privileges in schema %I grant execute on functions to app_runtime', s);
    end loop;
end
$$;

-- -----------------------------------------------------------------------------
-- Contexto da transação (definido por TenantAwareJpaTransactionManager)
-- -----------------------------------------------------------------------------
create or replace function shared.current_tenant_id() returns uuid
    language sql stable parallel safe
as $$
    select nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

create or replace function shared.current_actor_id() returns uuid
    language sql stable parallel safe
as $$
    select nullif(current_setting('app.actor_id', true), '')::uuid
$$;

-- -----------------------------------------------------------------------------
-- Habilita e força Row Level Security por tenant em uma tabela.
-- Sem app.tenant_id definido, nenhuma linha é visível nem gravável.
-- -----------------------------------------------------------------------------
create or replace function shared.enable_tenant_rls(target regclass) returns void
    language plpgsql
as $$
begin
    execute format('alter table %s enable row level security', target);
    execute format('alter table %s force row level security', target);
    execute format('drop policy if exists tenant_isolation on %s', target);
    execute format(
        'create policy tenant_isolation on %s using (tenant_id = shared.current_tenant_id()) '
        || 'with check (tenant_id = shared.current_tenant_id())', target);
end
$$;

-- -----------------------------------------------------------------------------
-- Gatilho para tabelas append-only (auditoria, logs, eventos financeiros).
-- -----------------------------------------------------------------------------
create or replace function shared.forbid_mutation() returns trigger
    language plpgsql
as $$
begin
    raise exception 'Tabela % é append-only: % não permitido', tg_table_name, tg_op
        using errcode = 'insufficient_privilege';
end
$$;
