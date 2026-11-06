-- =============================================================================
-- Telephony: marca durável do pedido de conexão ao operador (job telephony.bridge).
--
-- * phone_call.bridge_requested_at: gravada no preparo do job, em transação própria
--   e antes do HTTP ao provedor. Uma nova execução do job que encontra a marca não
--   sabe se o provedor recebeu o pedido e não o reenvia (repetir a conexão derrubaria
--   a conversa); sem a marca, a execução anterior falhou antes de pedir e o pedido é
--   feito. Limpa quando a conexão é desfeita. Não é dado pessoal.
-- =============================================================================

alter table telephony.phone_call add column bridge_requested_at timestamptz;
