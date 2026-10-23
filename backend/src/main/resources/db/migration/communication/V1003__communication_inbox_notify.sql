-- =============================================================================
-- Tempo real da caixa de entrada (ADR-0008): avisos LISTEN/NOTIFY no canal
-- communication_inbox a cada mudança de conversa (dono, situação, nova mensagem,
-- contadores) e de situação de mensagem (enviada, entregue, lida, falha).
--
-- O PostgreSQL entrega o aviso somente na confirmação da transação (transações
-- desfeitas não avisam) e a todas as sessões que escutam o canal — uma por
-- instância da aplicação. Avisos idênticos na mesma transação são unificados.
-- Conteúdo: somente ids (assessoria, conversa, credor); nada pessoal. Quem recebe
-- consulta os endpoints normais, sujeitos a permissão, escopo e RLS.
-- =============================================================================

create or replace function communication.notify_conversation_change() returns trigger
    language plpgsql as
$$
begin
    perform pg_notify('communication_inbox',
                      json_build_object('t', new.tenant_id, 'c', new.id, 'k', new.creditor_id)::text);
    return null;
end;
$$;

create or replace function communication.notify_message_change() returns trigger
    language plpgsql as
$$
declare
    v_creditor uuid;
begin
    -- a conversa é lida sob o RLS do tenant da transação (a mensagem pertence ao mesmo tenant)
    select c.creditor_id into v_creditor from communication.conversation c where c.id = new.conversation_id;
    perform pg_notify('communication_inbox',
                      json_build_object('t', new.tenant_id, 'c', new.conversation_id, 'k', v_creditor)::text);
    return null;
end;
$$;

create trigger tg_conversation_notify
    after insert or update on communication.conversation
    for each row execute function communication.notify_conversation_change();

create trigger tg_message_notify
    after insert or update of status on communication.message
    for each row execute function communication.notify_message_change();
