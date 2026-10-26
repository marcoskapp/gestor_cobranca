-- =============================================================================
-- Módulo helpdesk: chat de suporte com IA para o usuário interno
-- (docs/tecnico/desenho/chat-suporte.md). Faixa V2000–V2099.
--
-- * chat_conversation: conversa de um usuário (consumo acumulado e livro de
--   referências opacas D1, A1… → ids internos; nenhum dado pessoal).
-- * chat_message: perguntas e respostas. Conteúdo cifrado (content_encrypted) e
--   descartado pela retenção; metadados, consumo e avaliação permanecem. Só a
--   avaliação, o vínculo com o chamado e o descarte do conteúdo podem mudar
--   (privilégio de coluna + gatilho); sem exclusão.
-- * support_ticket: chamado (issue no GitHub) preparado pela IA e confirmado pela
--   pessoa; título e corpo (já mascarados, com o e-mail de quem abriu) cifrados.
--   Sem exclusão; o conteúdo nunca muda depois do rascunho.
-- =============================================================================
create schema if not exists helpdesk;
grant usage on schema helpdesk to app_runtime;

create table helpdesk.chat_conversation (
    id               uuid           not null primary key,
    tenant_id        uuid           not null references organization.tenant (id),
    version          bigint         not null,
    created_at       timestamptz    not null,
    created_by       uuid,
    updated_at       timestamptz    not null,
    updated_by       uuid,
    user_id          uuid           not null,
    started_at       timestamptz    not null,
    last_activity_at timestamptz    not null,
    screen_key       varchar(64),
    turns            integer        not null,
    input_tokens     bigint         not null,
    output_tokens    bigint         not null,
    cost_amount      numeric(19, 6) not null,
    cost_currency    varchar(3),
    references_json  jsonb          not null,
    constraint ck_chat_conversation_usage check (turns >= 0 and input_tokens >= 0 and output_tokens >= 0
                                                 and cost_amount >= 0)
);
create index ix_chat_conversation_user on helpdesk.chat_conversation (tenant_id, user_id, last_activity_at desc);
select shared.enable_tenant_rls('helpdesk.chat_conversation');

create table helpdesk.chat_message (
    id                 uuid           not null primary key,
    tenant_id          uuid           not null references organization.tenant (id),
    version            bigint         not null,
    created_at         timestamptz    not null,
    created_by         uuid,
    updated_at         timestamptz    not null,
    updated_by         uuid,
    conversation_id    uuid           not null references helpdesk.chat_conversation (id),
    user_id            uuid           not null,
    sequence           integer        not null,
    role               varchar(12)    not null,
    content_encrypted  varchar(100000),
    content_purged_at  timestamptz,
    screen_key         varchar(64),
    occurred_at        timestamptz    not null,
    outcome            varchar(20),
    topic              varchar(30),
    prompt_ref         varchar(60),
    prompt_sha256      varchar(64),
    knowledge_version  varchar(32),
    provider           varchar(20),
    model              varchar(200),
    model_calls        integer        not null,
    tool_calls         integer        not null,
    input_tokens       bigint         not null,
    output_tokens      bigint         not null,
    cache_read_tokens  bigint         not null,
    cache_write_tokens bigint         not null,
    cost_amount        numeric(19, 6) not null,
    cost_currency      varchar(3),
    latency_ms         bigint,
    error_code         varchar(60),
    ticket_id          uuid,
    feedback           varchar(4),
    feedback_reason    varchar(30),
    feedback_at        timestamptz,
    constraint uk_chat_message_sequence unique (tenant_id, conversation_id, sequence),
    constraint ck_chat_message_role check (role in ('USER', 'ASSISTANT')),
    constraint ck_chat_message_outcome check (outcome is null or outcome in ('ANSWERED', 'UNANSWERED', 'OUT_OF_SCOPE',
                                                                             'FAILED')),
    constraint ck_chat_message_answer check ((role = 'ASSISTANT') = (outcome is not null)),
    constraint ck_chat_message_topic check (topic is null or topic in ('HOW_TO', 'DATA_LOOKUP', 'BUSINESS_RULE', 'ACCESS',
                                                                       'SYSTEM_ERROR', 'OUT_OF_SCOPE', 'OTHER')),
    constraint ck_chat_message_feedback check (feedback is null or (role = 'ASSISTANT' and feedback in ('UP', 'DOWN')
                                                                    and feedback_at is not null)),
    constraint ck_chat_message_feedback_reason check (feedback_reason is null or (feedback = 'DOWN' and feedback_reason in
                                                      ('INCORRECT', 'NOT_HELPFUL', 'INCOMPLETE', 'OUT_OF_DATE', 'OTHER'))),
    constraint ck_chat_message_usage check (model_calls >= 0 and tool_calls >= 0 and input_tokens >= 0
                                            and output_tokens >= 0 and cache_read_tokens >= 0
                                            and cache_write_tokens >= 0 and cost_amount >= 0),
    constraint ck_chat_message_purged check (content_purged_at is null or content_encrypted is null),
    constraint ck_chat_message_content check (content_purged_at is not null or content_encrypted is not null)
);
create index ix_chat_message_conversation on helpdesk.chat_message (tenant_id, conversation_id, sequence);
create index ix_chat_message_user_time on helpdesk.chat_message (tenant_id, user_id, occurred_at);
create index ix_chat_message_purge on helpdesk.chat_message (tenant_id, occurred_at) where content_purged_at is null;
select shared.enable_tenant_rls('helpdesk.chat_message');

-- Só avaliação, vínculo com o chamado e descarte do conteúdo (para nulo) podem mudar.
create or replace function helpdesk.chat_message_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: helpdesk.chat_message' using errcode = 'P0001';
    end if;
    if new.content_encrypted is distinct from old.content_encrypted and new.content_encrypted is not null then
        raise exception 'Conteúdo de helpdesk.chat_message só pode ser descartado' using errcode = 'P0001';
    end if;
    if (to_jsonb(new) - 'content_encrypted' - 'content_purged_at' - 'ticket_id' - 'feedback' - 'feedback_reason'
            - 'feedback_at' - 'version' - 'updated_at' - 'updated_by')
       is distinct from
       (to_jsonb(old) - 'content_encrypted' - 'content_purged_at' - 'ticket_id' - 'feedback' - 'feedback_reason'
            - 'feedback_at' - 'version' - 'updated_at' - 'updated_by') then
        raise exception 'Registro imutável: helpdesk.chat_message (só avaliação, chamado e descarte)'
            using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_chat_message_guard before update or delete on helpdesk.chat_message
    for each row execute function helpdesk.chat_message_guard();

create table helpdesk.support_ticket (
    id               uuid          not null primary key,
    tenant_id        uuid          not null references organization.tenant (id),
    version          bigint        not null,
    created_at       timestamptz   not null,
    created_by       uuid,
    updated_at       timestamptz   not null,
    updated_by       uuid,
    conversation_id  uuid          not null references helpdesk.chat_conversation (id),
    user_id          uuid          not null,
    status           varchar(20)   not null,
    title_encrypted  varchar(2000) not null,
    body_encrypted   varchar(40000) not null,
    content_sha256   varchar(64)   not null,
    screen_key       varchar(64),
    repository       varchar(140)  not null,
    drafted_at       timestamptz   not null,
    expires_at       timestamptz   not null,
    creating_since   timestamptz,
    attempts         integer       not null,
    issue_number     bigint,
    issue_url        varchar(300),
    opened_at        timestamptz,
    duplicate_of_id  uuid references helpdesk.support_ticket (id),
    error_code       varchar(60),
    constraint ck_support_ticket_status check (status in ('DRAFT', 'CREATING', 'OPENED', 'DUPLICATE', 'FAILED',
                                                          'CANCELLED', 'EXPIRED')),
    constraint ck_support_ticket_opened check (status not in ('OPENED', 'DUPLICATE')
                                               or (issue_number is not null and issue_url is not null
                                                   and opened_at is not null)),
    constraint ck_support_ticket_creating check ((status = 'CREATING') = (creating_since is not null)),
    constraint ck_support_ticket_attempts check (attempts >= 0)
);
create index ix_support_ticket_user on helpdesk.support_ticket (tenant_id, user_id, content_sha256, opened_at desc);
create index ix_support_ticket_pending on helpdesk.support_ticket (tenant_id, expires_at)
    where status in ('DRAFT', 'FAILED');
create unique index uk_support_ticket_issue on helpdesk.support_ticket (repository, issue_number)
    where status = 'OPENED';
select shared.enable_tenant_rls('helpdesk.support_ticket');

-- O conteúdo do chamado nunca muda depois do rascunho.
create or replace function helpdesk.support_ticket_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'Registro imutável: helpdesk.support_ticket' using errcode = 'P0001';
    end if;
    if new.title_encrypted is distinct from old.title_encrypted or new.body_encrypted is distinct from old.body_encrypted
       or new.content_sha256 is distinct from old.content_sha256 or new.user_id is distinct from old.user_id
       or new.conversation_id is distinct from old.conversation_id or new.repository is distinct from old.repository then
        raise exception 'Conteúdo de helpdesk.support_ticket é imutável' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_support_ticket_guard before update or delete on helpdesk.support_ticket
    for each row execute function helpdesk.support_ticket_guard();

grant select, insert, update on helpdesk.chat_conversation, helpdesk.support_ticket to app_runtime;
grant select, insert on helpdesk.chat_message to app_runtime;
grant update (content_encrypted, content_purged_at, ticket_id, feedback, feedback_reason, feedback_at, version,
              updated_at, updated_by) on helpdesk.chat_message to app_runtime;
