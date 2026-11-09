-- Aprovação das correções de valor no padrão único das filas de aprovação (revisão de produto 11/2026, item 3.1): quem
-- decide vê quanto o pedido passa do limite da assessoria. O limite e as reduções já aplicadas sem segunda pessoa no
-- título (janela do limite acumulado) entravam na decisão do pedido, mas não eram gravados; passam a ficar no pedido,
-- uma vez, no registro. Pedidos anteriores ficam nulos: a tela usa o limite atual e reduções recentes zero. Sem backfill.
alter table debt.title_amount_adjustment
    add column request_threshold         numeric(19, 2),
    add column request_recent_reductions numeric(19, 2),
    add constraint ck_amount_adjustment_request_threshold
        check (num_nulls(request_threshold, request_recent_reductions) in (0, 2)
            and (request_threshold is null or (request_threshold >= 0 and request_recent_reductions >= 0)));
