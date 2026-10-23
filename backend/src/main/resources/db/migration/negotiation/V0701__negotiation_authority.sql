-- =============================================================================
-- negotiation: perfis de alçada (humanos e assistente) e atribuição a usuários e
-- a políticas do assistente, geral ou por credor (§4 do desenho).
-- =============================================================================

create table negotiation.authority_profile (
    id                      uuid           not null primary key,
    tenant_id               uuid           not null references organization.tenant (id),
    version                 bigint         not null,
    created_at              timestamptz    not null,
    created_by              uuid,
    updated_at              timestamptz    not null,
    updated_by              uuid,
    code                    varchar(40)    not null,
    name                    varchar(120)   not null,
    description             varchar(500),
    system_profile          boolean        not null,
    max_concession_level    integer        not null,
    max_discount_share      numeric(9, 4)  not null,
    max_installments        integer        not null,
    max_agreement_amount    numeric(19, 2),
    min_down_payment_share  numeric(9, 4)  not null,
    can_formalize           boolean        not null,
    can_approve             boolean        not null,
    max_renegotiations      integer        not null,
    constraint uk_authority_profile_code unique (tenant_id, code),
    constraint ck_authority_profile_code check (code ~ '^[A-Z0-9_]{2,40}$'),
    constraint ck_authority_profile_level check (max_concession_level between 0 and 99),
    constraint ck_authority_profile_share check (max_discount_share between 0 and 100
                                                 and min_down_payment_share >= 0 and min_down_payment_share < 100),
    constraint ck_authority_profile_installments check (max_installments between 1 and 360),
    constraint ck_authority_profile_amount check (max_agreement_amount is null or max_agreement_amount > 0),
    constraint ck_authority_profile_renegotiations check (max_renegotiations between 0 and 10),
    constraint ck_authority_profile_approve check (not can_approve or can_formalize)
);
select shared.enable_tenant_rls('negotiation.authority_profile');
create trigger trg_authority_profile_code_immutable before update on negotiation.authority_profile
    for each row when (new.code <> old.code or new.system_profile <> old.system_profile)
    execute function shared.forbid_mutation();

-- credores aos quais o perfil se aplica (nenhuma linha = todos); RLS pela linha pai
create table negotiation.authority_profile_creditor (
    profile_id   uuid  not null references negotiation.authority_profile (id),
    creditor_id  uuid  not null references organization.creditor (id),
    primary key (profile_id, creditor_id)
);
alter table negotiation.authority_profile_creditor enable row level security;
alter table negotiation.authority_profile_creditor force row level security;
create policy tenant_isolation on negotiation.authority_profile_creditor
    using (exists (select 1 from negotiation.authority_profile p where p.id = profile_id))
    with check (exists (select 1 from negotiation.authority_profile p where p.id = profile_id));

create table negotiation.authority_assignment (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    subject_type  varchar(20)   not null,
    subject_id    uuid          not null,
    creditor_id   uuid          references organization.creditor (id),
    profile_id    uuid          not null references negotiation.authority_profile (id),
    constraint ck_authority_assignment_subject check (subject_type in ('USER', 'ASSISTANT_POLICY'))
);
-- uma atribuição por sujeito e credor; a atribuição geral (sem credor) também é única
create unique index uk_authority_assignment_creditor on negotiation.authority_assignment
    (tenant_id, subject_type, subject_id, creditor_id) where creditor_id is not null;
create unique index uk_authority_assignment_all on negotiation.authority_assignment
    (tenant_id, subject_type, subject_id) where creditor_id is null;
create index ix_authority_assignment_profile on negotiation.authority_assignment (tenant_id, profile_id);
select shared.enable_tenant_rls('negotiation.authority_assignment');

grant select, insert, update, delete on negotiation.authority_profile, negotiation.authority_profile_creditor,
    negotiation.authority_assignment to app_runtime;
-- perfis não são apagados (atribuições e simulações os referenciam)
revoke delete on negotiation.authority_profile from app_runtime;
