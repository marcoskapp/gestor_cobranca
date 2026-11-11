-- =============================================================================
-- Communication: categoria RESPONSE da mensagem — resposta de operador ou assistente enquanto o devedor aguarda
-- retorno (contato iniciado por ele). Repassada ao porteiro de contato na verificação prévia e no envio.
-- =============================================================================
alter table communication.message drop constraint ck_message_contact_category;
alter table communication.message add constraint ck_message_contact_category
    check (contact_category in ('COLLECTION', 'TRANSACTIONAL', 'RESPONSE')) not valid;
-- só amplia os valores aceitos: valida depois, sem bloquear os envios durante a varredura
alter table communication.message validate constraint ck_message_contact_category;
