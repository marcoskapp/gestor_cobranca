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

-- Comissão variável dos negociadores em R$ por lançamento do razão, sem o beneficiário (margem-por-carteira.md §4.2).
-- O razão guarda o RECUPERADO creditado (base × peso × fator); a comissão em R$ só existe no extrato do período, que
-- é por pessoa e só recebe os lançamentos PESSOAIS dela (os de equipe nunca recebem extrato: o pool entra no valor a
-- pagar de membros e supervisores). Taxa efetiva de cada lançamento:
--   * pessoal, no extrato: valor a pagar ÷ creditado líquido dos lançamentos dele (só com creditado líquido positivo;
--     estornos que superam os acúmulos não geram taxa negativa);
--   * pessoal, sem extrato (pendente ou período aberto): a do último extrato do mesmo beneficiário; sem nenhum, a do
--     último período da assessoria; sem extrato na assessoria, zero;
--   * de equipe: só o valor a pagar dos extratos SEM lançamento pessoal (participação no pool, ex.: supervisor),
--     rateado pelo creditado de equipe liberado no mesmo período; pendente: a última dessas taxas; sem, zero.
--     O pool de quem também tem lançamento pessoal já está no extrato dela e segue o rateio pessoal.
-- `settled` diz se o valor já é o do extrato (senão é estimativa). O valor por pessoa não sai da view.
create view costing.src_commission_cost with (security_invoker = true) as
with statement_rate as (
    select s.tenant_id, s.id as statement_id, s.beneficiary_id, s.period_start, s.payable_amount,
           sum(e.credited_amount) as credited,
           case when sum(e.credited_amount) > 0 then s.payable_amount / sum(e.credited_amount) end as rate
    from incentive.commission_statement s
    join incentive.commission_ledger_entry e on e.tenant_id = s.tenant_id and e.statement_id = s.id
    group by s.tenant_id, s.id, s.beneficiary_id, s.period_start, s.payable_amount
),
beneficiary_rate as (
    select distinct on (r.tenant_id, r.beneficiary_id) r.tenant_id, r.beneficiary_id, r.rate
    from statement_rate r
    where r.rate is not null
    order by r.tenant_id, r.beneficiary_id, r.period_start desc
),
tenant_rate as (
    select distinct on (r.tenant_id) r.tenant_id,
           case when sum(r.credited) > 0 then sum(r.payable_amount) / sum(r.credited) end as rate
    from statement_rate r
    group by r.tenant_id, r.period_start
    order by r.tenant_id, r.period_start desc
),
pool_payable as (
    select s.tenant_id, s.period_start, sum(s.payable_amount) as payable
    from incentive.commission_statement s
    where not exists (select 1 from incentive.commission_ledger_entry e
                      where e.tenant_id = s.tenant_id and e.statement_id = s.id)
    group by s.tenant_id, s.period_start
),
pool_rate as (
    select p.tenant_id, p.period_start,
           case when sum(e.credited_amount) > 0 then p.payable / sum(e.credited_amount) end as rate
    from pool_payable p
    join incentive.commission_ledger_entry e on e.tenant_id = p.tenant_id and e.period_start = p.period_start
                                            and e.beneficiary_type = 'TEAM' and e.status = 'CLEARED'
    group by p.tenant_id, p.period_start, p.payable
),
latest_pool_rate as (
    select distinct on (r.tenant_id) r.tenant_id, r.rate
    from pool_rate r
    where r.rate is not null
    order by r.tenant_id, r.period_start desc
)
select e.tenant_id, e.creditor_id, e.portfolio_id, e.kind, e.status, e.paid_on, e.created_at,
       round(e.credited_amount * case when e.beneficiary_type = 'TEAM'
                                      then coalesce(pool.rate, case when e.period_start is null then lp.rate end, 0)
                                      when e.statement_id is not null then coalesce(own.rate, 0)
                                      else coalesce(latest.rate, t.rate, 0) end, 2) as commission_amount,
       (own.rate is not null or pool.rate is not null) as settled
from incentive.commission_ledger_entry e
left join statement_rate own on own.statement_id = e.statement_id
left join beneficiary_rate latest on e.statement_id is null and e.beneficiary_type = 'USER'
                                 and latest.tenant_id = e.tenant_id and latest.beneficiary_id = e.beneficiary_id
left join tenant_rate t on e.statement_id is null and e.beneficiary_type = 'USER' and t.tenant_id = e.tenant_id
left join pool_rate pool on e.beneficiary_type = 'TEAM' and pool.tenant_id = e.tenant_id
                        and pool.period_start = e.period_start
left join latest_pool_rate lp on e.beneficiary_type = 'TEAM' and lp.tenant_id = e.tenant_id;

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

-- Mensagens do chat para o tempo de resposta das pessoas (§4.1): quem enviou (só o id do usuário, para o
-- cálculo em memória) e quando; sem conteúdo, contato nem devedor.
create view costing.src_chat_message with (security_invoker = true) as
select m.tenant_id, m.conversation_id, c.creditor_id, c.portfolio_id, m.sender_type, m.sender_id, m.created_at
from communication.message m
join communication.conversation c on c.id = m.conversation_id;

grant select on costing.src_creditor, costing.src_portfolio, costing.src_interaction, costing.src_call,
    costing.src_provider_account, costing.src_logged_time, costing.src_commission_cost, costing.src_assistant_cost,
    costing.src_pdf_import_cost, costing.src_message, costing.src_legal_expense, costing.src_receipt,
    costing.src_commission_entry, costing.src_agreement, costing.src_chat_message to app_runtime;
