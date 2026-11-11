-- =============================================================================
-- Compliance: cadastro de operadores e suboperadores (LGPD art. 37 e 39; art. 33 para
-- transferência internacional) — docs/tecnico/desenho/conformidade.md §8.
--
-- A chave do provedor é a mesma das fontes de compartilhamento (shared.privacy), ex.:
-- communication.whatsapp_cloud, telephony.twilio, payment.pix_account:<id>, assistant.bedrock.
-- Só dados de empresa e contrato (texto curto, sem anexos); nenhum dado de devedor.
-- =============================================================================

create table compliance.data_processor (
    id                  uuid          not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    version             bigint        not null,
    created_at          timestamptz   not null,
    created_by          uuid,
    updated_at          timestamptz   not null,
    updated_by          uuid,
    provider_key        varchar(80)   not null,
    name                varchar(150)  not null,
    role                varchar(20)   not null,
    purpose             varchar(300)  not null,
    data_categories     varchar(300)  not null,
    country_code        varchar(2)    not null,
    transfer_safeguard  varchar(40),
    contract_reference  varchar(120),
    valid_from          date          not null,
    valid_until         date,
    active              boolean       not null,
    constraint uk_data_processor_key unique (tenant_id, provider_key),
    constraint ck_data_processor_key check (provider_key ~ '^[a-z]+\.[a-z_]+(:[0-9a-f-]{36})?$'),
    constraint ck_data_processor_role check (role in ('OPERATOR', 'SUB_OPERATOR', 'JOINT_CONTROLLER')),
    constraint ck_data_processor_country check (country_code ~ '^[A-Z]{2}$'),
    constraint ck_data_processor_safeguard check (transfer_safeguard is null or transfer_safeguard in (
        'ADEQUACY_DECISION', 'SPECIFIC_CONTRACTUAL_CLAUSES', 'STANDARD_CONTRACTUAL_CLAUSES', 'GLOBAL_CORPORATE_RULES',
        'SEALS_AND_CERTIFICATES', 'ANPD_AUTHORIZATION', 'SPECIFIC_CONSENT', 'LEGAL_NECESSITY')),
    -- fora do Brasil a garantia do art. 33 é obrigatória; no Brasil não se aplica
    constraint ck_data_processor_transfer check ((country_code = 'BR') = (transfer_safeguard is null)),
    constraint ck_data_processor_period check (valid_until is null or valid_until >= valid_from)
);
select shared.enable_tenant_rls('compliance.data_processor');
grant select, insert, update on compliance.data_processor to app_runtime;
