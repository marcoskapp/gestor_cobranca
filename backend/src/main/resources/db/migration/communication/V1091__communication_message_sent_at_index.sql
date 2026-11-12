-- =============================================================================
-- Leitura por período das mensagens enviadas ao provedor: apuração diária dos custos de mensageria (WhatsApp por
-- janela de 24 h, SMS e e-mail por mensagem — costing.src_message, docs/tecnico/desenho/margem-por-carteira.md §4).
-- Parcial: só mensagens saídas já enviadas (as que custam).
-- =============================================================================
create index ix_message_outbound_sent on communication.message (tenant_id, sent_at)
    where direction = 'OUTBOUND' and sent_at is not null;
