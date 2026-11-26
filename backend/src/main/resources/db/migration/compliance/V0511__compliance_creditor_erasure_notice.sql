-- =============================================================================
-- Aviso ao credor da anonimização do titular (LGPD art. 18 §6º; SEG-20261124-D01h, decisões 1468 e 1478–1483):
-- um aviso por par devedor × credor com títulos conservados, aberto na mesma transação da anonimização pelo portão
-- único (ErasureGate), e o registro de que o credor foi avisado (canal e data; sem texto livre).
--
-- Sem dado pessoal: o titular aparece só pelo id do registro anonimizado; os itens guardam a referência do título no
-- credor já mascarada (CPF/CNPJ embutido vira ***) e a parcela. Retenção: guarda indefinida, como a solicitação do
-- titular e a execução de retenção que o originam (evidência do art. 18; decisão 1483). origin_id é polimórfico (sem FK).
-- =============================================================================

create table compliance.creditor_erasure_notice (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    debtor_id         uuid          not null references debt.debtor (id),
    creditor_id       uuid          not null references organization.creditor (id),
    origin            varchar(30)   not null,
    origin_id         uuid          not null,
    erased_at         timestamptz   not null,
    status            varchar(20)   not null,
    notified_at       timestamptz,
    notified_by       uuid,
    notified_channel  varchar(20),
    registered_at     timestamptz,
    constraint uk_creditor_erasure_notice unique (tenant_id, debtor_id, creditor_id),
    constraint ck_creditor_erasure_notice_origin check (origin in ('DATA_SUBJECT_REQUEST', 'RETENTION')),
    constraint ck_creditor_erasure_notice_status check (status in ('PENDING', 'NOTIFIED')),
    constraint ck_creditor_erasure_notice_channel check (notified_channel is null
        or notified_channel in ('EMAIL', 'PHONE', 'LETTER', 'MEETING', 'OTHER')),
    constraint ck_creditor_erasure_notice_notified check (
        (status = 'PENDING' and notified_at is null and notified_by is null and notified_channel is null
            and registered_at is null)
        or (status = 'NOTIFIED' and notified_at is not null and notified_by is not null and notified_channel is not null
            and registered_at is not null and notified_at >= erased_at))
);
create index ix_creditor_erasure_notice_pending on compliance.creditor_erasure_notice (tenant_id, created_at)
    where status = 'PENDING';
create index ix_creditor_erasure_notice_creditor on compliance.creditor_erasure_notice (tenant_id, creditor_id, created_at desc);
create index ix_creditor_erasure_notice_origin on compliance.creditor_erasure_notice (tenant_id, origin_id);
select shared.enable_tenant_rls('compliance.creditor_erasure_notice');
grant select, insert on compliance.creditor_erasure_notice to app_runtime;
-- só o registro do aviso muda depois de criado (o privilégio padrão concede update na tabela inteira)
revoke update, delete on compliance.creditor_erasure_notice from app_runtime;
grant update (status, notified_at, notified_by, notified_channel, registered_at, version, updated_at, updated_by)
    on compliance.creditor_erasure_notice to app_runtime;

-- Títulos do credor que o aviso cita (append-only; isolamento pelo aviso, como promise_payment).
create table compliance.creditor_erasure_notice_item (
    notice_id           uuid          not null references compliance.creditor_erasure_notice (id),
    position            integer       not null,
    portfolio_id        uuid          not null,
    reference           varchar(1440) not null,
    installment_number  integer,
    primary key (notice_id, position),
    constraint ck_creditor_erasure_notice_item_position check (position >= 0)
);
alter table compliance.creditor_erasure_notice_item enable row level security;
alter table compliance.creditor_erasure_notice_item force row level security;
create policy tenant_isolation on compliance.creditor_erasure_notice_item
    using (exists (select 1 from compliance.creditor_erasure_notice n where n.id = notice_id))
    with check (exists (select 1 from compliance.creditor_erasure_notice n where n.id = notice_id));
grant select, insert on compliance.creditor_erasure_notice_item to app_runtime;
revoke update, delete on compliance.creditor_erasure_notice_item from app_runtime;
