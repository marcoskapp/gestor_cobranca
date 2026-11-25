-- H07 (SEG-20261124-H07, decisão 1466): pagamento recebido diretamente pelo credor e informado pela integração do
-- credor (API pública, token de integração). Não passa por revisão abaixo dos limites, mas o canal fica gravado para o
-- destaque nos títulos, a lista "Baixas informadas pelo credor" e o teto acumulado por credor (somando todos os tokens).
-- O canal vem do tipo do ator (nunca do pedido): INTEGRATION só existe com origem CREDITOR_DIRECT, quem informou (o
-- token) e sem verificação de comprovante do portal.

alter table payment.payment
    drop constraint ck_payment_informed_channel,
    drop constraint ck_payment_creditor_portal_review,
    drop constraint ck_payment_creditor_portal_decided,
    drop constraint ck_payment_creditor_rejection_required,
    drop constraint ck_payment_review_reason,
    add constraint ck_payment_informed_channel
        check (informed_channel is null or informed_channel in ('CREDITOR_PORTAL', 'INTEGRATION')),
    add constraint ck_payment_creditor_portal_review
        check (informed_channel is distinct from 'CREDITOR_PORTAL'
            or (review_required and source = 'EXTERNAL' and external_origin = 'CREDITOR_DIRECT'
                and review_reason = 'CREDITOR_PORTAL' and creditor_title_numbers is not null
                and evidence_scan_verdict is not null and evidence_scanned_at is not null)),
    add constraint ck_payment_creditor_portal_decided
        check (informed_channel is distinct from 'CREDITOR_PORTAL' or status in ('INFORMED', 'REJECTED')
            or reviewed_by is not null),
    add constraint ck_payment_creditor_rejection_required
        check (informed_channel is distinct from 'CREDITOR_PORTAL' or status <> 'REJECTED'
            or creditor_rejection_reason is not null),
    add constraint ck_payment_integration_channel
        check (informed_channel is distinct from 'INTEGRATION'
            or (source = 'EXTERNAL' and external_origin = 'CREDITOR_DIRECT' and informed_by is not null
                and evidence_scan_verdict is null and creditor_title_numbers is null)),
    add constraint ck_payment_review_reason
        check (review_reason is null
            or review_reason in ('AMOUNT', 'DESTINATION_CUMULATIVE', 'INFORMER_CUMULATIVE', 'CREDITOR_CUMULATIVE',
                                 'STATEMENT_LINE', 'CREDITOR_PORTAL'));

-- Pagamentos já informados pela integração antes do canal existir: reconhecidos pela auditoria do registro
-- (payment.informed com ator INTEGRATION). A RLS forçada vale também para o dono das tabelas: uma assessoria por vez.
-- Nunca lê o schema do publicapi (criado depois, na V2100, num banco novo).
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update payment.payment p
           set informed_channel = 'INTEGRATION'
         where p.source = 'EXTERNAL'
           and p.external_origin = 'CREDITOR_DIRECT'
           and p.informed_channel is null
           and exists (select 1 from shared.audit_event a
                        where a.entity_type = 'payment' and a.entity_id = p.id
                          and a.action = 'payment.informed' and a.actor_type = 'INTEGRATION');
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

-- teto acumulado por credor (soma da janela; review_required filtrado sobre poucas linhas) e a lista "Baixas informadas
-- pelo credor", sempre com o escopo por credor
create index ix_payment_integration on payment.payment (tenant_id, creditor_id, informed_at desc)
    where informed_channel = 'INTEGRATION';
