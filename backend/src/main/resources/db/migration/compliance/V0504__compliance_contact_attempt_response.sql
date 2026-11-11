-- =============================================================================
-- Compliance: categoria RESPONSE no porteiro de contato — resposta a contato iniciado pelo devedor que aguarda
-- retorno (mensagem recebida ou pedido de atendimento pelo portal). Registrada para rastreabilidade e, como a
-- TRANSACTIONAL, não entra nos limites de frequência da cobrança.
-- =============================================================================
alter table compliance.contact_attempt drop constraint ck_contact_attempt_category;
alter table compliance.contact_attempt add constraint ck_contact_attempt_category
    check (category in ('COLLECTION', 'TRANSACTIONAL', 'RESPONSE')) not valid;
-- só amplia os valores aceitos: valida depois, sem bloquear os envios durante a varredura
alter table compliance.contact_attempt validate constraint ck_contact_attempt_category;
