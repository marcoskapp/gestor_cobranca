-- =============================================================================
-- Telephony: ambiente multiprovedor.
--
-- * Várias contas ativas por assessoria, de provedores diferentes (sai o índice
--   de "uma conta ativa por credor"). Provedores: SANDBOX (simulador), ALVARIA
--   (Alvaria CX, antiga Aspect; contrato da API a confirmar) e TARIFANDO
--   (adaptador ainda não disponível; a aplicação recusa ativar).
-- * Tarifa por minuto informada por conta (somente para comparar custo estimado
--   entre provedores; a cobrança real é a fatura do provedor).
-- * Roteamento (account_route): qual conta disca por escopo — campanha,
--   carteira, credor ou padrão da assessoria —, com conta de contingência
--   (fallback) para falha transitória que comprovadamente não criou a chamada.
-- * Ramal por plataforma (agent_endpoint): o operador pode ter um ramal em cada
--   conta; ramal "fora desta plataforma" (active = false) impede que a conta
--   entregue chamadas a ele. Sem registro, vale o ramal padrão do operador.
-- * Chamada guarda a conta de origem quando discada pela contingência.
-- =============================================================================

alter table telephony.provider_account drop constraint ck_tel_account_provider;
alter table telephony.provider_account add constraint ck_tel_account_provider
    check (provider in ('SANDBOX', 'ALVARIA', 'TARIFANDO'));
drop index telephony.uk_tel_account_active;
alter table telephony.provider_account add column rate_per_minute numeric(10,4);
alter table telephony.provider_account add constraint ck_tel_account_rate
    check (rate_per_minute is null or (rate_per_minute >= 0 and rate_per_minute <= 100));
create index ix_tel_account_active on telephony.provider_account (tenant_id, provider) where active;

-- -----------------------------------------------------------------------------
-- Roteamento: uma regra por escopo. creditor_id = credor do escopo (visibilidade
-- e validação das contas restritas ao credor); nulo no padrão da assessoria e em
-- campanha de fila sem credor.
-- -----------------------------------------------------------------------------
create table telephony.account_route (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    scope_type            varchar(20)   not null,
    scope_id              uuid,
    creditor_id           uuid          references organization.creditor (id),
    primary_account_id    uuid          not null references telephony.provider_account (id),
    fallback_account_id   uuid          references telephony.provider_account (id),
    constraint ck_tel_route_scope check (scope_type in ('DEFAULT', 'CREDITOR', 'PORTFOLIO', 'CAMPAIGN')),
    constraint ck_tel_route_scope_id check ((scope_type = 'DEFAULT') = (scope_id is null)),
    constraint ck_tel_route_fallback check (fallback_account_id is null or fallback_account_id <> primary_account_id)
);
create unique index uk_tel_route_scope on telephony.account_route
    (tenant_id, scope_type, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid));
create index ix_tel_route_account on telephony.account_route (tenant_id, primary_account_id);
select shared.enable_tenant_rls('telephony.account_route');

-- -----------------------------------------------------------------------------
-- Ramal do operador por conta (plataforma).
-- -----------------------------------------------------------------------------
create table telephony.agent_endpoint (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    agent_id              uuid          not null references telephony.agent (id),
    provider_account_id   uuid          not null references telephony.provider_account (id),
    extension             varchar(64),
    active                boolean       not null,
    constraint ck_tel_endpoint_extension check (not active or extension is not null),
    constraint uk_tel_endpoint unique (tenant_id, agent_id, provider_account_id)
);
create index ix_tel_endpoint_account on telephony.agent_endpoint (tenant_id, provider_account_id, agent_id)
    where not active;
select shared.enable_tenant_rls('telephony.agent_endpoint');

alter table telephony.phone_call add column fallback_from_account_id uuid
    references telephony.provider_account (id);
create index ix_call_account on telephony.phone_call (tenant_id, provider_account_id, queued_at);

-- -----------------------------------------------------------------------------
-- Regras a partir das contas ativas existentes (antes: uma conta ativa padrão e
-- uma por credor): conta padrão → DEFAULT; conta do credor → CREDITOR.
-- -----------------------------------------------------------------------------
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into telephony.account_route (id, tenant_id, version, created_at, updated_at, scope_type, scope_id,
                                             creditor_id, primary_account_id)
        select gen_random_uuid(), a.tenant_id, 0, now(), now(),
               case when a.creditor_id is null then 'DEFAULT' else 'CREDITOR' end,
               a.creditor_id, a.creditor_id, a.id
        from telephony.provider_account a
        where a.active and a.tenant_id = t.id;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
