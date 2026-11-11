-- =============================================================================
-- Communication: finalidade AUTHENTICATION do template — código de acesso ao portal (decisão 1084). Corpo curto e
-- só as variáveis do código; no WhatsApp, categoria de autenticação da Meta. O envio do código exige esta finalidade.
-- =============================================================================
alter table communication.message_template drop constraint ck_template_purpose;
alter table communication.message_template add constraint ck_template_purpose
    check (purpose in ('COLLECTION', 'REMINDER', 'NOTIFICATION', 'AGREEMENT', 'PAYMENT', 'SERVICE', 'AUTHENTICATION'));
