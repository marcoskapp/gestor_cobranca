-- =============================================================================
-- Communication: categoria da mensagem no porteiro de contato (docs/negocio/contestacoes.md §6).
-- COLLECTION (cobrança, padrão de todas as mensagens existentes) ou TRANSACTIONAL (resposta
-- devida ao devedor, ex.: resultado da contestação), com o agregado de origem para rastreio.
-- A categoria é a mesma na verificação prévia e na autorização imediatamente antes do envio.
-- =============================================================================
alter table communication.message
    add column contact_category varchar(20) not null default 'COLLECTION',
    add column reference_type   varchar(40),
    add column reference_id     uuid,
    add constraint ck_message_contact_category check (contact_category in ('COLLECTION', 'TRANSACTIONAL')),
    add constraint ck_message_reference check ((reference_type is null) = (reference_id is null));
