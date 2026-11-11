-- =============================================================================
-- Compliance: categoria AUTHENTICATION no porteiro de contato — código de acesso pedido pelo devedor para entrar no
-- portal: só restrições e anonimização (sem janela de horário, feriados nem limites); não entra nos limites.
-- =============================================================================
alter table compliance.contact_attempt drop constraint ck_contact_attempt_category;
alter table compliance.contact_attempt add constraint ck_contact_attempt_category
    check (category in ('COLLECTION', 'TRANSACTIONAL', 'RESPONSE', 'AUTHENTICATION')) not valid;
-- só amplia os valores aceitos: a validação fica na migration seguinte, em transação própria (o lock do ALTER
-- aqui dura só a troca do check)
