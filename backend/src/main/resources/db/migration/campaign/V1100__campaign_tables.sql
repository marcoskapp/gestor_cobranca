-- =============================================================================
-- Módulo campaign (M04, F13, F25, RB07): segmentos salvos, campanhas com régua
-- versionada, inscrições (execução da campanha para um caso/devedor), execuções de
-- etapa com chave idempotente, atribuição de resultados, testes A/B e o read model
-- de audiência (projeção própria dos casos de cobrança, alimentada por eventos e pelas
-- interfaces públicas do collection — nunca por leitura das tabelas de outro módulo).
--
-- Nenhuma tabela guarda dado pessoal do devedor: somente ids, enums, datas e valores.
-- Execuções de etapa e atribuições são append-only (a execução só atualiza a situação
-- da mensagem enviada); versões publicadas da régua são imutáveis (gatilho).
-- Casos e devedores são referenciados por id, sem chave estrangeira (outros módulos).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Segmento: filtros combináveis (jsonb tipado pela aplicação), por credor ou da
-- assessoria (creditor_id nulo).
-- -----------------------------------------------------------------------------
create table campaign.segment (
    id            uuid          not null primary key,
    tenant_id     uuid          not null references organization.tenant (id),
    version       bigint        not null,
    created_at    timestamptz   not null,
    created_by    uuid,
    updated_at    timestamptz   not null,
    updated_by    uuid,
    creditor_id   uuid          references organization.creditor (id),
    code          varchar(60)   not null,
    name          varchar(120)  not null,
    description   varchar(500),
    criteria      jsonb         not null,
    active        boolean       not null,
    constraint uk_segment_code unique (tenant_id, code)
);
create index ix_segment_creditor on campaign.segment (tenant_id, creditor_id);
select shared.enable_tenant_rls('campaign.segment');

-- -----------------------------------------------------------------------------
-- Campanha: credor (e carteira opcional), segmento, prioridade e grupo de
-- exclusividade (controle de concorrência entre campanhas), parâmetros de execução.
-- -----------------------------------------------------------------------------
create table campaign.campaign (
    id                         uuid          not null primary key,
    tenant_id                  uuid          not null references organization.tenant (id),
    version                    bigint        not null,
    created_at                 timestamptz   not null,
    created_by                 uuid,
    updated_at                 timestamptz   not null,
    updated_by                 uuid,
    code                       varchar(60)   not null,
    name                       varchar(120)  not null,
    description                varchar(500),
    creditor_id                uuid          not null references organization.creditor (id),
    portfolio_id               uuid          references organization.portfolio (id),
    segment_id                 uuid          not null references campaign.segment (id),
    purpose                    varchar(30)   not null,
    status                     varchar(20)   not null,
    priority                   integer       not null,
    exclusivity_group          varchar(60),
    reenroll_after_days        integer,
    attribution_window_days    integer       not null,
    payment_pause_days         integer,
    max_deferral_hours         integer       not null,
    exit_when_out_of_segment   boolean       not null,
    activated_at               timestamptz,
    activated_by               uuid,
    paused_at                  timestamptz,
    pause_note                 varchar(300),
    finished_at                timestamptz,
    constraint uk_campaign_code unique (tenant_id, code),
    constraint ck_campaign_purpose check (purpose in ('EXTRAJUDICIAL_COLLECTION', 'REMINDER', 'AGREEMENT_FOLLOW_UP',
                                                      'INFORMATIVE')),
    constraint ck_campaign_status check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'FINISHED')),
    constraint ck_campaign_values check (priority between 0 and 1000
                                         and (reenroll_after_days is null or reenroll_after_days between 1 and 3650)
                                         and attribution_window_days between 0 and 90
                                         and (payment_pause_days is null or payment_pause_days between 1 and 180)
                                         and max_deferral_hours between 0 and 168)
);
create index ix_campaign_status on campaign.campaign (tenant_id, status);
create index ix_campaign_creditor on campaign.campaign (tenant_id, creditor_id);
select shared.enable_tenant_rls('campaign.campaign');

-- -----------------------------------------------------------------------------
-- Régua versionada da campanha: etapas em jsonb tipado. Um rascunho e uma versão
-- publicada por campanha; a publicada é imutável (inscrições guardam a versão).
-- -----------------------------------------------------------------------------
create table campaign.cadence (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    campaign_id     uuid          not null references campaign.campaign (id),
    version_number  integer       not null,
    status          varchar(20)   not null,
    definition      jsonb         not null,
    published_at    timestamptz,
    published_by    uuid,
    retired_at      timestamptz,
    constraint uk_cadence_version unique (tenant_id, campaign_id, version_number),
    constraint ck_cadence_status check (status in ('DRAFT', 'PUBLISHED', 'RETIRED'))
);
create unique index uk_cadence_draft on campaign.cadence (tenant_id, campaign_id) where status = 'DRAFT';
create unique index uk_cadence_published on campaign.cadence (tenant_id, campaign_id) where status = 'PUBLISHED';
select shared.enable_tenant_rls('campaign.cadence');

create or replace function campaign.cadence_definition_immutable() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'campaign.cadence não admite exclusão' using errcode = '42501';
    end if;
    if old.status <> 'DRAFT' and (new.definition is distinct from old.definition
                                  or new.version_number <> old.version_number
                                  or new.campaign_id <> old.campaign_id) then
        raise exception 'versão de régua publicada é imutável' using errcode = '42501';
    end if;
    if old.status = 'RETIRED' and new.status <> 'RETIRED' then
        raise exception 'versão de régua retirada não volta a vigorar' using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_cadence_definition_immutable before update or delete on campaign.cadence
    for each row execute function campaign.cadence_definition_immutable();

-- -----------------------------------------------------------------------------
-- Teste A/B (F25): variantes (jsonb), grupo de controle (%), sal da atribuição
-- estável por devedor, horizonte de observação e amostra mínima por braço.
-- -----------------------------------------------------------------------------
create table campaign.experiment (
    id                   uuid          not null primary key,
    tenant_id            uuid          not null references organization.tenant (id),
    version              bigint        not null,
    created_at           timestamptz   not null,
    created_by           uuid,
    updated_at           timestamptz   not null,
    updated_by           uuid,
    campaign_id          uuid          not null references campaign.campaign (id),
    name                 varchar(120)  not null,
    hypothesis           varchar(500),
    primary_metric       varchar(30)   not null,
    status               varchar(20)   not null,
    control_share        numeric(9,4)  not null,
    variants             jsonb         not null,
    salt                 varchar(64)   not null,
    observation_days     integer       not null,
    min_sample_per_arm   integer       not null,
    started_at           timestamptz,
    started_by           uuid,
    stopped_at           timestamptz,
    stopped_by           uuid,
    stop_reason          varchar(300),
    constraint ck_experiment_status check (status in ('DRAFT', 'RUNNING', 'STOPPED')),
    constraint ck_experiment_metric check (primary_metric in ('EFFECTIVE_CONTACT', 'REPLY', 'AGREEMENT', 'PAYMENT',
                                                              'RECOVERED_PER_DEBTOR')),
    constraint ck_experiment_values check (control_share >= 0 and control_share <= 50
                                           and observation_days between 1 and 180
                                           and min_sample_per_arm between 10 and 1000000)
);
create unique index uk_experiment_open on campaign.experiment (tenant_id, campaign_id) where status in ('DRAFT', 'RUNNING');
select shared.enable_tenant_rls('campaign.experiment');

create or replace function campaign.experiment_frozen_after_start() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'campaign.experiment não admite exclusão' using errcode = '42501';
    end if;
    if old.status <> 'DRAFT' and (new.variants is distinct from old.variants
                                  or new.salt <> old.salt
                                  or new.control_share <> old.control_share
                                  or new.observation_days <> old.observation_days) then
        raise exception 'experimento iniciado não pode ter variantes, sal, controle ou horizonte alterados'
            using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_experiment_frozen_after_start before update or delete on campaign.experiment
    for each row execute function campaign.experiment_frozen_after_start();

-- atribuição estável: um braço por devedor e experimento, nunca alterado
create table campaign.experiment_assignment (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    experiment_id   uuid          not null references campaign.experiment (id),
    debtor_id       uuid          not null,
    arm             varchar(40)   not null,
    control         boolean       not null,
    bucket          integer       not null,
    constraint uk_experiment_assignment unique (tenant_id, experiment_id, debtor_id),
    constraint ck_experiment_assignment_bucket check (bucket between 0 and 9999)
);
select shared.enable_tenant_rls('campaign.experiment_assignment');
create trigger trg_experiment_assignment_append_only before update or delete on campaign.experiment_assignment
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Inscrição: execução da campanha para um caso (devedor × carteira), com a versão da
-- régua fixada, próxima etapa, pausa (motivo e retomada), fim e grupo de exclusividade.
-- Concorrência: uma inscrição em andamento por campanha e caso, e uma por devedor e
-- grupo de exclusividade (índices únicos parciais + lock consultivo por devedor).
-- -----------------------------------------------------------------------------
create table campaign.enrollment (
    id                     uuid          not null primary key,
    tenant_id              uuid          not null references organization.tenant (id),
    version                bigint        not null,
    created_at             timestamptz   not null,
    created_by             uuid,
    updated_at             timestamptz   not null,
    updated_by             uuid,
    campaign_id            uuid          not null references campaign.campaign (id),
    cadence_id             uuid          not null references campaign.cadence (id),
    case_id                uuid          not null,
    debtor_id              uuid          not null,
    creditor_id            uuid          not null references organization.creditor (id),
    portfolio_id           uuid          not null references organization.portfolio (id),
    exclusivity_group      varchar(60),
    priority               integer       not null,
    experiment_id          uuid          references campaign.experiment (id),
    variant_code           varchar(40),
    control                boolean       not null,
    status                 varchar(20)   not null,
    enrolled_at            timestamptz   not null,
    next_step_code         varchar(40),
    next_step_iteration    integer       not null,
    next_run_at            timestamptz,
    step_due_at            timestamptz,
    pending_reply_action   varchar(30),
    last_step_code         varchar(40),
    last_step_at           timestamptz,
    last_message_id        uuid,
    steps_executed         integer       not null,
    steps_skipped          integer       not null,
    step_runs              integer       not null,
    pause_reason           varchar(30),
    paused_at              timestamptz,
    resume_at              timestamptz,
    end_reason             varchar(40),
    ended_at               timestamptz,
    observe_until          timestamptz,
    replied_at             timestamptz,
    effective_contact_at   timestamptz,
    last_error_code        varchar(120),
    last_error_at          timestamptz,
    constraint ck_enrollment_status check (status in ('ACTIVE', 'PAUSED', 'FAILED', 'HOLDOUT', 'ENDED')),
    constraint ck_enrollment_pause check (pause_reason is null or pause_reason in (
        'PAYMENT_CONFIRMED', 'AGREEMENT_FORMALIZED', 'DISPUTE_OPENED', 'CONTACT_RESTRICTED', 'CASE_SUSPENDED',
        'CASE_LEGAL', 'MANUAL')),
    constraint ck_enrollment_reply_action check (pending_reply_action is null
                                                 or pending_reply_action in ('HAND_TO_ASSISTANT', 'MOVE_TO_QUEUE')),
    constraint ck_enrollment_paused check ((status = 'PAUSED') = (pause_reason is not null)),
    constraint ck_enrollment_ended check ((status = 'ENDED') = (end_reason is not null)),
    constraint ck_enrollment_control check (not control or (status in ('HOLDOUT', 'ENDED') and exclusivity_group is null))
);
create unique index uk_enrollment_case_running on campaign.enrollment (tenant_id, campaign_id, case_id)
    where status in ('ACTIVE', 'PAUSED', 'FAILED', 'HOLDOUT');
create unique index uk_enrollment_exclusive on campaign.enrollment (tenant_id, debtor_id, exclusivity_group)
    where exclusivity_group is not null and status in ('ACTIVE', 'PAUSED', 'FAILED');
create index ix_enrollment_campaign on campaign.enrollment (tenant_id, campaign_id, status);
create index ix_enrollment_debtor on campaign.enrollment (tenant_id, debtor_id, creditor_id);
create index ix_enrollment_case on campaign.enrollment (tenant_id, case_id);
create index ix_enrollment_due on campaign.enrollment (tenant_id, next_run_at) where status = 'ACTIVE';
create index ix_enrollment_resume on campaign.enrollment (tenant_id, resume_at) where status = 'PAUSED';
select shared.enable_tenant_rls('campaign.enrollment');

-- devedores do segmento não inscritos por concorrência (painel), uma linha por campanha e caso
create table campaign.enrollment_suppression (
    tenant_id               uuid          not null references organization.tenant (id),
    campaign_id             uuid          not null references campaign.campaign (id),
    case_id                 uuid          not null,
    reason                  varchar(40)   not null,
    blocking_campaign_id    uuid,
    first_seen_at           timestamptz   not null,
    last_seen_at            timestamptz   not null,
    occurrences             integer       not null,
    primary key (tenant_id, campaign_id, case_id),
    constraint ck_suppression_reason check (reason in ('CONCURRENT_CAMPAIGN'))
);
select shared.enable_tenant_rls('campaign.enrollment_suppression');

-- -----------------------------------------------------------------------------
-- Execução de etapa: chave idempotente (inscrição × etapa × iteração) única entre as
-- execuções efetivas e puladas; falhas ficam como tentativas (o reprocessamento usa a
-- mesma chave, também enviada ao communication, que não duplica a mensagem).
-- -----------------------------------------------------------------------------
create table campaign.step_execution (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    enrollment_id     uuid          not null references campaign.enrollment (id),
    campaign_id       uuid          not null references campaign.campaign (id),
    cadence_id        uuid          not null references campaign.cadence (id),
    step_code         varchar(40)   not null,
    iteration         integer       not null,
    action_type       varchar(30)   not null,
    channel           varchar(20),
    outcome           varchar(20)   not null,
    reason            varchar(200),
    reason_detail     varchar(500),
    message_id        uuid,
    message_status    varchar(20),
    task_id           uuid,
    variant_code      varchar(40),
    executed_at       timestamptz   not null,
    idempotency_key   varchar(150)  not null,
    constraint ck_step_execution_outcome check (outcome in ('EXECUTED', 'SKIPPED', 'FAILED')),
    constraint ck_step_execution_action check (action_type in ('SEND_MESSAGE', 'CREATE_TASK', 'HAND_TO_ASSISTANT',
                                                               'MOVE_TO_QUEUE', 'OFFER'))
);
create unique index uk_step_execution_key on campaign.step_execution (tenant_id, idempotency_key)
    where outcome in ('EXECUTED', 'SKIPPED');
create index ix_step_execution_enrollment on campaign.step_execution (tenant_id, enrollment_id);
create index ix_step_execution_campaign on campaign.step_execution (tenant_id, campaign_id, outcome);
create index ix_step_execution_message on campaign.step_execution (tenant_id, message_id) where message_id is not null;
select shared.enable_tenant_rls('campaign.step_execution');

-- somente a situação da mensagem (e os metadados) muda depois do registro
create or replace function campaign.step_execution_status_only() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'campaign.step_execution é append-only' using errcode = '42501';
    end if;
    if new.enrollment_id <> old.enrollment_id or new.step_code <> old.step_code or new.iteration <> old.iteration
       or new.outcome <> old.outcome or new.idempotency_key <> old.idempotency_key
       or new.executed_at <> old.executed_at or new.action_type <> old.action_type
       or new.message_id is distinct from old.message_id or new.reason is distinct from old.reason then
        raise exception 'campaign.step_execution só admite atualizar a situação da mensagem' using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_step_execution_status_only before update or delete on campaign.step_execution
    for each row execute function campaign.step_execution_status_only();

-- -----------------------------------------------------------------------------
-- Atribuição de resultados à inscrição (resposta, contato efetivo, acordo, pagamento
-- e estorno), dentro da janela de atribuição; base do painel e dos testes A/B.
-- -----------------------------------------------------------------------------
create table campaign.attribution (
    id                uuid          not null primary key,
    tenant_id         uuid          not null references organization.tenant (id),
    version           bigint        not null,
    created_at        timestamptz   not null,
    created_by        uuid,
    updated_at        timestamptz   not null,
    updated_by        uuid,
    enrollment_id     uuid          not null references campaign.enrollment (id),
    campaign_id       uuid          not null references campaign.campaign (id),
    debtor_id         uuid          not null,
    kind              varchar(30)   not null,
    reference_type    varchar(40)   not null,
    reference_id      uuid          not null,
    amount            numeric(19,2),
    occurred_at       timestamptz   not null,
    experiment_id     uuid,
    variant_code      varchar(40),
    control           boolean       not null,
    constraint uk_attribution unique (tenant_id, enrollment_id, kind, reference_id),
    constraint ck_attribution_kind check (kind in ('REPLY', 'EFFECTIVE_CONTACT', 'AGREEMENT', 'PAYMENT',
                                                   'PAYMENT_REVERSAL'))
);
create index ix_attribution_campaign on campaign.attribution (tenant_id, campaign_id, kind);
create index ix_attribution_experiment on campaign.attribution (tenant_id, experiment_id) where experiment_id is not null;
select shared.enable_tenant_rls('campaign.attribution');
create trigger trg_attribution_append_only before update or delete on campaign.attribution
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Read model de audiência: projeção própria dos casos de cobrança (posição, situação,
-- elegibilidade, histórico de contato, score), atualizada a partir de CaseUseCases
-- ao consumir os eventos do caso e por reconciliação. Base dos segmentos.
-- -----------------------------------------------------------------------------
create table campaign.audience_case (
    tenant_id                  uuid           not null references organization.tenant (id),
    case_id                    uuid           not null,
    debtor_id                  uuid           not null,
    creditor_id                uuid           not null,
    portfolio_id               uuid           not null,
    status                     varchar(20)    not null,
    resume_status              varchar(20),
    eligible                   boolean        not null,
    eligibility_reasons        varchar(500)   not null,
    contactable_channels       varchar(100)   not null,
    open_balance               numeric(19,2)  not null,
    eligible_balance           numeric(19,2)  not null,
    oldest_due_date            date,
    owner_type                 varchar(20)    not null,
    interaction_count          integer        not null,
    effective_contact_count    integer        not null,
    last_interaction_at        timestamptz,
    last_effective_contact_at  timestamptz,
    score                      integer,
    broken_agreements          integer        not null default 0,
    case_version               bigint         not null,
    refreshed_at               timestamptz    not null,
    primary key (tenant_id, case_id)
);
create index ix_audience_creditor on campaign.audience_case (tenant_id, creditor_id, status);
create index ix_audience_debtor on campaign.audience_case (tenant_id, debtor_id);
select shared.enable_tenant_rls('campaign.audience_case');

grant select, insert, update, delete on all tables in schema campaign to app_runtime;
-- segmentos, campanhas, réguas, experimentos e inscrições são desativados/encerrados, nunca removidos
revoke delete on campaign.segment, campaign.campaign, campaign.cadence, campaign.experiment, campaign.enrollment,
    campaign.step_execution from app_runtime;
revoke update, delete on campaign.experiment_assignment, campaign.attribution from app_runtime;
