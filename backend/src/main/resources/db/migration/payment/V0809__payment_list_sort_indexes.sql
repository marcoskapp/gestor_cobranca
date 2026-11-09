-- =============================================================================
-- Ordenação da lista de pagamentos sobre a assessoria inteira (GET /api/v1/payment/payments, decisão 954). Os
-- índices existentes começam pela situação ou pelo devedor. Medido em 200 mil pagamentos de uma assessoria
-- (docs/tecnico/desenho/ux.md §16.1): seq scan + top-N heapsort (23–28 ms, ~11,7 mil buffers) → 0,2 ms com
-- coluna + id. O valor do pagamento não muda depois de registrado.
-- =============================================================================
create index ix_payment_tenant_paid on payment.payment (tenant_id, paid_at, id);
create index ix_payment_tenant_amount on payment.payment (tenant_id, amount, id);
