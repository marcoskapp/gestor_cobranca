-- =============================================================================
-- Pix na conversa pelo assistente (ADR-0009; docs/tecnico/desenho/assistente.md §5.1).
-- Um registro por chamada da ferramenta generate_payment: critérios avaliados no
-- servidor (imutáveis), decisão (ISSUED/DENIED/SUGGESTED), instrução de pagamento
-- gerada no payment, entrega do "copia e cola" ao devedor e pagamento (conversão
-- Pix emitido → pago). Nunca guarda o código Pix nem dados pessoais.
-- =============================================================================
create table assistant.pix_request (
    id                   uuid           not null primary key,
    tenant_id            uuid           not null references organization.tenant (id),
    version              bigint         not null,
    created_at           timestamptz    not null,
    created_by           uuid,
    updated_at           timestamptz    not null,
    updated_by           uuid,
    turn_id              uuid           not null references assistant.assistant_turn (id),
    session_id           uuid           not null references assistant.assistant_session (id),
    conversation_id      uuid           not null references communication.conversation (id),
    creditor_id          uuid           not null references organization.creditor (id),
    portfolio_id         uuid           references organization.portfolio (id),
    debtor_id            uuid           not null references debt.debtor (id),
    case_id              uuid,
    policy_id            uuid           references assistant.assistant_policy (id),
    mode                 varchar(30)    not null,
    decision             varchar(20)    not null,
    denial_code          varchar(40),
    criteria             jsonb          not null,
    agreement_id         uuid,
    installment_id       uuid,
    installment_number   integer,
    installment_kind     varchar(20),
    amount               numeric(19, 2),
    instruction_id       uuid           references payment.payment_instruction (id),
    reused_instruction   boolean        not null,
    expires_at           timestamptz,
    requested_at         timestamptz    not null,
    delivery_status      varchar(20),
    delivery_code        varchar(60),
    delivery_message_id  uuid,
    delivered_at         timestamptz,
    paid_at              timestamptz,
    constraint ck_pix_request_decision check (decision in ('ISSUED', 'DENIED', 'SUGGESTED')),
    constraint ck_pix_request_mode check (mode in ('OFF', 'SHADOW', 'COPILOT', 'AUTONOMOUS_WITH_APPROVAL', 'AUTONOMOUS')),
    constraint ck_pix_request_denial check ((decision = 'DENIED') = (denial_code is not null)),
    constraint ck_pix_request_issued check ((decision = 'ISSUED') = (instruction_id is not null
                                            and delivery_status is not null and amount is not null)),
    constraint ck_pix_request_delivery check (delivery_status is null or delivery_status in (
        'AWAITING', 'DELIVERED', 'CANCELLED', 'NOT_DELIVERED')),
    constraint ck_pix_request_delivered check ((delivery_status = 'DELIVERED') = (delivered_at is not null)),
    constraint ck_pix_request_kind check (installment_kind is null or installment_kind in (
        'SINGLE', 'DOWN_PAYMENT', 'INSTALLMENT')),
    constraint ck_pix_request_amount check (amount is null or amount > 0)
);
create index ix_pix_request_conversation on assistant.pix_request (tenant_id, conversation_id, requested_at);
create index ix_pix_request_instruction on assistant.pix_request (tenant_id, instruction_id) where instruction_id is not null;
create index ix_pix_request_turn on assistant.pix_request (tenant_id, turn_id);
create index ix_pix_request_requested on assistant.pix_request (tenant_id, requested_at);
select shared.enable_tenant_rls('assistant.pix_request');

-- a avaliação é imutável: só a entrega e o pagamento mudam; nada é removido
create or replace function assistant.pix_request_evaluation_immutable() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Pedido de Pix do assistente não pode ser removido' using errcode = 'P0001';
    end if;
    if (to_jsonb(new) - 'delivery_status' - 'delivery_code' - 'delivery_message_id' - 'delivered_at' - 'paid_at'
            - 'version' - 'updated_at' - 'updated_by')
       is distinct from
       (to_jsonb(old) - 'delivery_status' - 'delivery_code' - 'delivery_message_id' - 'delivered_at' - 'paid_at'
            - 'version' - 'updated_at' - 'updated_by') then
        raise exception 'Avaliação do Pix do assistente é imutável' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_pix_request_immutable before update or delete on assistant.pix_request
    for each row execute function assistant.pix_request_evaluation_immutable();

grant select, insert, update on assistant.pix_request to app_runtime;
revoke delete on assistant.pix_request from app_runtime;
