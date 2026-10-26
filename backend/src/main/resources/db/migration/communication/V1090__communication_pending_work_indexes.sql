-- =============================================================================
-- "Minhas pendências" (docs/tecnico/desenho/pendencias.md): rascunhos de template
-- aguardando a decisão do provedor ou a publicação.
-- =============================================================================
create index ix_template_draft_approval on communication.message_template (tenant_id, approval_status)
    where status = 'DRAFT';
