-- =============================================================================
-- Módulo debt: natureza do título no contrato de origem, garantias (com avaliações
-- append-only e vínculo opcional a títulos) e partes da obrigação (coobrigados).
-- Desenho: docs/design/juridico.md, Parte A (A.1, A.2, A.3).
--
-- A identificação do bem (placa, RENAVAM, chassi; matrícula, cartório, município/UF)
-- não contém dados de pessoa física: o proprietário/terceiro garantidor é uma parte
-- da obrigação (debt.obligation_party → debt.debtor, com dados criptografados).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Natureza do instrumento. Contratos existentes assumem CONTRACT, não executivo,
-- até serem reclassificados (pela carga ou manualmente).
-- -----------------------------------------------------------------------------
alter table debt.origin_contract
    add column instrument_type varchar(30) not null default 'CONTRACT',
    add column executive_title boolean     not null default false;
alter table debt.origin_contract
    alter column instrument_type drop default,
    alter column executive_title drop default,
    add constraint ck_origin_contract_instrument_type check (instrument_type in
        ('CONTRACT', 'CCB', 'PROMISSORY_NOTE', 'DUPLICATE', 'CHECK', 'DEBT_CONFESSION', 'OTHER'));
-- recálculo de prescrição em lote por natureza (cursor por id)
create index ix_origin_contract_instrument on debt.origin_contract (tenant_id, instrument_type, id);

-- -----------------------------------------------------------------------------
-- Garantia do contrato de origem. estimated_value/last_appraisal_date são cache da
-- avaliação mais recente (debt.collateral_appraisal), mantidos na mesma transação.
-- -----------------------------------------------------------------------------
create table debt.collateral (
    id                            uuid            not null primary key,
    tenant_id                     uuid            not null references organization.tenant (id),
    version                       bigint          not null,
    created_at                    timestamptz     not null,
    created_by                    uuid,
    updated_at                    timestamptz     not null,
    updated_by                    uuid,
    creditor_id                   uuid            not null references organization.creditor (id),
    origin_contract_id            uuid            not null references debt.origin_contract (id),
    external_id                   varchar(80),
    type                          varchar(40)     not null,
    description                   varchar(1000),
    asset_kind                    varchar(20)     not null,
    vehicle_plate                 varchar(7),
    vehicle_renavam               varchar(11),
    vehicle_chassis               varchar(17),
    vehicle_manufacture_year      integer,
    vehicle_model_year            integer,
    real_estate_registry_number   varchar(30),
    real_estate_registry_office   varchar(150),
    real_estate_municipality      varchar(100),
    real_estate_state             varchar(2),
    registration_authority        varchar(150),
    registration_number           varchar(60),
    registration_date             date,
    status                        varchar(20)     not null,
    status_reason                 varchar(500),
    status_changed_at             timestamptz,
    estimated_value               numeric(19, 2),
    last_appraisal_date           date,
    constraint uk_collateral_external unique (tenant_id, origin_contract_id, external_id),
    constraint ck_collateral_type check (type in ('VEHICLE_FIDUCIARY_ALIENATION', 'REAL_ESTATE_FIDUCIARY_ALIENATION',
        'MORTGAGE', 'PLEDGE', 'RECEIVABLES_FIDUCIARY_ASSIGNMENT', 'SURETY_INSURANCE', 'OTHER')),
    constraint ck_collateral_asset_kind check (asset_kind in ('VEHICLE', 'REAL_ESTATE', 'OTHER')),
    -- natureza do bem admitida por modalidade (CollateralType#allowedAssetKinds)
    constraint ck_collateral_asset_by_type check (
        (type = 'VEHICLE_FIDUCIARY_ALIENATION' and asset_kind = 'VEHICLE')
        or (type in ('REAL_ESTATE_FIDUCIARY_ALIENATION', 'MORTGAGE') and asset_kind = 'REAL_ESTATE')
        or (type = 'PLEDGE' and asset_kind in ('VEHICLE', 'OTHER'))
        or (type in ('RECEIVABLES_FIDUCIARY_ASSIGNMENT', 'SURETY_INSURANCE') and asset_kind = 'OTHER')
        or type = 'OTHER'),
    constraint ck_collateral_vehicle check (
        (asset_kind = 'VEHICLE' and (vehicle_plate is not null or vehicle_renavam is not null or vehicle_chassis is not null))
        or (asset_kind <> 'VEHICLE' and vehicle_plate is null and vehicle_renavam is null and vehicle_chassis is null
            and vehicle_manufacture_year is null and vehicle_model_year is null)),
    constraint ck_collateral_vehicle_plate check (vehicle_plate is null or vehicle_plate ~ '^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$'),
    constraint ck_collateral_vehicle_renavam check (vehicle_renavam is null or vehicle_renavam ~ '^[0-9]{11}$'),
    constraint ck_collateral_vehicle_chassis check (vehicle_chassis is null or vehicle_chassis ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    constraint ck_collateral_real_estate check (
        (asset_kind = 'REAL_ESTATE' and real_estate_registry_number is not null and real_estate_registry_office is not null
            and real_estate_municipality is not null and real_estate_state is not null)
        or (asset_kind <> 'REAL_ESTATE' and real_estate_registry_number is null and real_estate_registry_office is null
            and real_estate_municipality is null and real_estate_state is null)),
    constraint ck_collateral_other_description check (asset_kind <> 'OTHER' or description is not null),
    constraint ck_collateral_status check (status in ('ACTIVE', 'UNDER_ENFORCEMENT', 'SEIZED', 'CONSOLIDATED',
                                                      'AUCTIONED', 'RELEASED', 'WRITTEN_OFF')),
    constraint ck_collateral_status_reason check (
        (status_changed_at is null and status_reason is null)
        or (status_changed_at is not null and status_reason is not null)),
    constraint ck_collateral_estimate check (
        (estimated_value is null and last_appraisal_date is null)
        or (estimated_value >= 0 and last_appraisal_date is not null))
);
create index ix_collateral_contract on debt.collateral (tenant_id, origin_contract_id);
create index ix_collateral_creditor_status on debt.collateral (tenant_id, creditor_id, status);
-- o mesmo bem não é cadastrado duas vezes no mesmo contrato (findSameAsset)
create unique index uk_collateral_vehicle_chassis on debt.collateral (tenant_id, origin_contract_id, vehicle_chassis)
    where vehicle_chassis is not null;
create unique index uk_collateral_vehicle_renavam on debt.collateral (tenant_id, origin_contract_id, vehicle_renavam)
    where vehicle_renavam is not null;
create unique index uk_collateral_vehicle_plate on debt.collateral (tenant_id, origin_contract_id, vehicle_plate)
    where vehicle_plate is not null;
create unique index uk_collateral_real_estate on debt.collateral (tenant_id, origin_contract_id,
    real_estate_registry_number, lower(real_estate_registry_office), lower(real_estate_municipality), real_estate_state)
    where asset_kind = 'REAL_ESTATE';
select shared.enable_tenant_rls('debt.collateral');

-- -----------------------------------------------------------------------------
-- Títulos cobertos quando a garantia é por parcela (sem linhas = todos os títulos
-- do contrato). RLS pela linha pai.
-- -----------------------------------------------------------------------------
create table debt.collateral_title (
    collateral_id  uuid  not null references debt.collateral (id),
    title_id       uuid  not null references debt.debt_title (id),
    primary key (collateral_id, title_id)
);
create index ix_collateral_title_title on debt.collateral_title (title_id);
alter table debt.collateral_title enable row level security;
alter table debt.collateral_title force row level security;
create policy tenant_isolation on debt.collateral_title
    using (exists (select 1 from debt.collateral c where c.id = collateral_id))
    with check (exists (select 1 from debt.collateral c where c.id = collateral_id));

-- -----------------------------------------------------------------------------
-- Avaliações da garantia (append-only). Uma avaliação por lote de carga.
-- -----------------------------------------------------------------------------
create table debt.collateral_appraisal (
    id               uuid            not null primary key,
    tenant_id        uuid            not null references organization.tenant (id),
    version          bigint          not null,
    created_at       timestamptz     not null,
    created_by       uuid,
    updated_at       timestamptz     not null,
    updated_by       uuid,
    collateral_id    uuid            not null references debt.collateral (id),
    amount           numeric(19, 2)  not null,
    appraisal_date   date            not null,
    source           varchar(20)     not null,
    notes            varchar(500),
    import_batch_id  uuid,
    constraint ck_collateral_appraisal_amount check (amount >= 0),
    constraint ck_collateral_appraisal_source check (source in ('CREDITOR', 'APPRAISER', 'FIPE', 'MARKET', 'AUCTION'))
);
create index ix_collateral_appraisal_collateral on debt.collateral_appraisal (tenant_id, collateral_id, appraisal_date desc);
create unique index uk_collateral_appraisal_batch on debt.collateral_appraisal (tenant_id, collateral_id, import_batch_id)
    where import_batch_id is not null;
select shared.enable_tenant_rls('debt.collateral_appraisal');
create trigger trg_collateral_appraisal_append_only before update or delete on debt.collateral_appraisal
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Partes da obrigação: coobrigados do contrato (o devedor principal é o do contrato).
-- A pessoa é o cadastro de devedor consolidado pelo documento (RB01).
-- -----------------------------------------------------------------------------
create table debt.obligation_party (
    id                  uuid          not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    version             bigint        not null,
    created_at          timestamptz   not null,
    created_by          uuid,
    updated_at          timestamptz   not null,
    updated_by          uuid,
    creditor_id         uuid          not null references organization.creditor (id),
    origin_contract_id  uuid          not null references debt.origin_contract (id),
    debtor_id           uuid          not null references debt.debtor (id),
    role                varchar(40)   not null,
    since_date          date,
    ended_on            date,
    end_reason          varchar(500),
    constraint uk_obligation_party unique (tenant_id, origin_contract_id, debtor_id, role),
    constraint ck_obligation_party_role check (role in ('GUARANTOR_AVAL', 'GUARANTOR_SURETY', 'CO_DEBTOR',
                                                        'THIRD_PARTY_COLLATERAL_PROVIDER')),
    constraint ck_obligation_party_end check (
        (ended_on is null and end_reason is null) or (ended_on is not null and end_reason is not null)),
    constraint ck_obligation_party_period check (ended_on is null or since_date is null or ended_on >= since_date)
);
create index ix_obligation_party_contract on debt.obligation_party (tenant_id, origin_contract_id);
-- visibilidade e ficha do coobrigado
create index ix_obligation_party_debtor_active on debt.obligation_party (tenant_id, debtor_id) where ended_on is null;
select shared.enable_tenant_rls('debt.obligation_party');

grant select, insert, update, delete on debt.collateral, debt.collateral_title, debt.collateral_appraisal,
    debt.obligation_party to app_runtime;
-- avaliações são imutáveis; garantias e participações não são removidas (encerramento/baixa por situação)
revoke update, delete on debt.collateral_appraisal from app_runtime;
revoke delete on debt.collateral, debt.obligation_party from app_runtime;
