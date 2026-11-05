-- =============================================================================
-- Organization: contatos do credor responsáveis por atender pedidos de contestação
-- (docs/negocio/contestacoes.md §4). Por credor e, opcionalmente, por carteira; um ou mais
-- principais (destinatários) e cópias. Contato desativado não é apagado (rastreabilidade
-- dos pedidos já enviados, que guardam os destinatários usados).
-- =============================================================================
create table organization.creditor_dispute_contact (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    creditor_id   uuid          not null references organization.creditor (id),
    portfolio_id  uuid          references organization.portfolio (id),
    name          varchar(150)  not null,
    email         varchar(200)  not null,
    phone         varchar(30),
    role          varchar(20)   not null,
    active        boolean       not null,
    constraint ck_creditor_dispute_contact_role check (role in ('PRIMARY', 'COPY')),
    constraint ck_creditor_dispute_contact_email check (position('@' in email) > 1)
);
create unique index uk_creditor_dispute_contact on organization.creditor_dispute_contact
    (tenant_id, creditor_id, coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(email))
    where active;
create index ix_creditor_dispute_contact on organization.creditor_dispute_contact (tenant_id, creditor_id, active);
select shared.enable_tenant_rls('organization.creditor_dispute_contact');

grant select, insert, update on organization.creditor_dispute_contact to app_runtime;
