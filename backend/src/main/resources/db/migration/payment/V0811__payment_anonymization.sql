-- LGPD (SEG-20261124-D01, decisão 1458): o estorno continua imutável, exceto pela anonimização do titular, que apaga a
-- observação (texto livre) no consumo de DebtorAnonymized; e índices para achar, por pagamento, as linhas de extrato e
-- as divergências do titular sem varrer as tabelas da assessoria (a retenção anonimiza em lote).
create or replace function payment.reversal_anonymize_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: payment.payment_reversal' using errcode = 'P0001';
    end if;
    if old.note_encrypted is null
       or new.note_encrypted is not null
       or (to_jsonb(new) - 'note_encrypted' - 'version' - 'updated_at' - 'updated_by')
          is distinct from (to_jsonb(old) - 'note_encrypted' - 'version' - 'updated_at' - 'updated_by') then
        raise exception 'Registro imutável: payment.payment_reversal (somente a anonimização da observação é permitida)'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;

drop trigger trg_payment_reversal_append_only on payment.payment_reversal;
create trigger trg_payment_reversal_append_only before update or delete on payment.payment_reversal
    for each row execute function payment.reversal_anonymize_only();

grant update (note_encrypted, version, updated_at, updated_by) on payment.payment_reversal to app_runtime;

create index ix_statement_line_payment on payment.statement_line (tenant_id, payment_id) where payment_id is not null;
create index ix_divergence_payment on payment.divergence (tenant_id, payment_id) where payment_id is not null;
create index ix_divergence_resolved_payment on payment.divergence (tenant_id, resolved_payment_id)
    where resolved_payment_id is not null;
