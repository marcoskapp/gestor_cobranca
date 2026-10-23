-- =============================================================================
-- negotiation: LGPD — a simulação continua append-only, exceto pela anonimização
-- da observação livre da justificativa de concessão (coluna e memória de cálculo
-- em JSON), feita no consumo de DebtorAnonymized. O código do motivo permanece.
-- =============================================================================

create or replace function negotiation.simulation_anonymize_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: negotiation.simulation' using errcode = 'P0001';
    end if;
    if new.justification_note is not null
       or (to_jsonb(new) - 'justification_note' - 'calculation')
          is distinct from (to_jsonb(old) - 'justification_note' - 'calculation')
       or new.calculation is distinct from (
           case when jsonb_typeof(old.calculation -> 'justification') = 'object'
                then jsonb_set(old.calculation, '{justification,note}', 'null'::jsonb)
                else old.calculation end) then
        raise exception 'Registro imutável: negotiation.simulation (somente a anonimização da justificativa é permitida)'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;

drop trigger trg_simulation_append_only on negotiation.simulation;
create trigger trg_simulation_append_only before update or delete on negotiation.simulation
    for each row execute function negotiation.simulation_anonymize_only();

grant update (justification_note, calculation) on negotiation.simulation to app_runtime;
