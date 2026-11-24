-- Portal do credor (ADR 0011, fatia 5): a própria pessoa do credor responde, no portal, ao pedido da assessoria na
-- contestação. A resposta ganha a origem CREDITOR_PORTAL (os documentos passam pela quarentena, como os de toda
-- origem externa) e a lista "Contestações" do portal busca os pedidos pelo credor (todos os que aguardam e os recentes).

alter table collection.dispute_creditor_response
    drop constraint ck_dispute_response_source,
    add constraint ck_dispute_response_source check (source in ('MANUAL', 'PUBLIC_API', 'EMAIL', 'CREDITOR_PORTAL'));

create index ix_dispute_request_creditor on collection.dispute_creditor_request (tenant_id, creditor_id, created_at desc);
-- as que aguardam o credor entram todas na lista do portal, pelo prazo
create index ix_dispute_request_creditor_awaiting on collection.dispute_creditor_request (tenant_id, creditor_id, requested_due_date)
    where status in ('QUEUED', 'SENT', 'FAILED');
