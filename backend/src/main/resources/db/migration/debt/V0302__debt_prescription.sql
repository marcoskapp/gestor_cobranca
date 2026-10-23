-- =============================================================================
-- Módulo debt: prescrição dos títulos (docs/design/juridico.md, A.3).
--
-- Os prazos não ficam no código: cada assessoria tem suas regras (debt.prescription_rule)
-- por natureza do título e pretensão (EXECUTION: execução do título executivo;
-- COLLECTION: cobrança/monitória). A tabela de mercado (debt.prescription_rule_template,
-- global) é o ponto de partida: é copiada para cada assessoria — as existentes aqui, as
-- novas no provisionamento — sempre como PENDING_LEGAL_REVIEW, até o jurídico validar.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Tabela de mercado (dado de referência global, sem tenant). VALIDAR COM O JURÍDICO.
-- -----------------------------------------------------------------------------
create table debt.prescription_rule_template (
    instrument_type      varchar(30)   not null,
    claim                varchar(20)   not null,
    term_months          integer       not null,
    base                 varchar(30)   not null,
    start_offset_days    integer       not null,
    interruption_policy  varchar(20)   not null,
    alert_days           integer       not null,
    legal_basis          varchar(300)  not null,
    primary key (instrument_type, claim),
    constraint ck_prescription_rule_template_term check (term_months between 1 and 360),
    constraint ck_prescription_rule_template_offset check (start_offset_days between 0 and 365),
    constraint ck_prescription_rule_template_alert check (alert_days between 1 and 730)
);

insert into debt.prescription_rule_template
    (instrument_type, claim, term_months, base, start_offset_days, interruption_policy, alert_days, legal_basis)
values
    ('CONTRACT', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CC art. 206, §5º, I (dívida líquida constante de instrumento) — validar com o jurídico'),
    ('CONTRACT', 'EXECUTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CPC art. 784, III; Súmula 150/STF (execução prescreve no prazo da ação) — validar com o jurídico'),
    ('CCB', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CC art. 206, §5º, I — validar com o jurídico'),
    ('CCB', 'EXECUTION', 36, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 120,
     'Lei 10.931/2004, art. 44, c/c LUG (Dec. 57.663/1966), art. 70 — validar com o jurídico'),
    ('PROMISSORY_NOTE', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'Súmula 504/STJ (monitória: 5 anos do dia seguinte ao vencimento) — validar com o jurídico'),
    ('PROMISSORY_NOTE', 'EXECUTION', 36, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 120,
     'LUG (Dec. 57.663/1966), arts. 70 e 77 — validar com o jurídico'),
    ('DUPLICATE', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CC art. 206, §5º, I — validar com o jurídico'),
    ('DUPLICATE', 'EXECUTION', 36, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 120,
     'Lei 5.474/1968, art. 18, I (contra o sacado) — validar com o jurídico'),
    ('CHECK', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'Súmula 503/STJ (monitória: 5 anos do dia seguinte à emissão) — validar com o jurídico'),
    ('CHECK', 'EXECUTION', 6, 'TITLE_DUE_DATE', 30, 'FIRST_ONLY', 60,
     'Lei 7.357/1985, art. 59: 6 meses após o prazo de apresentação (art. 33: 30 dias na mesma praça; '
         || 'ajustar para 60 em praça diversa) — validar com o jurídico'),
    ('DEBT_CONFESSION', 'COLLECTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CC art. 206, §5º, I — validar com o jurídico'),
    ('DEBT_CONFESSION', 'EXECUTION', 60, 'TITLE_DUE_DATE', 0, 'FIRST_ONLY', 180,
     'CPC art. 784, III; Súmula 150/STF — validar com o jurídico');

grant select on debt.prescription_rule_template to app_runtime;

-- -----------------------------------------------------------------------------
-- Regras do tenant: no máximo uma por (natureza, pretensão).
-- -----------------------------------------------------------------------------
create table debt.prescription_rule (
    id                   uuid           not null primary key,
    tenant_id            uuid           not null references organization.tenant (id),
    version              bigint         not null,
    created_at           timestamptz    not null,
    created_by           uuid,
    updated_at           timestamptz    not null,
    updated_by           uuid,
    instrument_type      varchar(30)    not null,
    claim                varchar(20)    not null,
    term_months          integer        not null,
    base                 varchar(30)    not null,
    start_offset_days    integer        not null,
    interruption_policy  varchar(20)    not null,
    alert_days           integer        not null,
    legal_basis          varchar(300),
    review_status        varchar(30)    not null,
    reviewed_at          timestamptz,
    reviewed_by          uuid,
    constraint uk_prescription_rule unique (tenant_id, instrument_type, claim),
    constraint ck_prescription_rule_instrument check (instrument_type in
        ('CONTRACT', 'CCB', 'PROMISSORY_NOTE', 'DUPLICATE', 'CHECK', 'DEBT_CONFESSION', 'OTHER')),
    constraint ck_prescription_rule_claim check (claim in ('EXECUTION', 'COLLECTION')),
    constraint ck_prescription_rule_term check (term_months between 1 and 360),
    constraint ck_prescription_rule_base check (base in ('TITLE_DUE_DATE', 'CONTRACT_FINAL_DUE_DATE')),
    constraint ck_prescription_rule_offset check (start_offset_days between 0 and 365),
    constraint ck_prescription_rule_policy check (interruption_policy in ('FIRST_ONLY', 'EVERY')),
    constraint ck_prescription_rule_alert check (alert_days between 1 and 730),
    constraint ck_prescription_rule_review check (
        (review_status = 'PENDING_LEGAL_REVIEW' and reviewed_at is null)
        or (review_status = 'VALIDATED' and reviewed_at is not null))
);
select shared.enable_tenant_rls('debt.prescription_rule');

-- -----------------------------------------------------------------------------
-- Prescrição estimada por título e pretensão (existe enquanto houver regra aplicável).
-- -----------------------------------------------------------------------------
create table debt.title_prescription (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    title_id          uuid          not null references debt.debt_title (id),
    claim             varchar(20)   not null,
    -- sem regra não há prescrição estimada: remover a regra remove as linhas calculadas por ela
    rule_id           uuid          not null references debt.prescription_rule (id) on delete cascade,
    base_date         date          not null,
    counting_start    date          not null,
    term_months       integer       not null,
    estimated_date    date          not null,
    last_alerted_for  date,
    last_alerted_at   timestamptz,
    constraint uk_title_prescription unique (tenant_id, title_id, claim),
    constraint ck_title_prescription_claim check (claim in ('EXECUTION', 'COLLECTION')),
    constraint ck_title_prescription_term check (term_months > 0),
    constraint ck_title_prescription_dates check (counting_start >= base_date and estimated_date > counting_start),
    constraint ck_title_prescription_alert check ((last_alerted_for is null) = (last_alerted_at is null))
);
-- varredura diária do alerta de prescrição próxima
create index ix_title_prescription_estimated on debt.title_prescription (tenant_id, estimated_date);
create index ix_title_prescription_rule on debt.title_prescription (tenant_id, rule_id);
select shared.enable_tenant_rls('debt.title_prescription');

-- -----------------------------------------------------------------------------
-- Interrupções (append-only): no máximo uma por (título, tipo, data).
-- -----------------------------------------------------------------------------
create table debt.prescription_interruption (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    title_id               uuid           not null references debt.debt_title (id),
    type                   varchar(20)    not null,
    interruption_date      date           not null,
    reference              varchar(200),
    notes                  varchar(500),
    origin                 varchar(20)    not null,
    origin_reference_id    uuid,
    evidence_key           varchar(512),
    evidence_name          varchar(200),
    evidence_content_type  varchar(100),
    evidence_size          bigint,
    evidence_sha256        varchar(64),
    constraint uk_prescription_interruption unique (tenant_id, title_id, type, interruption_date),
    constraint ck_prescription_interruption_type check (type in ('PROTEST', 'CITATION', 'ACKNOWLEDGEMENT', 'OTHER')),
    constraint ck_prescription_interruption_origin check (origin in ('MANUAL', 'LEGAL')),
    constraint ck_prescription_interruption_other check (type <> 'OTHER' or reference is not null or notes is not null),
    constraint ck_prescription_interruption_evidence check (
        (evidence_key is null and evidence_sha256 is null and evidence_size is null)
        or (evidence_key is not null and evidence_sha256 is not null and evidence_size is not null))
);
create index ix_prescription_interruption_title on debt.prescription_interruption (tenant_id, title_id, interruption_date);
select shared.enable_tenant_rls('debt.prescription_interruption');
create trigger trg_prescription_interruption_append_only before update or delete on debt.prescription_interruption
    for each row execute function shared.forbid_mutation();

grant select, insert, update, delete on debt.prescription_rule, debt.title_prescription,
    debt.prescription_interruption to app_runtime;
revoke update, delete on debt.prescription_interruption from app_runtime;

-- -----------------------------------------------------------------------------
-- Semeia a tabela de mercado nas assessorias existentes (RLS forçado: a cópia percorre
-- os tenants definindo o tenant da transação, como a aplicação faria). As prescrições
-- dos títulos existentes são calculadas pela reconciliação do job diário.
-- -----------------------------------------------------------------------------
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into debt.prescription_rule (id, tenant_id, version, created_at, updated_at, instrument_type, claim,
                                            term_months, base, start_offset_days, interruption_policy, alert_days,
                                            legal_basis, review_status)
        select gen_random_uuid(), t.id, 0, now(), now(), m.instrument_type, m.claim, m.term_months, m.base,
               m.start_offset_days, m.interruption_policy, m.alert_days, m.legal_basis, 'PENDING_LEGAL_REVIEW'
        from debt.prescription_rule_template m
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
