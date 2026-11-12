-- =============================================================================
-- Cobrança B2B (docs/tecnico/desenho/cobranca-b2b.md §3 a §5):
--   * B1 — cadastro da empresa: nome fantasia (criptografado) e raiz do CNPJ (blind index
--     próprio, finalidade debtor.document_root) para agrupar matriz e filiais;
--   * B2 — pessoa de contato: nome (criptografado), papel e indicação de quem decide;
--   * B3 — representantes da empresa (sócio administrador, procurador): entidade própria,
--     fora de debt.debtor (o representante não responde pela dívida).
-- =============================================================================

alter table debt.debtor
    add column trade_name_encrypted varchar(1024),
    add column document_root_hash   varchar(64);
alter table debt.debtor
    add constraint ck_debtor_company_profile check (
        (person_type = 'COMPANY' or (trade_name_encrypted is null and document_root_hash is null))
        and (status = 'ACTIVE' or (trade_name_encrypted is null and document_root_hash is null)));
-- "Outras unidades da empresa": devedores com a mesma raiz do CNPJ
create index ix_debtor_document_root on debt.debtor (tenant_id, document_root_hash)
    where document_root_hash is not null;

alter table debt.debtor_contact
    add column person_name_encrypted varchar(1024),
    add column person_role           varchar(20),
    add column decision_maker        boolean not null default false;
alter table debt.debtor_contact
    add constraint ck_debtor_contact_person_role check (person_role is null
        or person_role in ('FINANCE', 'ACCOUNTS_PAYABLE', 'OWNER_PARTNER', 'LEGAL', 'OTHER'));

-- -----------------------------------------------------------------------------
-- Representante da empresa devedora: quem pode negociar em nome dela. Sempre pessoa
-- física (CPF); document_hash = blind index do CPF com finalidade própria
-- (debt.representative.document). Encerrado, nunca removido — exceto na anonimização da
-- empresa (LGPD: a retenção do representante segue a do cadastro da empresa).
-- -----------------------------------------------------------------------------
create table debt.company_representative (
    id                  uuid           not null primary key,
    tenant_id           uuid           not null references organization.tenant (id),
    version             bigint         not null,
    created_at          timestamptz    not null,
    created_by          uuid,
    updated_at          timestamptz    not null,
    updated_by          uuid,
    company_debtor_id   uuid           not null references debt.debtor (id),
    document_encrypted  varchar(512)   not null,
    document_hash       varchar(64)    not null,
    name_encrypted      varchar(1024)  not null,
    role                varchar(20)    not null,
    can_negotiate       boolean        not null,
    valid_until         date,
    evidence_note       varchar(200),
    source              varchar(20)    not null,
    ended_at            timestamptz,
    end_reason          varchar(300),
    constraint ck_company_representative_role check (role in ('MANAGING_PARTNER', 'ATTORNEY_IN_FACT', 'OTHER')),
    constraint ck_company_representative_source check (source in ('MANUAL', 'IMPORT')),
    constraint ck_company_representative_end check ((ended_at is null) = (end_reason is null))
);
-- único por (empresa, CPF) enquanto vigente
create unique index uk_company_representative_active
    on debt.company_representative (tenant_id, company_debtor_id, document_hash) where ended_at is null;
create index ix_company_representative_company on debt.company_representative (tenant_id, company_debtor_id);
-- solicitação de titular do representante (pelo CPF)
create index ix_company_representative_document on debt.company_representative (tenant_id, document_hash);
select shared.enable_tenant_rls('debt.company_representative');

grant select, insert, update, delete on debt.company_representative to app_runtime;
