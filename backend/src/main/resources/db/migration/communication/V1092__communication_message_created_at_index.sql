-- =============================================================================
-- Leitura por período das mensagens das conversas: tempo de resposta das pessoas no chat na apuração diária do custo
-- de pessoas (costing.src_chat_message, docs/tecnico/desenho/margem-por-carteira.md §4.1). A apuração ordena as
-- mensagens de cada conversa pelo registro (created_at) para achar o evento anterior a cada resposta.
-- =============================================================================
create index ix_message_tenant_created on communication.message (tenant_id, created_at);
