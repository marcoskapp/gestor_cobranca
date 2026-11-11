-- =============================================================================
-- Valida o check de categoria ampliado na migration anterior (criado NOT VALID), em transação própria: a varredura
-- usa lock que não bloqueia leituras nem gravações da tabela.
-- =============================================================================
alter table communication.message validate constraint ck_message_contact_category;
