-- =============================================================================
-- Leitura das respostas do credor por e-mail (docs/tecnico/operacao/email-entrada.md,
-- decisões 819–835).
--   * dispute_reply_route: endereço de resposta próprio de cada pedido ao credor
--     (contestacao+<token>@<subdomínio>). Tabela global, sem RLS: o e-mail chega sem
--     assessoria. Só o hash SHA-256 do token, ids e a validade; nenhum dado do devedor.
--   * inbound_mail_unrouted: e-mail ao subdomínio sem pedido correspondente (token
--     desconhecido). Global, só metadados técnicos e a chave do original; nunca descartado
--     em silêncio.
--   * dispute_mail_reply: cada e-mail recebido para um pedido (append-only no conteúdo):
--     original (.eml) com SHA-256 para a cadeia de custódia, texto já sanitizado,
--     veredictos SPF/DKIM/DMARC/vírus/spam do SES, quarentena com motivo e a confirmação
--     do analista (que registra a resposta do credor).
--   * dispute_document_quarantine / dispute_evidence: os anexos seguem o mesmo pipeline
--     dos documentos da API (quarentena + antivírus), ligados ao e-mail.
--   * dispute_creditor_request.reply_address_encrypted: endereço de resposta usado no envio.
-- =============================================================================

create table collection.dispute_reply_route (
    token_hash varchar(64) not null primary key,
    tenant_id  uuid        not null,
    request_id uuid        not null,
    expires_at timestamptz not null,
    created_at timestamptz not null
);
create index ix_dispute_reply_route_request on collection.dispute_reply_route (request_id);

create table collection.inbound_mail_unrouted (
    id                  uuid         not null primary key,
    received_at         timestamptz  not null,
    provider_message_id varchar(200) not null,
    raw_key             varchar(500),
    size_bytes          bigint       not null,
    reason              varchar(30)  not null,
    created_at          timestamptz  not null,
    constraint uq_inbound_mail_unrouted unique (provider_message_id),
    constraint ck_inbound_mail_unrouted_reason check (reason in ('NO_REPLY_ADDRESS', 'UNKNOWN_TOKEN'))
);

alter table collection.dispute_creditor_request
    add column reply_address_encrypted varchar(2000);

create table collection.dispute_mail_reply (
    id                     uuid           not null primary key,
    tenant_id              uuid           not null references organization.tenant (id),
    version                bigint         not null,
    created_at             timestamptz    not null,
    created_by             uuid,
    updated_at             timestamptz    not null,
    updated_by             uuid,
    dispute_id             uuid           not null references collection.dispute (id),
    request_id             uuid           not null references collection.dispute_creditor_request (id),
    creditor_id            uuid           not null,
    provider_message_id    varchar(200)   not null,
    message_id_hash        varchar(64),
    sender_encrypted       varchar(2000)  not null,
    sender_hash            varchar(64)    not null,
    subject_encrypted      varchar(4000),
    text_encrypted         varchar(250000),
    text_truncated         boolean        not null,
    raw_key                varchar(500)   not null,
    raw_sha256             varchar(64)    not null,
    raw_size               bigint         not null,
    received_at            timestamptz    not null,
    spf_verdict            varchar(20)    not null,
    dkim_verdict           varchar(20)    not null,
    dmarc_verdict          varchar(20)    not null,
    virus_verdict          varchar(20)    not null,
    spam_verdict           varchar(20)    not null,
    attachments            int            not null,
    attachments_rejected   int            not null,
    status                 varchar(25)    not null,
    quarantine_reason      varchar(30),
    released_by            uuid,
    released_by_name       varchar(200),
    released_at            timestamptz,
    release_note_encrypted varchar(24000),
    response_id            uuid references collection.dispute_creditor_response (id),
    decided_by             uuid,
    decided_by_name        varchar(200),
    decided_at             timestamptz,
    decision_note_encrypted varchar(24000),
    constraint ck_dispute_mail_reply_status check (status in ('PENDING_CONFIRMATION', 'QUARANTINED', 'CONFIRMED',
                                                              'DISMISSED')),
    constraint ck_dispute_mail_reply_reason check (quarantine_reason is null or quarantine_reason in (
        'SENDER_NOT_REGISTERED', 'AUTHENTICATION_FAILED', 'VIRUS', 'SPAM', 'RATE_LIMITED', 'TOO_LARGE',
        'TOO_MANY_ATTACHMENTS', 'ROUTE_EXPIRED', 'DISPUTE_CLOSED', 'MALFORMED')),
    constraint ck_dispute_mail_reply_verdicts check (
        spf_verdict in ('PASS', 'FAIL', 'GRAY', 'PROCESSING_FAILED', 'DISABLED')
        and dkim_verdict in ('PASS', 'FAIL', 'GRAY', 'PROCESSING_FAILED', 'DISABLED')
        and dmarc_verdict in ('PASS', 'FAIL', 'GRAY', 'PROCESSING_FAILED', 'DISABLED')
        and virus_verdict in ('PASS', 'FAIL', 'GRAY', 'PROCESSING_FAILED', 'DISABLED')
        and spam_verdict in ('PASS', 'FAIL', 'GRAY', 'PROCESSING_FAILED', 'DISABLED')),
    -- o motivo existe só para o que foi (ou esteve) em quarentena; a resposta, só na confirmação
    constraint ck_dispute_mail_reply_quarantine check (status <> 'QUARANTINED' or quarantine_reason is not null),
    constraint ck_dispute_mail_reply_confirmed check ((status = 'CONFIRMED') = (response_id is not null)),
    constraint ck_dispute_mail_reply_decided check ((status in ('CONFIRMED', 'DISMISSED')) = (decided_at is not null)),
    constraint ck_dispute_mail_reply_released check ((released_at is null) = (released_by is null)
                                                     and (released_at is null or quarantine_reason is not null)),
    constraint ck_dispute_mail_reply_counts check (attachments >= 0 and attachments_rejected >= 0
                                                   and attachments_rejected <= attachments)
);
-- o mesmo e-mail (reentrega da fila) nunca é registrado duas vezes
create unique index uq_dispute_mail_reply_provider on collection.dispute_mail_reply (tenant_id, provider_message_id);
create index ix_dispute_mail_reply_dispute on collection.dispute_mail_reply (tenant_id, dispute_id, received_at);
create index ix_dispute_mail_reply_open on collection.dispute_mail_reply (tenant_id, status, received_at)
    where status in ('PENDING_CONFIRMATION', 'QUARANTINED');
create index ix_dispute_mail_reply_sender on collection.dispute_mail_reply (tenant_id, sender_hash, received_at);
select shared.enable_tenant_rls('collection.dispute_mail_reply');

-- o conteúdo recebido é imutável; só a decisão (uma vez) e a liberação da quarentena mudam
create or replace function collection.dispute_mail_reply_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'collection.dispute_mail_reply é append-only' using errcode = '42501';
    end if;
    if old.status in ('CONFIRMED', 'DISMISSED') then
        raise exception 'e-mail do credor já decidido não pode ser alterado' using errcode = '42501';
    end if;
    if new.raw_key <> old.raw_key or new.raw_sha256 <> old.raw_sha256 or new.sender_hash <> old.sender_hash
        or new.received_at <> old.received_at or new.request_id <> old.request_id
        or new.dispute_id <> old.dispute_id or new.provider_message_id <> old.provider_message_id
        or new.spf_verdict <> old.spf_verdict or new.dkim_verdict <> old.dkim_verdict
        or new.dmarc_verdict <> old.dmarc_verdict or new.virus_verdict <> old.virus_verdict
        or new.text_encrypted is distinct from old.text_encrypted then
        raise exception 'conteúdo do e-mail do credor é imutável' using errcode = '42501';
    end if;
    return new;
end
$$;
create trigger trg_dispute_mail_reply_guard before update or delete on collection.dispute_mail_reply
    for each row execute function collection.dispute_mail_reply_guard();

-- anexos do e-mail: mesma quarentena dos documentos da API, ligados ao e-mail e não a uma resposta
alter table collection.dispute_document_quarantine
    alter column creditor_response_id drop not null,
    add column mail_reply_id uuid references collection.dispute_mail_reply (id),
    add constraint ck_dispute_quarantine_origin check ((creditor_response_id is null) <> (mail_reply_id is null));
create index ix_dispute_quarantine_mail_reply on collection.dispute_document_quarantine (tenant_id, mail_reply_id)
    where mail_reply_id is not null;

alter table collection.dispute_evidence
    add column mail_reply_id uuid references collection.dispute_mail_reply (id);

alter table collection.dispute_creditor_response
    drop constraint ck_dispute_response_source,
    add constraint ck_dispute_response_source check (source in ('MANUAL', 'PUBLIC_API', 'EMAIL'));

grant select, insert on collection.dispute_reply_route to app_runtime;
grant select, insert on collection.inbound_mail_unrouted to app_runtime;
grant select, insert, update on collection.dispute_mail_reply to app_runtime;
