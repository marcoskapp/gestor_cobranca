-- =============================================================================
-- Communication: categoria AUTHENTICATION da mensagem — código de acesso ao portal pedido pelo devedor, repassada ao
-- porteiro de contato na verificação prévia e no envio.
-- =============================================================================
alter table communication.message drop constraint ck_message_contact_category;
alter table communication.message add constraint ck_message_contact_category
    check (contact_category in ('COLLECTION', 'TRANSACTIONAL', 'RESPONSE', 'AUTHENTICATION')) not valid;
-- só amplia os valores aceitos: a validação fica na migration seguinte, em transação própria (o lock do ALTER
-- aqui dura só a troca do check)
