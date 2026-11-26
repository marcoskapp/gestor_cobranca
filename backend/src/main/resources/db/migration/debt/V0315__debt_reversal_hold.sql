-- =============================================================================
-- H07 parte 3: retenção da cobrança após o estorno de um pagamento recebido pelo credor ("Estorno em conferência").
--
-- Decisões do responsável (25/11 e 26/11/2026): o estorno de baixa informada ou recebida pelo credor não retoma a
-- cobrança sozinho. Enquanto houver retenção PENDING, nenhum contato de cobrança desse credor com o devedor (porteiro
-- do compliance, todos os canais), nenhuma oferta, acordo ou instrução para os títulos e parcelas afetados, e o portal
-- não mostra os títulos. Só uma pessoa com PAYMENT_REVIEW, que não estornou nem informou o pagamento, libera, com
-- justificativa e confirmação de que conferiu com o credor.
--
-- Um registro por estorno (empilha): o par devedor–credor fica retido enquanto houver ao menos um PENDING. A retenção é
-- independente do status do título (suspender, reativar, carga ou nova quitação não a encerram); só a liberação humana
-- (RELEASED) e a anonimização/eliminação do titular (ENDED) a encerram.
--
-- Criada pelo payment na mesma transação do estorno (falha fechada): erro aqui desfaz o estorno inteiro.
-- Nome reversal_hold para não colidir com legal_matter.collection_hold / CollectionHold do jurídico (decisão 1644).
--
-- Expand: só tabelas novas; nenhuma versão anterior da aplicação as lê ou grava.
-- =============================================================================
create table debt.reversal_hold (
    id                  uuid          not null primary key,
    tenant_id           uuid          not null references organization.tenant (id),
    version             bigint        not null,
    created_at          timestamptz   not null,
    created_by          uuid,
    updated_at          timestamptz   not null,
    updated_by          uuid,
    creditor_id         uuid          not null references organization.creditor (id),
    debtor_id           uuid          not null references debt.debtor (id),
    -- pagamento e estorno no payment (sem chave estrangeira entre schemas)
    payment_id          uuid          not null,
    reversal_id         uuid          not null,
    reversal_reason     varchar(30)   not null,
    state               varchar(10)   not null,
    placed_at           timestamptz   not null,
    -- quem estornou: usuário da assessoria ou o sistema (devolução informada pelo PSP)
    placed_by           uuid          not null,
    placed_by_type      varchar(20)   not null,
    -- quem informou o pagamento original, só quando foi um usuário interno (operador); nulo para integração e portal
    informed_by         uuid,
    released_at         timestamptz,
    released_by         uuid,
    release_reason_code varchar(40),
    release_note        varchar(500),
    ended_at            timestamptz,
    ended_reason        varchar(30),
    constraint uk_reversal_hold_reversal unique (tenant_id, reversal_id),
    constraint ck_reversal_hold_state check (state in ('PENDING', 'RELEASED', 'ENDED')),
    constraint ck_reversal_hold_placed_by_type check (placed_by_type in ('USER', 'SYSTEM')),
    constraint ck_reversal_hold_release_reason check (release_reason_code is null
        or release_reason_code in ('CREDITOR_CONFIRMED_NOT_RECEIVED', 'PAYMENT_ON_WRONG_TITLE', 'OTHER')),
    constraint ck_reversal_hold_ended_reason check (ended_reason is null or ended_reason in ('DEBTOR_ANONYMIZED')),
    -- liberada: quem, quando e o motivo; a justificativa pode ter sido limpa na anonimização do titular
    constraint ck_reversal_hold_released check ((state = 'RELEASED') = (released_at is not null)
        and (released_at is null) = (released_by is null)
        and (released_at is null) = (release_reason_code is null)),
    -- encerrada sem liberação: bicondicional com a situação e motivo coerente (AQ-07)
    constraint ck_reversal_hold_ended check ((state = 'ENDED') = (ended_at is not null)
        and (ended_at is null) = (ended_reason is null)),
    -- segregação também no banco: quem libera nunca é quem estornou nem quem informou
    constraint ck_reversal_hold_segregation check (released_by is null
        or (released_by <> placed_by and released_by is distinct from informed_by))
);
-- porteiro, elegibilidade e portal: o par em retenção (poucas linhas PENDING)
create index ix_reversal_hold_pending on debt.reversal_hold (tenant_id, creditor_id, debtor_id)
    where state = 'PENDING';
-- aba "Estornos em conferência": escopo por credor e período
create index ix_reversal_hold_creditor on debt.reversal_hold (tenant_id, creditor_id, placed_at desc, id);
create index ix_reversal_hold_payment on debt.reversal_hold (tenant_id, payment_id);
create index ix_reversal_hold_debtor on debt.reversal_hold (tenant_id, debtor_id);
select shared.enable_tenant_rls('debt.reversal_hold');

-- títulos, acordos, parcelas e alocações afetados pelo estorno (imutáveis). Um pagamento pode ter alocações em mais de
-- um acordo (sobra realocada para outro acordo): cada acordo é um item AGREEMENT (decisão 1678). O índice por
-- (kind, item_id) atende a quebra e a instrução por acordo (AQ-03).
create table debt.reversal_hold_item (
    hold_id  uuid         not null references debt.reversal_hold (id),
    kind     varchar(20)  not null,
    item_id  uuid         not null,
    primary key (hold_id, kind, item_id),
    constraint ck_reversal_hold_item_kind check (kind in ('TITLE', 'AGREEMENT', 'INSTALLMENT', 'ALLOCATION'))
);
create index ix_reversal_hold_item on debt.reversal_hold_item (kind, item_id);
alter table debt.reversal_hold_item enable row level security;
alter table debt.reversal_hold_item force row level security;
create policy tenant_isolation on debt.reversal_hold_item
    using (exists (select 1 from debt.reversal_hold h where h.id = hold_id))
    with check (exists (select 1 from debt.reversal_hold h where h.id = hold_id));

-- campos da criação são imutáveis; a retenção não volta a PENDING nem muda de desfecho (RS-123)
create or replace function debt.reversal_hold_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'debt.reversal_hold não pode ser removida' using errcode = '42501';
    end if;
    if new.tenant_id <> old.tenant_id or new.creditor_id <> old.creditor_id or new.debtor_id <> old.debtor_id
        or new.payment_id <> old.payment_id or new.reversal_id <> old.reversal_id
        or new.reversal_reason <> old.reversal_reason
        or new.placed_at <> old.placed_at or new.placed_by <> old.placed_by
        or new.placed_by_type <> old.placed_by_type or new.informed_by is distinct from old.informed_by
        or new.created_at <> old.created_at or new.created_by is distinct from old.created_by then
        raise exception 'dados da retenção são imutáveis' using errcode = '42501';
    end if;
    if old.state <> 'PENDING' and (new.state <> old.state
        or new.released_at is distinct from old.released_at or new.released_by is distinct from old.released_by
        or new.release_reason_code is distinct from old.release_reason_code
        or new.ended_at is distinct from old.ended_at or new.ended_reason is distinct from old.ended_reason) then
        raise exception 'retenção já encerrada não muda de situação' using errcode = '42501';
    end if;
    -- a justificativa só pode ser limpa (anonimização), nunca reescrita
    if old.release_note is not null and new.release_note is not null and new.release_note <> old.release_note then
        raise exception 'justificativa da liberação é imutável' using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_reversal_hold_guard before update or delete on debt.reversal_hold
    for each row execute function debt.reversal_hold_guard();
create trigger trg_reversal_hold_item_append_only before update or delete on debt.reversal_hold_item
    for each row execute function shared.forbid_mutation();

grant select, insert, update on debt.reversal_hold to app_runtime;
grant select, insert on debt.reversal_hold_item to app_runtime;
revoke delete on debt.reversal_hold from app_runtime;
revoke update, delete on debt.reversal_hold_item from app_runtime;
