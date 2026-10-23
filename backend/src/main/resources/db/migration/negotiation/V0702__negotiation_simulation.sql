-- =============================================================================
-- negotiation: simulações registradas (append-only) com a memória de cálculo
-- completa — composição por título, envelope, ofertas e cronogramas — para
-- reprodução (RB03) e referência pelas propostas da parte 2.
-- =============================================================================

create table negotiation.simulation (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    creditor_id             uuid           not null references organization.creditor (id),
    debtor_id               uuid           not null references debt.debtor (id),
    portfolio_id            uuid           references organization.portfolio (id),
    policy_id               uuid           not null references negotiation.collection_policy (id),
    policy_version          integer        not null,
    override_code           varchar(40),
    reference_date          date           not null,
    actor_type              varchar(20)    not null,
    actor_id                uuid           not null,
    authority_profile_id    uuid           not null references negotiation.authority_profile (id),
    authority_profile_code  varchar(40)    not null,
    requested_level         integer        not null,
    granted_level           integer        not null,
    justification_reason    varchar(30),
    justification_note      varchar(500),
    title_count             integer        not null,
    max_days_overdue        bigint         not null,
    updated_amount          numeric(19, 2) not null,
    best_offer_amount       numeric(19, 2),
    channel                 varchar(20),
    calculation             jsonb          not null,
    engine_version          integer        not null,
    constraint ck_simulation_actor check (actor_type in ('USER', 'ASSISTANT')),
    constraint ck_simulation_levels check (requested_level >= 0 and granted_level >= 0
                                           and granted_level <= requested_level),
    constraint ck_simulation_justification check (requested_level = 0 or justification_reason is not null),
    constraint ck_simulation_reason check (justification_reason is null or justification_reason in (
        'PAYMENT_CAPACITY', 'INCOME_LOSS', 'HEALTH_OR_FAMILY', 'COMPETING_OFFER', 'LOW_RECOVERY_PROSPECT',
        'IMMEDIATE_PAYMENT', 'OTHER')),
    constraint ck_simulation_amounts check (updated_amount >= 0 and title_count > 0)
);
create index ix_simulation_debtor on negotiation.simulation (tenant_id, debtor_id, created_at desc);
create index ix_simulation_actor on negotiation.simulation (tenant_id, actor_type, actor_id, created_at desc);
select shared.enable_tenant_rls('negotiation.simulation');
create trigger trg_simulation_append_only before update or delete on negotiation.simulation
    for each row execute function shared.forbid_mutation();

grant select, insert on negotiation.simulation to app_runtime;
revoke update, delete on negotiation.simulation from app_runtime;
