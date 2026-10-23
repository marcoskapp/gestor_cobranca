-- =============================================================================
-- Retornos cumpridos e promessas cumpridas (docs/design/remuneracao-variavel.md
-- §2.1, §2.2 e §10.6), a partir de CallbackStatusChanged e PromiseStatusChanged
-- publicados pelo collection.
--
-- performance_fact recebe medidas aditivas; as situações por compromisso ficam em
-- tabelas próprias, porque os eventos do mesmo retorno/promessa chegam em qualquer
-- ordem e cada mudança de situação gera, no fato diário, a diferença entre a
-- contribuição nova e a anterior (o fato é sempre a soma das situações atuais).
-- =============================================================================

-- Retornos: dia do horário combinado (fuso de operação) × responsável.
--   callbacks_scheduled  agendados (exclui cancelados e substituídos por reagendamento)
--   callbacks_kept       cumpridos até o fim da tolerância
--   callbacks_missed     atrasados (inclui os cumpridos depois de vencida a tolerância)
-- Promessas:
--   promises_registered  registradas (dia do registro × quem registrou)
--   promises_due         vencidas = prazo encerrado com desfecho (cumpridas + quebradas), dia da data prometida
--   promises_kept        cumpridas (dia da data prometida)
--   promises_broken      quebradas (dia da data prometida)
alter table incentive.performance_fact
    add column callbacks_scheduled  integer not null default 0,
    add column callbacks_kept       integer not null default 0,
    add column callbacks_missed     integer not null default 0,
    add column promises_registered  integer not null default 0,
    add column promises_due         integer not null default 0,
    add column promises_kept        integer not null default 0,
    add column promises_broken      integer not null default 0,
    add constraint ck_performance_fact_commitments check (
        callbacks_scheduled >= 0 and callbacks_kept >= 0 and callbacks_missed >= 0
        and callbacks_kept + callbacks_missed <= callbacks_scheduled
        and promises_registered >= 0 and promises_due >= 0 and promises_kept >= 0 and promises_broken >= 0
        and promises_kept + promises_broken = promises_due);

-- Compromisso de retorno: tarefa × horário combinado (o reagendamento cria outro).
create table incentive.callback_commitment (
    tenant_id      uuid          not null references organization.tenant (id),
    task_id        uuid          not null,
    due_at         timestamptz   not null,
    case_id        uuid,
    creditor_id    uuid,
    portfolio_id   uuid,
    assignee_id    uuid,
    status         varchar(20)   not null,
    scheduled_at   timestamptz,
    last_event_at  timestamptz   not null,
    primary key (tenant_id, task_id, due_at),
    constraint ck_callback_commitment_status check (status in ('SCHEDULED', 'SUPERSEDED', 'CANCELLED', 'MISSED',
                                                               'COMPLETED_LATE', 'KEPT'))
);
create index ix_callback_commitment_assignee on incentive.callback_commitment (tenant_id, assignee_id, due_at);
select shared.enable_tenant_rls('incentive.callback_commitment');

-- Situação atual da promessa (vale o evento mais recente; estorno reabre a cumprida).
create table incentive.promise_outcome (
    tenant_id      uuid          not null references organization.tenant (id),
    promise_id     uuid          not null,
    case_id        uuid,
    creditor_id    uuid,
    portfolio_id   uuid,
    operator_id    uuid,
    due_date       date,
    status         varchar(20)   not null,
    status_at      timestamptz   not null,
    registered_at  timestamptz,
    primary key (tenant_id, promise_id),
    constraint ck_promise_outcome_status check (status in ('PENDING', 'KEPT', 'BROKEN', 'CANCELLED'))
);
create index ix_promise_outcome_operator on incentive.promise_outcome (tenant_id, operator_id, due_date);
select shared.enable_tenant_rls('incentive.promise_outcome');

grant select, insert, update on incentive.callback_commitment, incentive.promise_outcome to app_runtime;
revoke delete on incentive.callback_commitment, incentive.promise_outcome from app_runtime;
