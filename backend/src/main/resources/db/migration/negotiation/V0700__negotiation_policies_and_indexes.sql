-- =============================================================================
-- Módulo negotiation (parte 1) — docs/design/negociacao-e-alcadas.md.
--
-- Este arquivo: schema, políticas de cobrança versionadas por credor e tabela
-- manual de índices de correção monetária. Faixa da parte 1: V0700–V0749
-- (V0750+ reservadas à parte 2: propostas, aprovação, acordos e parcelas).
-- =============================================================================

create schema if not exists negotiation;
grant usage on schema negotiation to app_runtime;
alter default privileges in schema negotiation grant select, insert, update, delete on tables to app_runtime;
alter default privileges in schema negotiation grant usage, select on sequences to app_runtime;

-- -----------------------------------------------------------------------------
-- Política de cobrança e negociação: cada linha é uma versão (credor + versão).
-- Termos tipados (regras base + sobreposições por carteira/faixa de atraso) em
-- jsonb com versão de esquema, validados pela aplicação antes de gravar.
-- Versões publicadas são imutáveis (gatilho); só a vigência pode ser encerrada.
-- -----------------------------------------------------------------------------
create table negotiation.collection_policy (
    id                    uuid           not null primary key,
    tenant_id             uuid           not null references organization.tenant (id),
    version               bigint         not null,
    created_at            timestamptz    not null,
    created_by            uuid,
    updated_at            timestamptz    not null,
    updated_by            uuid,
    creditor_id           uuid           not null references organization.creditor (id),
    policy_version        integer        not null,
    name                  varchar(120)   not null,
    description           varchar(1000),
    status                varchar(20)    not null,
    effective_from        date,
    effective_to          date,
    terms                 jsonb          not null,
    terms_schema_version  integer        not null,
    published_at          timestamptz,
    published_by          uuid,
    retired_at            timestamptz,
    retired_by            uuid,
    constraint uk_collection_policy_version unique (tenant_id, creditor_id, policy_version),
    constraint ck_collection_policy_status check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    constraint ck_collection_policy_version check (policy_version > 0 and terms_schema_version > 0),
    constraint ck_collection_policy_period check (effective_to is null or effective_from is null
                                                  or effective_to >= effective_from - 1),
    constraint ck_collection_policy_published check (status = 'DRAFT' or (effective_from is not null
                                                     and published_at is not null)),
    constraint ck_collection_policy_retired check (status <> 'RETIRED' or (effective_to is not null
                                                   and retired_at is not null))
);
create unique index uk_collection_policy_draft on negotiation.collection_policy (tenant_id, creditor_id)
    where status = 'DRAFT';
create index ix_collection_policy_effective on negotiation.collection_policy (tenant_id, creditor_id, status,
                                                                             effective_from);
select shared.enable_tenant_rls('negotiation.collection_policy');

create or replace function negotiation.collection_policy_guard() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then
            raise exception 'Política publicada não pode ser removida' using errcode = 'insufficient_privilege';
        end if;
        return old;
    end if;
    if old.status <> 'DRAFT' and (new.terms is distinct from old.terms
                                  or new.creditor_id <> old.creditor_id
                                  or new.policy_version <> old.policy_version
                                  or new.effective_from is distinct from old.effective_from
                                  or new.terms_schema_version <> old.terms_schema_version
                                  or new.status = 'DRAFT') then
        raise exception 'Versão publicada de política é imutável' using errcode = 'insufficient_privilege';
    end if;
    if old.status <> 'DRAFT' and old.effective_to is not null
       and (new.effective_to is null or new.effective_to > old.effective_to) then
        raise exception 'A vigência de uma versão publicada só pode ser encurtada' using errcode = 'insufficient_privilege';
    end if;
    return new;
end
$$;
create trigger trg_collection_policy_guard before update or delete on negotiation.collection_policy
    for each row execute function negotiation.collection_policy_guard();

-- -----------------------------------------------------------------------------
-- Índices de correção monetária (tabela manual por tenant) e variações mensais
-- (% no mês, com sinal). Correções de valor são permitidas e auditadas; as
-- simulações guardam o fator usado na memória de cálculo.
-- -----------------------------------------------------------------------------
create table negotiation.monetary_index (
    id          uuid          not null primary key,
    tenant_id   uuid          not null references organization.tenant (id),
    version     bigint        not null,
    created_at  timestamptz   not null,
    created_by  uuid,
    updated_at  timestamptz   not null,
    updated_by  uuid,
    code        varchar(20)   not null,
    name        varchar(120)  not null,
    source      varchar(200),
    constraint uk_monetary_index_code unique (tenant_id, code),
    constraint ck_monetary_index_code check (code ~ '^[A-Z0-9_]{2,20}$')
);
select shared.enable_tenant_rls('negotiation.monetary_index');

create table negotiation.monetary_index_rate (
    id            uuid           not null primary key,
    tenant_id     uuid           not null references organization.tenant (id),
    version       bigint         not null,
    created_at    timestamptz    not null,
    created_by    uuid,
    updated_at    timestamptz    not null,
    updated_by    uuid,
    index_id      uuid           not null references negotiation.monetary_index (id),
    competence    date           not null,
    rate_percent  numeric(9, 4)  not null,
    constraint uk_monetary_index_rate unique (tenant_id, index_id, competence),
    constraint ck_monetary_index_rate_competence check (extract(day from competence) = 1),
    constraint ck_monetary_index_rate_value check (rate_percent > -100 and rate_percent < 100)
);
select shared.enable_tenant_rls('negotiation.monetary_index_rate');

grant select, insert, update, delete on negotiation.collection_policy, negotiation.monetary_index,
    negotiation.monetary_index_rate to app_runtime;
-- histórico de índices e políticas não é apagado pela aplicação (rascunho de política pode ser
-- descartado no futuro; hoje não há remoção)
revoke delete on negotiation.monetary_index, negotiation.monetary_index_rate from app_runtime;
