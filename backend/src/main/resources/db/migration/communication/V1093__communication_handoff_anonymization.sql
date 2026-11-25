-- LGPD (SEG-20261124-D01): a transferência de atendimento continua imutável, exceto pela anonimização do titular, que
-- apaga o resumo (texto livre sobre o atendimento) no consumo de DebtorAnonymized. As demais colunas não mudam.
create or replace function communication.handoff_anonymize_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: communication.conversation_handoff' using errcode = 'P0001';
    end if;
    if old.summary_encrypted is null
       or new.summary_encrypted is not null
       or (to_jsonb(new) - 'summary_encrypted' - 'version' - 'updated_at' - 'updated_by')
          is distinct from (to_jsonb(old) - 'summary_encrypted' - 'version' - 'updated_at' - 'updated_by') then
        raise exception 'Registro imutável: communication.conversation_handoff (somente a anonimização do resumo é permitida)'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;

drop trigger trg_handoff_append_only on communication.conversation_handoff;
create trigger trg_handoff_append_only before update or delete on communication.conversation_handoff
    for each row execute function communication.handoff_anonymize_only();

grant update (summary_encrypted, version, updated_at, updated_by) on communication.conversation_handoff to app_runtime;
