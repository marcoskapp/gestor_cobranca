-- Reconhecimento do aviso de privacidade pelo titular (LGPD art. 9º), registrado pelo compliance a
-- partir do evento PortalPrivacyAcknowledged do portal de autonegociação, com a base legal vigente
-- do credor/carteira no instante (nula se não houver registro — pendência visível à governança).
-- Versão na faixa do portal (V1300–V1399), pasta do schema dono. Append-only.
create table compliance.privacy_notice_acknowledgment (
    id                      uuid          not null primary key,
    tenant_id               uuid          not null references organization.tenant (id),
    version                 bigint        not null,
    created_at              timestamptz   not null,
    created_by              uuid,
    updated_at              timestamptz   not null,
    updated_by              uuid,
    debtor_id               uuid          not null,
    creditor_id             uuid          not null references organization.creditor (id),
    channel                 varchar(20)   not null,
    notice_version          integer       not null,
    notice_sha256           varchar(64)   not null,
    reference_id            uuid,
    acknowledged_at         timestamptz   not null,
    legal_basis_record_id   uuid          references compliance.legal_basis_record (id),
    legal_basis             varchar(40),
    source_event_id         uuid          not null,
    constraint uk_privacy_ack_event unique (tenant_id, source_event_id),
    constraint ck_privacy_ack_channel check (channel in ('PORTAL')),
    constraint ck_privacy_ack_version check (notice_version >= 1)
);
create index ix_privacy_ack_debtor on compliance.privacy_notice_acknowledgment (tenant_id, debtor_id, acknowledged_at desc);
select shared.enable_tenant_rls('compliance.privacy_notice_acknowledgment');
grant select, insert on compliance.privacy_notice_acknowledgment to app_runtime;
revoke update, delete on compliance.privacy_notice_acknowledgment from app_runtime;
