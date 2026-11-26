-- Remessa ao credor (decisão 1486): pendentes do credor na ordem da anonimização, sem ler o histórico já avisado
-- (a guarda dos avisos é indefinida, decisão 1483). V0512 e V0513 ficam reservadas à retenção jurídica.
create index ix_creditor_erasure_notice_pending_creditor
    on compliance.creditor_erasure_notice (tenant_id, creditor_id, erased_at, id)
    where status = 'PENDING';
