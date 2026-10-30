-- =============================================================================
-- Proporcionalidade do ciclo de transição (decisão 621, do responsável): o extrato
-- guarda os dias do ciclo e os dias do ciclo regular de referência; mensalidade e
-- franquias são proporcionais a cycle_days ÷ reference_cycle_days. Nulos nos
-- extratos gravados antes da regra (fator 1). Colunas novas não alteram linhas
-- existentes (o extrato continua append-only).
-- =============================================================================
alter table publicapi.usage_statement
    add column cycle_days integer,
    add column reference_cycle_days integer;
alter table publicapi.usage_statement
    add constraint ck_usage_statement_proration check ((cycle_days is null) = (reference_cycle_days is null)
        and (cycle_days is null or (cycle_days >= 1 and reference_cycle_days >= 1)));
