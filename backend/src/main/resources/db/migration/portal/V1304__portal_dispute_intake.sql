-- =============================================================================
-- Portal: contestação aberta pelo devedor vira contestação no collection (I14 da
-- revisão de produto de 11/2026). O pedido DISPUTE guarda o motivo escolhido; o
-- resultado do registro no collection (job portal.dispute_intake) fica numa linha
-- própria, para não concorrer com o job do atendimento humano pelo pedido.
-- =============================================================================

alter table portal.service_request
    add column dispute_type varchar(20);
alter table portal.service_request
    add constraint ck_service_request_dispute_type check (dispute_type is null
        or (reason = 'DISPUTE' and dispute_type in ('NOT_RECOGNIZED', 'ALREADY_PAID', 'WRONG_AMOUNT', 'OTHER')));

create table portal.service_request_dispute (
    id               uuid          not null primary key,
    tenant_id        uuid          not null references organization.tenant (id),
    version          bigint        not null,
    created_at       timestamptz   not null,
    created_by       uuid,
    updated_at       timestamptz   not null,
    updated_by       uuid,
    request_id       uuid          not null references portal.service_request (id),
    status           varchar(15)   not null,
    registered_at    timestamptz,
    disputes_opened  integer,
    constraint uk_service_request_dispute unique (tenant_id, request_id),
    constraint ck_service_request_dispute_status check (status in ('PENDING', 'OPENED', 'ALREADY_OPEN', 'NO_CASE',
                                                                    'NO_TITLES')),
    constraint ck_service_request_dispute_done check ((status = 'PENDING') = (registered_at is null)),
    constraint ck_service_request_dispute_opened check (disputes_opened is null or disputes_opened >= 0)
);
select shared.enable_tenant_rls('portal.service_request_dispute');
grant select, insert, update on portal.service_request_dispute to app_runtime;
