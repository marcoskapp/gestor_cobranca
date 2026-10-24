-- =============================================================================
-- Módulo assistant (F24): política do assistente versionada por assessoria,
-- credor ou carteira; sessão do assistente por conversa; trilha append-only de
-- cada turno (entrada, contexto enviado ao modelo, resposta, guardrails, custo,
-- latência, versões de política e prompt) e das chamadas de ferramenta;
-- amostra de revisão humana; fatos de acordo para as métricas de qualidade.
--
-- Texto do devedor, contexto e respostas ficam criptografados (*_encrypted,
-- AES-256-GCM pela aplicação) e são descartados pela retenção MESSAGE_CONTENT
-- e pela anonimização do devedor — única alteração aceita na trilha (gatilho).
-- Ver docs/tecnico/desenho/assistente.md.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Política do assistente. Escopo: assessoria (credor e carteira nulos), credor
-- ou carteira. policy_version é atribuída na publicação (sequência por código).
-- -----------------------------------------------------------------------------
create table assistant.assistant_policy (
    id                       uuid          not null primary key,
    tenant_id                uuid          not null references organization.tenant (id),
    version                  bigint        not null,
    created_at               timestamptz   not null,
    created_by               uuid,
    updated_at               timestamptz   not null,
    updated_by               uuid,
    creditor_id              uuid          references organization.creditor (id),
    portfolio_id             uuid          references organization.portfolio (id),
    code                     varchar(40)   not null,
    name                     varchar(120)  not null,
    policy_version           integer,
    status                   varchar(20)   not null,
    mode                     varchar(30)   not null,
    settings                 jsonb         not null,
    settings_schema_version  integer       not null,
    prompt_code              varchar(40)   not null,
    prompt_version           integer       not null,
    published_at             timestamptz,
    published_by             uuid,
    retired_at               timestamptz,
    retire_reason            varchar(300),
    constraint ck_assistant_policy_status check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    constraint ck_assistant_policy_mode check (mode in ('OFF', 'SHADOW', 'COPILOT', 'AUTONOMOUS_WITH_APPROVAL',
                                                        'AUTONOMOUS')),
    constraint ck_assistant_policy_scope check (portfolio_id is null or creditor_id is not null),
    constraint ck_assistant_policy_version check ((status = 'DRAFT') = (policy_version is null)),
    constraint ck_assistant_policy_published check (status = 'DRAFT' or published_at is not null),
    constraint ck_assistant_policy_retired check ((status = 'RETIRED') = (retired_at is not null))
);
create unique index uk_assistant_policy_version on assistant.assistant_policy (tenant_id, code, policy_version)
    where policy_version is not null;
create unique index uk_assistant_policy_draft on assistant.assistant_policy (tenant_id, code) where status = 'DRAFT';
create unique index uk_assistant_policy_published on assistant.assistant_policy (tenant_id, code)
    where status = 'PUBLISHED';
create unique index uk_assistant_policy_published_scope on assistant.assistant_policy (
    tenant_id, coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid)) where status = 'PUBLISHED';
create index ix_assistant_policy_status on assistant.assistant_policy (tenant_id, status);
select shared.enable_tenant_rls('assistant.assistant_policy');

-- versão publicada é imutável: só a aposentadoria (status, data e motivo) muda
create or replace function assistant.policy_published_immutable() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Política do assistente não pode ser removida' using errcode = 'P0001';
    end if;
    if old.status <> 'DRAFT' and (
           new.settings is distinct from old.settings or new.mode is distinct from old.mode
           or new.prompt_code is distinct from old.prompt_code or new.prompt_version is distinct from old.prompt_version
           or new.policy_version is distinct from old.policy_version or new.code is distinct from old.code
           or new.creditor_id is distinct from old.creditor_id or new.portfolio_id is distinct from old.portfolio_id
           or new.published_at is distinct from old.published_at or new.published_by is distinct from old.published_by
           or new.name is distinct from old.name
           or (old.status = 'RETIRED' and new.status <> 'RETIRED')
           or (old.status = 'PUBLISHED' and new.status not in ('PUBLISHED', 'RETIRED'))) then
        raise exception 'Política do assistente publicada é imutável' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_assistant_policy_immutable before update or delete on assistant.assistant_policy
    for each row execute function assistant.policy_published_immutable();

-- -----------------------------------------------------------------------------
-- Sessão do assistente por conversa (estado operacional, atualizável).
-- -----------------------------------------------------------------------------
create table assistant.assistant_session (
    id                         uuid           not null primary key,
    tenant_id                  uuid           not null references organization.tenant (id),
    version                    bigint         not null,
    created_at                 timestamptz    not null,
    created_by                 uuid,
    updated_at                 timestamptz    not null,
    updated_by                 uuid,
    conversation_id            uuid           not null references communication.conversation (id),
    creditor_id                uuid           not null references organization.creditor (id),
    debtor_id                  uuid           not null references debt.debtor (id),
    portfolio_id               uuid           references organization.portfolio (id),
    case_id                    uuid,
    status                     varchar(20)    not null,
    episode                    integer        not null,
    turns                      integer        not null,
    total_turns                integer        not null,
    misunderstandings          integer        not null,
    introduced                 boolean        not null,
    identity_status            varchar(20)    not null,
    identity_attempts          integer        not null,
    identity_verified_at       timestamptz,
    memory                     jsonb          not null,
    input_tokens               bigint         not null,
    output_tokens              bigint         not null,
    cost_amount                numeric(19, 6) not null,
    cost_currency              varchar(3),
    last_processed_inbound_at  timestamptz,
    pending_turn_id            uuid,
    pending_since              timestamptz,
    handoff_reason             varchar(40),
    handed_off_at              timestamptz,
    last_policy_id             uuid           references assistant.assistant_policy (id),
    constraint uk_assistant_session_conversation unique (tenant_id, conversation_id),
    constraint ck_assistant_session_status check (status in ('ACTIVE', 'HANDED_OFF')),
    constraint ck_assistant_session_identity check (identity_status in ('PENDING', 'VERIFIED', 'FAILED', 'NOT_REQUIRED')),
    constraint ck_assistant_session_counts check (episode >= 1 and turns >= 0 and total_turns >= turns
                                                  and misunderstandings >= 0 and identity_attempts >= 0
                                                  and input_tokens >= 0 and output_tokens >= 0 and cost_amount >= 0),
    constraint ck_assistant_session_pending check ((pending_turn_id is null) = (pending_since is null))
);
create index ix_assistant_session_pending on assistant.assistant_session (tenant_id, pending_since)
    where pending_since is not null;
create index ix_assistant_session_debtor on assistant.assistant_session (tenant_id, debtor_id);
select shared.enable_tenant_rls('assistant.assistant_session');

-- -----------------------------------------------------------------------------
-- Turno (append-only; só o descarte do conteúdo é aceito).
-- -----------------------------------------------------------------------------
create table assistant.assistant_turn (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    session_id             uuid           not null references assistant.assistant_session (id),
    conversation_id        uuid           not null references communication.conversation (id),
    creditor_id            uuid           not null references organization.creditor (id),
    portfolio_id           uuid           references organization.portfolio (id),
    debtor_id              uuid           not null references debt.debtor (id),
    case_id                uuid,
    policy_id              uuid           references assistant.assistant_policy (id),
    policy_version         integer,
    prompt_code            varchar(40),
    prompt_version         integer,
    prompt_sha256          varchar(64),
    mode                   varchar(30)    not null,
    episode                integer        not null,
    turn_number            integer        not null,
    trigger_message_id     uuid,
    input_message_ids      jsonb          not null,
    input_text_encrypted   varchar(100000),
    context_encrypted      varchar(400000),
    context_sha256         varchar(64),
    context_version        integer        not null,
    output_text_encrypted  varchar(100000),
    outcome                varchar(30)    not null,
    handoff_reason         varchar(40),
    trigger_source         varchar(20),
    guardrails             jsonb          not null,
    intent                 varchar(30),
    confidence             numeric(5, 4),
    provider               varchar(40),
    model                  varchar(80),
    model_calls            integer        not null,
    tool_calls             integer        not null,
    input_tokens           bigint         not null,
    output_tokens          bigint         not null,
    cost_amount            numeric(19, 6) not null,
    cost_currency          varchar(3),
    model_latency_ms       bigint         not null,
    total_latency_ms       bigint         not null,
    outbound_message_id    uuid,
    draft_id               uuid,
    proposal_id            uuid,
    agreement_id           uuid,
    error_code             varchar(80),
    started_at             timestamptz    not null,
    finished_at            timestamptz    not null,
    content_purged_at      timestamptz,
    constraint ck_assistant_turn_outcome check (outcome in ('REPLIED', 'DRAFTED', 'HANDED_OFF', 'APPROVAL_REQUESTED',
                                                            'SEND_BLOCKED', 'GUARDRAIL_BLOCKED', 'SKIPPED', 'FAILED')),
    constraint ck_assistant_turn_mode check (mode in ('OFF', 'SHADOW', 'COPILOT', 'AUTONOMOUS_WITH_APPROVAL',
                                                      'AUTONOMOUS')),
    constraint ck_assistant_turn_source check (trigger_source is null or trigger_source in (
        'RULE', 'MODEL', 'GUARDRAIL', 'LIMIT', 'ENGINE', 'POLICY', 'FAILURE')),
    constraint ck_assistant_turn_counts check (model_calls >= 0 and tool_calls >= 0 and input_tokens >= 0
                                               and output_tokens >= 0 and cost_amount >= 0 and model_latency_ms >= 0
                                               and total_latency_ms >= 0),
    constraint ck_assistant_turn_confidence check (confidence is null or (confidence >= 0 and confidence <= 1)),
    constraint ck_assistant_turn_purged check (content_purged_at is null or (input_text_encrypted is null
                                               and context_encrypted is null and output_text_encrypted is null))
);
create index ix_assistant_turn_conversation on assistant.assistant_turn (tenant_id, conversation_id, started_at desc);
create index ix_assistant_turn_started on assistant.assistant_turn (tenant_id, started_at);
create index ix_assistant_turn_debtor on assistant.assistant_turn (tenant_id, debtor_id);
create index ix_assistant_turn_purge on assistant.assistant_turn (tenant_id, finished_at) where content_purged_at is null;
select shared.enable_tenant_rls('assistant.assistant_turn');

create or replace function assistant.turn_purge_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: assistant.assistant_turn' using errcode = 'P0001';
    end if;
    if new.content_purged_at is null or new.input_text_encrypted is not null or new.context_encrypted is not null
       or new.output_text_encrypted is not null
       or (to_jsonb(new) - 'input_text_encrypted' - 'context_encrypted' - 'output_text_encrypted' - 'content_purged_at')
          is distinct from
          (to_jsonb(old) - 'input_text_encrypted' - 'context_encrypted' - 'output_text_encrypted' - 'content_purged_at') then
        raise exception 'Registro imutável: assistant.assistant_turn (somente o descarte do conteúdo é permitido)'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_assistant_turn_append_only before update or delete on assistant.assistant_turn
    for each row execute function assistant.turn_purge_only();

-- -----------------------------------------------------------------------------
-- Chamadas de ferramenta do turno (append-only).
-- -----------------------------------------------------------------------------
create table assistant.turn_tool_call (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    turn_id         uuid          not null references assistant.assistant_turn (id),
    sequence        integer       not null,
    tool_name       varchar(40)   not null,
    arguments       jsonb         not null,
    result          jsonb         not null,
    outcome         varchar(20)   not null,
    error_code      varchar(80),
    reference_type  varchar(40),
    reference_id    uuid,
    duration_ms     bigint        not null,
    constraint uk_turn_tool_call unique (tenant_id, turn_id, sequence),
    constraint ck_turn_tool_call_outcome check (outcome in ('OK', 'REJECTED', 'ERROR'))
);
create index ix_turn_tool_call_turn on assistant.turn_tool_call (tenant_id, turn_id);
select shared.enable_tenant_rls('assistant.turn_tool_call');
create trigger trg_turn_tool_call_append_only before update or delete on assistant.turn_tool_call
    for each row execute function shared.forbid_mutation();

-- -----------------------------------------------------------------------------
-- Revisão humana por amostragem (uma decisão por turno).
-- -----------------------------------------------------------------------------
create table assistant.turn_review (
    id                 uuid          not null primary key,
    tenant_id          uuid          not null references organization.tenant (id),
    version            bigint        not null,
    created_at         timestamptz   not null,
    created_by         uuid,
    updated_at         timestamptz   not null,
    updated_by         uuid,
    turn_id            uuid          not null references assistant.assistant_turn (id),
    conversation_id    uuid          not null references communication.conversation (id),
    creditor_id        uuid          not null references organization.creditor (id),
    portfolio_id       uuid          references organization.portfolio (id),
    debtor_id          uuid          not null references debt.debtor (id),
    policy_id          uuid          references assistant.assistant_policy (id),
    sample_reason      varchar(20)   not null,
    status             varchar(20)   not null,
    score              integer,
    verdict            varchar(30),
    tags               jsonb         not null,
    comment_encrypted  varchar(8000),
    reviewer_id        uuid,
    reviewed_at        timestamptz,
    constraint uk_turn_review_turn unique (tenant_id, turn_id),
    constraint ck_turn_review_reason check (sample_reason in ('RANDOM', 'GUARDRAIL', 'FAILURE')),
    constraint ck_turn_review_status check (status in ('PENDING', 'REVIEWED')),
    constraint ck_turn_review_verdict check (verdict is null or verdict in ('ADEQUATE', 'NEEDS_IMPROVEMENT', 'OUT_OF_POLICY')),
    constraint ck_turn_review_decision check ((status = 'REVIEWED') = (score is not null and verdict is not null
                                              and reviewer_id is not null and reviewed_at is not null)),
    constraint ck_turn_review_score check (score is null or score between 1 and 5)
);
create index ix_turn_review_queue on assistant.turn_review (tenant_id, status, created_at);
create index ix_turn_review_debtor on assistant.turn_review (tenant_id, debtor_id);
select shared.enable_tenant_rls('assistant.turn_review');

-- decisão registrada não muda (exceto a remoção do comentário na anonimização)
create or replace function assistant.review_decided_immutable() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Revisão do assistente não pode ser removida' using errcode = 'P0001';
    end if;
    if old.status = 'REVIEWED' and (
           (to_jsonb(new) - 'comment_encrypted' - 'version' - 'updated_at' - 'updated_by')
           is distinct from (to_jsonb(old) - 'comment_encrypted' - 'version' - 'updated_at' - 'updated_by')
           or (new.comment_encrypted is not null and new.comment_encrypted is distinct from old.comment_encrypted)) then
        raise exception 'Revisão do assistente já decidida é imutável' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_turn_review_immutable before update or delete on assistant.turn_review
    for each row execute function assistant.review_decided_immutable();

-- -----------------------------------------------------------------------------
-- Fatos de acordo para as métricas de qualidade (assistente × humanos).
-- -----------------------------------------------------------------------------
create table assistant.agreement_fact (
    id                   uuid           not null primary key,
    tenant_id            uuid           not null references organization.tenant (id),
    version              bigint         not null,
    created_at           timestamptz    not null,
    created_by           uuid,
    updated_at           timestamptz    not null,
    updated_by           uuid,
    agreement_id         uuid           not null,
    creditor_id          uuid           not null references organization.creditor (id),
    portfolio_id         uuid           references organization.portfolio (id),
    case_id              uuid,
    origin               varchar(40),
    assistant_policy_id  uuid,
    amount               numeric(19, 2),
    formalized_at        timestamptz,
    status               varchar(20)    not null,
    broken_at            timestamptz,
    status_at            timestamptz    not null,
    constraint uk_agreement_fact unique (tenant_id, agreement_id)
);
create index ix_agreement_fact_formalized on assistant.agreement_fact (tenant_id, formalized_at);
select shared.enable_tenant_rls('assistant.agreement_fact');

grant select, insert, update on all tables in schema assistant to app_runtime;
revoke delete on all tables in schema assistant from app_runtime;
revoke update on assistant.assistant_turn, assistant.turn_tool_call from app_runtime;
grant update (input_text_encrypted, context_encrypted, output_text_encrypted, content_purged_at)
    on assistant.assistant_turn to app_runtime;
