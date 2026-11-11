-- =============================================================================
-- negotiation: desconto máximo que a política permitia na formalização do acordo (decisão 1091). Com ele, o uso do
-- desconto (envelope_usage) distingue "não havia desconto a conceder" (máximo zero) de "havia e não foi usado".
-- Nulo nos acordos anteriores (o dado não existia).
-- =============================================================================
alter table negotiation.agreement add column max_discount_amount numeric(19, 2);
alter table negotiation.agreement add constraint ck_agreement_max_discount
    check (max_discount_amount is null or max_discount_amount >= 0);
