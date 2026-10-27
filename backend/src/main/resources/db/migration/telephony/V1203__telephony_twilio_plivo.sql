-- =============================================================================
-- Telephony: provedores Twilio e Plivo (APIs públicas, adaptadores prontos).
--
-- * provider_account aceita TWILIO e PLIVO.
-- * phone_call.provider_leg_id: id da perna da chamada no provedor quando difere
--   do id devolvido no pedido de discagem. A Plivo devolve request_uuid ao criar
--   a chamada e informa o CallUUID nos eventos; encerrar e transferir usam o
--   CallUUID. Não é dado pessoal (identificador técnico do provedor).
-- =============================================================================

alter table telephony.provider_account drop constraint ck_tel_account_provider;
alter table telephony.provider_account add constraint ck_tel_account_provider
    check (provider in ('SANDBOX', 'ALVARIA', 'TARIFANDO', 'TWILIO', 'PLIVO'));

alter table telephony.phone_call add column provider_leg_id varchar(200);
