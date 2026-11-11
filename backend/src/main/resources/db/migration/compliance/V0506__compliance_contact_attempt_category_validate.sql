-- =============================================================================
-- Valida o check de categoria ampliado na migration anterior (criado NOT VALID), em transação própria: a varredura
-- usa lock que não bloqueia leituras nem gravações da tabela.
-- =============================================================================
alter table compliance.contact_attempt validate constraint ck_contact_attempt_category;
