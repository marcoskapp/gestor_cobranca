-- Portal do credor (fatia 7, decisão do responsável de 26/11/2026): prazo para o credor responder ao demonstrativo
-- enviado — data do envio (fuso de negócio) + N dias corridos (app.settlement.statement-response-days, padrão 10).
-- É só lembrete: vencido, o demonstrativo continua SENT ("prazo vencido"), sem efeito automático.
-- Gravado no envio; os já enviados recebem envio + 10 dias (o padrão). settlement.statement tem RLS forçada:
-- preenchimento por assessoria (mesmo padrão da V0901 e da decisão 849). A coluna não está na lista de imutáveis do
-- gatilho statement_guard (não é valor do demonstrativo).

alter table settlement.statement add column response_due_on date;

do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update settlement.statement
        set response_due_on = (sent_at at time zone 'America/Sao_Paulo')::date + 10
        where sent_at is not null and response_due_on is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

-- Expand/contract (scripts/deploy/release.sh): a versão anterior envia demonstrativo sem prazo, por isso o check aceita
-- prazo nulo nesta fase. A igualdade (sent_at is null) = (response_due_on is null) entra numa migration posterior,
-- depois de uma versão em produção, com novo preenchimento dos enviados sem prazo (decisão 1557).
alter table settlement.statement add constraint ck_statement_response_due
    check (response_due_on is null
           or (sent_at is not null and response_due_on >= (sent_at at time zone 'America/Sao_Paulo')::date));

-- "Aguardando você" do portal do credor: demonstrativos enviados e sem resposta do credor, pelo prazo
create index ix_statement_creditor_awaiting on settlement.statement (tenant_id, creditor_id, response_due_on)
    where status = 'SENT';
