-- =============================================================================
-- Contrato de leitura do analytics (docs/tecnico/desenho/analytics.md §3).
--
-- Migration REPETÍVEL: o Flyway a executa depois de todas as versionadas e de novo
-- sempre que este arquivo muda. Assim as views podem ler tabelas de qualquer módulo,
-- inclusive dos que têm faixa de versão maior que a do analytics (incentive, V18xx).
--
-- Regras:
--   * Toda view é security_invoker: roda com os privilégios e as políticas de RLS de
--     quem consulta (app_runtime, com app.tenant_id da transação). Nada é visível sem
--     assessoria; nada de outra assessoria é visível. Tabela de origem sem RLS
--     (shared.processing_job, fila global de jobs) é filtrada explicitamente pela
--     assessoria corrente.
--   * Cada view src_* expõe só as colunas que o analytics usa de cada módulo — é o
--     contrato. O PostgreSQL recusa DROP COLUMN/ALTER TYPE em coluna usada por view:
--     a migration do módulo produtor falha (e com ela toda a suíte de testes), em vez
--     de o painel quebrar em produção. Domínios de status/origem de que as fórmulas
--     dependem são conferidos por AnalyticsContractIT.
--   * Para alterar uma coluna do contrato, a migration do módulo produtor remove a
--     view afetada (drop view analytics.src_x) e o mesmo pull request altera este
--     arquivo (a mudança de checksum o reexecuta e recria tudo).
--   * Nenhuma view expõe dado pessoal do devedor (nome, documento, contato).
-- =============================================================================

-- Recria do zero: views (e as funções que as leem) não guardam estado.
do $$
declare
    v record;
begin
    for v in select viewname from pg_views where schemaname = 'analytics' loop
        execute format('drop view if exists analytics.%I cascade', v.viewname);
    end loop;
end
$$;

-- ----------------------------------------------------------------- organization
create view analytics.src_creditor with (security_invoker = true) as
select c.tenant_id, c.id as creditor_id, c.trade_name as name
from organization.creditor c;

create view analytics.src_portfolio with (security_invoker = true) as
select p.tenant_id, p.id as portfolio_id, p.creditor_id, p.code, p.name
from organization.portfolio p;

-- --------------------------------------------------------------------- identity
-- Somente id e nome (sem e-mail, credenciais ou sessões).
create view analytics.src_user with (security_invoker = true) as
select u.tenant_id, u.id as user_id, u.name
from identity.app_user u;

create view analytics.src_team with (security_invoker = true) as
select t.tenant_id, t.id as team_id, t.name, t.supervisor_id, t.active
from identity.team t;

-- Composição atual das equipes (o identity não guarda histórico; mesma leitura do incentive).
-- team_member não tem tenant_id: a política dela exige a equipe visível (RLS da linha pai).
create view analytics.src_team_member with (security_invoker = true) as
select m.team_id, m.user_id
from identity.team_member m;

-- ------------------------------------------------------------------------- debt
create view analytics.src_title with (security_invoker = true) as
select t.tenant_id, t.id as title_id, t.creditor_id, t.portfolio_id, t.due_date, t.status, t.open_balance
from debt.debt_title t;

-- ------------------------------------------------------------------- collection
create view analytics.src_case with (security_invoker = true) as
select c.tenant_id, c.id as case_id, c.creditor_id, c.portfolio_id, c.status, c.eligible, c.open_balance,
       c.eligible_balance, c.oldest_due_date, c.owner_type, c.queue_id, c.assignee_user_id, c.assignee_team_id
from collection.collection_case c;

-- Interações (contato efetivo = tabulação de pessoa certa), de humanos e do assistente.
create view analytics.src_interaction with (security_invoker = true) as
select i.tenant_id, i.id as interaction_id, i.case_id, i.creditor_id, i.portfolio_id, i.channel, i.direction,
       i.effective_contact, i.operator_id, i.actor_type, i.started_at
from collection.interaction i;

create view analytics.src_queue with (security_invoker = true) as
select q.tenant_id, q.id as queue_id, q.code, q.name, q.creditor_id, q.active
from collection.queue q;

-- ------------------------------------------------------------------ negotiation
create view analytics.src_proposal with (security_invoker = true) as
select p.tenant_id, p.id as proposal_id, p.creditor_id, p.portfolio_id, p.team_id, p.channel, p.actor_type,
       p.actor_id, p.status, p.created_at
from negotiation.proposal p;

create view analytics.src_agreement with (security_invoker = true) as
select a.tenant_id, a.id as agreement_id, a.creditor_id, a.portfolio_id, a.case_id, a.team_id, a.channel, a.origin,
       a.closer_type, a.closer_id, a.status, a.formalized_at, a.ended_at, a.renegotiation_number, a.total_amount,
       a.negotiated_amount, a.discount_amount, a.installment_count
from negotiation.agreement a;

create view analytics.src_installment with (security_invoker = true) as
select i.tenant_id, i.agreement_id, i.number, i.due_date, i.amount, i.paid_amount, i.status, i.paid_at
from negotiation.agreement_installment i;

-- ------------------------------------------------------------------- settlement
-- Recebimentos: PAYMENT (valor aplicado, positivo) e REVERSAL (compensação, negativo).
-- competence_date = data do fato (pagamento ou estorno).
create view analytics.src_receipt with (security_invoker = true) as
select r.tenant_id, r.id as receipt_id, r.kind, r.payment_id, r.agreement_id, r.creditor_id, r.portfolio_id,
       r.amount, r.days_past_due, r.paid_on, r.competence_date
from settlement.receipt r;

-- ---------------------------------------------------------------------- payment
create view analytics.src_payment_instruction with (security_invoker = true) as
select i.tenant_id, i.id as instruction_id, i.creditor_id, i.type, i.status, i.failure_code, i.created_at, i.updated_at
from payment.payment_instruction i;

create view analytics.src_divergence with (security_invoker = true) as
select d.tenant_id, d.id as divergence_id, d.creditor_id, d.type, d.status, d.amount, d.created_at, d.resolved_at
from payment.divergence d;

create view analytics.src_statement_import with (security_invoker = true) as
select s.tenant_id, s.id as import_id, s.creditor_id, s.status, s.failure_code, s.created_at, s.started_at,
       s.finished_at, s.total_lines
from payment.statement_import s;

create view analytics.src_statement_line with (security_invoker = true) as
select l.tenant_id, l.import_id, s.creditor_id, l.status, l.created_at
from payment.statement_line l
join payment.statement_import s on s.id = l.import_id;

-- ---------------------------------------------------------------- communication
create view analytics.src_message with (security_invoker = true) as
select m.tenant_id, m.id as message_id, c.creditor_id, m.channel, m.direction, m.status, m.failure_code,
       m.queued_at, m.failed_at, m.created_at
from communication.message m
join communication.conversation c on c.id = m.conversation_id;

-- -------------------------------------------------------------------- telephony
create view analytics.src_call with (security_invoker = true) as
select c.tenant_id, c.id as call_id, c.creditor_id, c.status, c.failure_code, c.created_at
from telephony.phone_call c;

-- -------------------------------------------------------------------- ingestion
create view analytics.src_import_batch with (security_invoker = true) as
select b.tenant_id, b.id as batch_id, b.creditor_id, b.portfolio_id, b.source, b.mode, b.status, b.created_at,
       b.started_at, b.finished_at, b.rows_read, b.rows_with_error
from ingestion.import_batch b;

-- ----------------------------------------------------------------------- shared
-- Fila de jobs é global (sem RLS): o filtro pela assessoria corrente é parte da view.
create view analytics.src_processing_job with (security_invoker = true) as
select j.tenant_id, j.type, j.status, j.attempts, j.created_at, j.finished_at
from shared.processing_job j
where j.tenant_id = shared.current_tenant_id();

-- Log de negócio: só os tipos que não têm tabela própria como fonte (catálogo em
-- docs/tecnico/logs-e-metricas.md). conversation.sla_breached = espera do devedor que
-- estourou o limite de primeira resposta (varredura do communication).
create view analytics.src_business_event with (security_invoker = true) as
select e.tenant_id, e.event_type, e.occurred_at, e.creditor_id, e.portfolio_id, e.channel,
       e.attributes ->> 'owner' as owner
from shared.business_event e
where e.event_type in ('conversation.sla_breached');

-- -------------------------------------------------------------------- incentive
-- Fatos dos negociadores (humanos), reutilizados em vez de duplicados.
create view analytics.src_performance_fact with (security_invoker = true) as
select f.tenant_id, f.fact_date, f.user_id, f.creditor_id, f.portfolio_id, f.channel, f.attempts,
       f.effective_contacts, f.answered_calls, f.talk_seconds
from incentive.performance_fact f;

create view analytics.src_worked_case with (security_invoker = true) as
select w.tenant_id, w.fact_date, w.user_id, w.case_id, w.creditor_id, w.portfolio_id, w.channel
from incentive.worked_case_day w;

create view analytics.src_logged_time with (security_invoker = true) as
select l.tenant_id, l.fact_date, l.user_id, l.seconds
from incentive.logged_time_daily l;

-- Retrato do saldo elegível atribuível por caso (denominador da taxa de recuperação).
create view analytics.src_case_snapshot with (security_invoker = true) as
select s.tenant_id, s.case_id, s.effective_at, s.creditor_id, s.portfolio_id, s.assignee_id, s.team_id,
       s.eligible_balance
from incentive.case_snapshot s;

-- Só o valor a pagar por período (custo por real recuperado, sempre agregado com ≥ 3 extratos).
create view analytics.src_commission_statement with (security_invoker = true) as
select s.tenant_id, s.period_start, s.beneficiary_id, s.status, s.payable_amount
from incentive.commission_statement s;

grant select on all tables in schema analytics to app_runtime;

-- =============================================================================
-- Reconstrução dos snapshots da assessoria corrente (chamadas pelo job, como
-- app_runtime, dentro da transação do tenant). Devolvem a quantidade de linhas.
-- =============================================================================
create or replace function analytics.require_tenant() returns uuid
    language plpgsql stable
as $$
declare
    t uuid := shared.current_tenant_id();
begin
    if t is null then
        raise exception 'analytics: assessoria não definida na transação' using errcode = 'insufficient_privilege';
    end if;
    return t;
end
$$;

create or replace function analytics.refresh_recovery_daily() returns bigint
    language plpgsql
as $$
declare
    n bigint;
begin
    perform analytics.require_tenant();
    delete from analytics.recovery_daily;
    insert into analytics.recovery_daily (tenant_id, fact_date, creditor_id, portfolio_id, operator_id, team_id, channel,
                                          origin, origin_group, delay_bucket, recovered_gross, reversed, receipts)
    select r.tenant_id, r.competence_date, r.creditor_id, r.portfolio_id,
           case when a.closer_type = 'USER' then a.closer_id end, a.team_id,
           coalesce(a.channel, 'UNKNOWN'), coalesce(a.origin, 'NO_AGREEMENT'), analytics.origin_group(a.origin),
           analytics.delay_bucket(r.days_past_due),
           coalesce(sum(r.amount) filter (where r.kind = 'PAYMENT'), 0),
           coalesce(-sum(r.amount) filter (where r.kind = 'REVERSAL'), 0),
           count(*) filter (where r.kind = 'PAYMENT')
    from analytics.src_receipt r
    left join analytics.src_agreement a on a.agreement_id = r.agreement_id
    group by 1, 2, 3, 4, 5, 6, 7, 8, 9, 10;
    get diagnostics n = row_count;
    return n;
end
$$;

create or replace function analytics.refresh_agreement_cohort(p_zone text) returns bigint
    language plpgsql
as $$
declare
    n bigint;
begin
    perform analytics.require_tenant();
    delete from analytics.agreement_cohort;
    insert into analytics.agreement_cohort (tenant_id, agreement_id, creditor_id, portfolio_id, case_id, operator_id,
                                            team_id, channel, origin, origin_group, formalized_at, formalized_on, status,
                                            ended_at, broken_at, renegotiation, total_amount, negotiated_amount,
                                            discount_amount, installment_count, first_installment_paid_at,
                                            first_receipt_on, paid_net)
    select a.tenant_id, a.agreement_id, a.creditor_id, a.portfolio_id, a.case_id,
           case when a.closer_type = 'USER' then a.closer_id end, a.team_id, coalesce(a.channel, 'UNKNOWN'), a.origin,
           analytics.origin_group(a.origin), a.formalized_at, (a.formalized_at at time zone p_zone)::date, a.status,
           a.ended_at, case when a.status = 'BROKEN' then a.ended_at end, a.renegotiation_number > 0, a.total_amount,
           a.negotiated_amount, a.discount_amount, a.installment_count,
           (select i.paid_at from analytics.src_installment i
            where i.agreement_id = a.agreement_id and i.number = 1 and i.status = 'PAID'),
           (select min(r.competence_date) from analytics.src_receipt r
            where r.agreement_id = a.agreement_id and r.kind = 'PAYMENT'),
           coalesce((select sum(r.amount) from analytics.src_receipt r where r.agreement_id = a.agreement_id), 0)
    from analytics.src_agreement a;
    get diagnostics n = row_count;
    return n;
end
$$;

create or replace function analytics.refresh_portfolio_position(p_reference date) returns bigint
    language plpgsql
as $$
declare
    n bigint;
begin
    perform analytics.require_tenant();
    delete from analytics.portfolio_position;
    insert into analytics.portfolio_position (tenant_id, reference_date, creditor_id, portfolio_id, delay_bucket,
                                              open_titles, portfolio_amount, overdue_titles, overdue_amount)
    select t.tenant_id, p_reference, t.creditor_id, t.portfolio_id, analytics.delay_bucket(p_reference - t.due_date),
           count(*), sum(t.open_balance),
           count(*) filter (where t.due_date < p_reference),
           coalesce(sum(t.open_balance) filter (where t.due_date < p_reference), 0)
    from analytics.src_title t
    where t.status in ('OPEN', 'SUSPENDED') and t.open_balance > 0
    group by 1, 2, 3, 4, 5;
    get diagnostics n = row_count;
    return n;
end
$$;

create or replace function analytics.refresh_case_position(p_reference date) returns bigint
    language plpgsql
as $$
declare
    n bigint;
begin
    perform analytics.require_tenant();
    delete from analytics.case_position;
    insert into analytics.case_position (tenant_id, reference_date, creditor_id, portfolio_id, delay_bucket, status,
                                         eligible, owner_type, queue_id, cases, open_balance, eligible_balance)
    select c.tenant_id, p_reference, c.creditor_id, c.portfolio_id,
           analytics.delay_bucket(p_reference - c.oldest_due_date), c.status, c.eligible, c.owner_type, c.queue_id,
           count(*), sum(c.open_balance), sum(c.eligible_balance)
    from analytics.src_case c
    where c.status <> 'CLOSED'
    group by 1, 2, 3, 4, 5, 6, 7, 8, 9;
    get diagnostics n = row_count;
    return n;
end
$$;

grant execute on function analytics.require_tenant(), analytics.refresh_recovery_daily(),
    analytics.refresh_agreement_cohort(text), analytics.refresh_portfolio_position(date),
    analytics.refresh_case_position(date) to app_runtime;
