-- =============================================================================
-- Contrato de leitura do costing (docs/tecnico/desenho/margem-por-carteira.md §2 e
-- "Implementação da fase 1"). Mesmo padrão do analytics (R__analytics_contract.sql):
--
--   * Migration REPETÍVEL: roda depois de todas as versionadas (lê tabelas de módulos com
--     faixa maior e menor que a do costing) e de novo sempre que este arquivo muda.
--   * Toda view é security_invoker: roda com as políticas de RLS de quem consulta
--     (app_runtime com app.tenant_id da transação) — nada de outra assessoria é visível.
--   * Cada view src_* expõe só as colunas que a apuração usa. O PostgreSQL recusa
--     DROP COLUMN/ALTER TYPE em coluna usada por view: a migration do módulo produtor
--     falha (e com ela a suíte), em vez de a apuração quebrar em produção. Para alterar
--     uma coluna do contrato, a migration do produtor remove a view (drop view
--     costing.src_x) e o mesmo pull request altera este arquivo.
--   * Nenhuma view expõe dado pessoal do devedor. Usuário (operador) aparece só como id,
--     para o cálculo em memória do custo de pessoas; os fatos gravados não têm usuário.
-- =============================================================================

do $$
declare
    v record;
begin
    for v in select viewname from pg_views where schemaname = 'costing' loop
        execute format('drop view if exists costing.%I cascade', v.viewname);
    end loop;
end
$$;

-- ----------------------------------------------------------------- organization
create view costing.src_creditor with (security_invoker = true) as
select c.tenant_id, c.id as creditor_id, c.trade_name as name
from organization.creditor c;

create view costing.src_portfolio with (security_invoker = true) as
select p.tenant_id, p.id as portfolio_id, p.creditor_id, p.code, p.name
from organization.portfolio p;

-- ------------------------------------------------------------------- collection
-- Tempo direto: interações registradas por pessoa (actor_type USER) com duração.
create view costing.src_interaction with (security_invoker = true) as
select i.tenant_id, i.case_id, i.creditor_id, i.portfolio_id, i.operator_id, i.actor_type, i.started_at,
       i.duration_seconds
from collection.interaction i;

-- -------------------------------------------------------------------- telephony
-- Conta efetivamente usada na chamada (provider_account_id) e tempos; sem número nem devedor.
create view costing.src_call with (security_invoker = true) as
select c.tenant_id, c.creditor_id, c.portfolio_id, c.operator_id, c.provider_account_id, c.talk_seconds,
       c.wrap_up_seconds, c.queued_at, c.ended_at
from telephony.phone_call c;

create view costing.src_provider_account with (security_invoker = true) as
select a.tenant_id, a.id as provider_account_id, a.rate_per_minute
from telephony.provider_account a;

-- -------------------------------------------------------------------- incentive
create view costing.src_logged_time with (security_invoker = true) as
select l.tenant_id, l.fact_date, l.user_id, l.seconds
from incentive.logged_time_daily l;

-- Comissão variável dos negociadores (pessoa ou equipe), sem o beneficiário.
create view costing.src_commission_ledger with (security_invoker = true) as
select e.tenant_id, e.creditor_id, e.portfolio_id, e.kind, e.status, e.credited_amount, e.paid_on, e.created_at
from incentive.commission_ledger_entry e;

-- ------------------------------------------------------------------ IA (USD)
create view costing.src_assistant_cost with (security_invoker = true) as
select t.tenant_id, t.creditor_id, t.portfolio_id, t.started_at, t.cost_amount, t.cost_currency
from assistant.assistant_turn t;

create view costing.src_pdf_import_cost with (security_invoker = true) as
select p.tenant_id, p.creditor_id, p.portfolio_id, p.created_at, p.ai_cost, p.ai_currency
from ingestion.pdf_import p;

-- ---------------------------------------------------------------- communication
-- Mensagens saídas com a categoria do template no provedor (WhatsApp); sem conteúdo nem contato.
create view costing.src_message with (security_invoker = true) as
select m.tenant_id, m.conversation_id, c.creditor_id, c.portfolio_id, m.channel, m.direction, m.status,
       m.template_id, t.provider_category, m.sent_at
from communication.message m
join communication.conversation c on c.id = m.conversation_id
left join communication.message_template t on t.id = m.template_id;

-- ------------------------------------------------------------------------ legal
create view costing.src_legal_expense with (security_invoker = true) as
select e.tenant_id, e.creditor_id, m.portfolio_id, e.amount, e.incurred_on, e.advanced_by, e.reimbursable, e.status
from legal.legal_expense e
join legal.legal_matter m on m.id = e.matter_id;

-- ------------------------------------------------------------------- settlement
-- Recebimentos (PAYMENT positivo, REVERSAL negativo) na data do fato.
create view costing.src_receipt with (security_invoker = true) as
select r.tenant_id, r.id as receipt_id, r.kind, r.creditor_id, r.portfolio_id, r.amount, r.competence_date
from settlement.receipt r;

-- Receita da assessoria por recebimento: comissão + valor fixo + honorários da assessoria
-- (lançamentos de estorno já vêm negativos).
create view costing.src_commission_entry with (security_invoker = true) as
select e.tenant_id, e.receipt_id, e.commission_amount, e.fixed_fee, e.agency_honorarium
from settlement.commission_entry e;

-- ------------------------------------------------------------------ negotiation
create view costing.src_agreement with (security_invoker = true) as
select a.tenant_id, a.creditor_id, a.portfolio_id, a.status, a.settled_at
from negotiation.agreement a;

grant select on costing.src_creditor, costing.src_portfolio, costing.src_interaction, costing.src_call,
    costing.src_provider_account, costing.src_logged_time, costing.src_commission_ledger, costing.src_assistant_cost,
    costing.src_pdf_import_cost, costing.src_message, costing.src_legal_expense, costing.src_receipt,
    costing.src_commission_entry, costing.src_agreement to app_runtime;
