-- =============================================================================
-- Inteligência de contato (fase 1): fatos de tentativa/resposta por contato, perfil agregado por
-- contato e desfecho por acordo (contatos até o acordo e até o acordo cumprido).
-- Desenho: docs/tecnico/desenho/inteligencia-de-contato.md; regra: docs/negocio/score-propensao.md.
--
-- Sem dado pessoal direto: ids (o valor do contato fica no debt), canal, contagens, hora e dia da semana no
-- fuso de operação. RLS forçado por tenant. Retenção: categoria SCORING_CONTACT_FACTS do compliance.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Fato por tentativa, resposta do devedor ou leitura de mensagem (idempotente pelo evento de origem).
-- success de uma tentativa de mensagem passa a verdadeiro quando o devedor responde no mesmo canal e credor
-- (e contato, quando identificado) em até 72 h, e só se ela for a tentativa mais recente da janela
-- (responded_by_event_id guarda a resposta que a marcou).
-- -----------------------------------------------------------------------------
create table scoring.contact_attempt (
    tenant_id              uuid         not null references organization.tenant (id),
    source_event_id        uuid         not null,
    kind                   varchar(10)  not null,
    debtor_id              uuid         not null,
    creditor_id            uuid,
    portfolio_id           uuid,
    case_id                uuid,
    contact_id             uuid,
    channel                varchar(10)  not null,
    direction              varchar(10)  not null,
    success                boolean      not null,
    local_hour             smallint     not null,
    local_weekday          smallint     not null,
    person_type            varchar(12),
    occurred_at            timestamptz  not null,
    responded_by_event_id  uuid,
    -- NEW: contato chegou numa carga depois do cadastro do devedor (contatos-recebidos.md); EXISTING: os demais;
    -- nulo sem contato identificado. Gravado no registro do fato (comparação contato novo × antigo).
    contact_origin         varchar(10),
    recorded_at            timestamptz  not null default now(),
    primary key (tenant_id, source_event_id),
    constraint ck_contact_attempt_kind check (kind in ('ATTEMPT', 'RESPONSE', 'READ')),
    constraint ck_contact_attempt_channel check (channel in ('PHONE', 'WHATSAPP', 'SMS', 'EMAIL', 'OTHER')),
    constraint ck_contact_attempt_direction check (direction in ('OUTBOUND', 'INBOUND')),
    constraint ck_contact_attempt_person_type check (person_type is null or person_type in ('INDIVIDUAL', 'COMPANY')),
    constraint ck_contact_attempt_origin check (contact_origin is null or contact_origin in ('NEW', 'EXISTING')),
    constraint ck_contact_attempt_hour check (local_hour between 0 and 23),
    constraint ck_contact_attempt_weekday check (local_weekday between 1 and 7)
);
create index ix_contact_attempt_debtor on scoring.contact_attempt (tenant_id, debtor_id, occurred_at);
create index ix_contact_attempt_period on scoring.contact_attempt (tenant_id, occurred_at, creditor_id);
-- tentativa mais recente do devedor no canal (a resposta só marca a mais recente, com ou sem sucesso)
create index ix_contact_attempt_latest on scoring.contact_attempt (tenant_id, debtor_id, channel, occurred_at desc)
    where kind = 'ATTEMPT';
select shared.enable_tenant_rls('scoring.contact_attempt');

-- -----------------------------------------------------------------------------
-- Perfil agregado por (devedor, credor, contato, canal), recalculado a partir dos fatos vigentes do devedor
-- a cada fato novo e no descarte. slots: 6 faixas de 3 h (06–24) com tentativas, sucessos e a última tentativa.
-- -----------------------------------------------------------------------------
create table scoring.contact_profile (
    tenant_id                 uuid         not null references organization.tenant (id),
    debtor_id                 uuid         not null,
    creditor_id               uuid,
    contact_id                uuid,
    channel                   varchar(10)  not null,
    attempts                  integer      not null,
    successes                 integer      not null,
    debtor_initiated          integer      not null,
    reads                     integer      not null,
    attempts_to_first_success integer,
    last_success_at           timestamptz,
    last_attempt_at           timestamptz,
    slots                     jsonb        not null,
    updated_at                timestamptz  not null,
    constraint ck_contact_profile_channel check (channel in ('PHONE', 'WHATSAPP', 'SMS', 'EMAIL', 'OTHER')),
    constraint ck_contact_profile_counts check (attempts >= 0 and successes >= 0 and successes <= attempts
                                                and debtor_initiated >= 0 and reads >= 0),
    constraint ck_contact_profile_first check (attempts_to_first_success is null
                                               or attempts_to_first_success between 1 and attempts)
);
create unique index uk_contact_profile on scoring.contact_profile (tenant_id, debtor_id,
    coalesce(creditor_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(contact_id, '00000000-0000-0000-0000-000000000000'::uuid), channel);
select shared.enable_tenant_rls('scoring.contact_profile');

-- -----------------------------------------------------------------------------
-- Desfecho por acordo: tentativas e contatos efetivos antes do acordo (desde o acordo anterior do devedor no
-- mesmo credor) e a situação final. Na anonimização do devedor (e no prazo de retenção, para acordos
-- encerrados) case_id, agreement_id e debtor_id viram nulos e agreed_at, status_at e updated_at são truncados
-- para o dia no fuso de operação: fica só a estatística sem identificação.
-- -----------------------------------------------------------------------------
create table scoring.agreement_contact_outcome (
    tenant_id                  uuid         not null references organization.tenant (id),
    id                         uuid         not null primary key,
    agreement_id               uuid,
    case_id                    uuid,
    debtor_id                  uuid,
    creditor_id                uuid         not null,
    portfolio_id               uuid,
    person_type                varchar(12),
    agreed_at                  timestamptz  not null,
    attempts_before            integer      not null,
    effective_before           integer      not null,
    last_effective_channel     varchar(10),
    days_first_effective       integer,
    final_status               varchar(10)  not null,
    status_at                  timestamptz  not null,
    updated_at                 timestamptz  not null,
    constraint ck_agreement_outcome_status check (final_status in ('ACTIVE', 'COMPLETED', 'BROKEN', 'CANCELLED')),
    constraint ck_agreement_outcome_person_type check (person_type is null or person_type in ('INDIVIDUAL', 'COMPANY')),
    constraint ck_agreement_outcome_channel check (last_effective_channel is null
        or last_effective_channel in ('PHONE', 'WHATSAPP', 'SMS', 'EMAIL', 'OTHER')),
    constraint ck_agreement_outcome_counts check (attempts_before >= 0 and effective_before >= 0
                                                  and (days_first_effective is null or days_first_effective >= 0))
);
create unique index uk_agreement_outcome_agreement on scoring.agreement_contact_outcome (tenant_id, agreement_id)
    where agreement_id is not null;
create index ix_agreement_outcome_period on scoring.agreement_contact_outcome (tenant_id, agreed_at, creditor_id);
create index ix_agreement_outcome_debtor on scoring.agreement_contact_outcome (tenant_id, debtor_id)
    where debtor_id is not null;
select shared.enable_tenant_rls('scoring.agreement_contact_outcome');

-- -----------------------------------------------------------------------------
-- Base das taxas, materializada na manutenção diária (scoring.contact_rate_base) com as tentativas dos últimos
-- 180 dias: o cartão do perfil lê a priori e a mediana daqui, sem agregar a assessoria a cada abertura.
--   portfolio_id + channel: tentativas e sucessos da carteira no canal;
--   channel sem carteira:   tentativas e sucessos da assessoria no canal;
--   portfolio_id sem canal: totais da carteira e a mediana de tentativas até o 1º sucesso das séries
--                           (devedor, contato, canal) com sucesso.
-- Antes da primeira manutenção a tabela está vazia e a priori é neutra (50%).
-- -----------------------------------------------------------------------------
create table scoring.contact_rate_base (
    tenant_id                        uuid          not null references organization.tenant (id),
    portfolio_id                     uuid,
    channel                          varchar(10),
    attempts                         bigint        not null,
    successes                        bigint        not null,
    median_attempts_to_first_success numeric(8, 1),
    window_from                      timestamptz   not null,
    refreshed_at                     timestamptz   not null,
    constraint ck_contact_rate_base_channel check (channel is null
        or channel in ('PHONE', 'WHATSAPP', 'SMS', 'EMAIL', 'OTHER')),
    constraint ck_contact_rate_base_scope check (portfolio_id is not null or channel is not null),
    constraint ck_contact_rate_base_counts check (attempts >= 0 and successes >= 0 and successes <= attempts),
    constraint ck_contact_rate_base_median check (median_attempts_to_first_success is null
        or (channel is null and median_attempts_to_first_success >= 1))
);
create unique index uk_contact_rate_base on scoring.contact_rate_base (tenant_id,
    coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(channel, ''));
select shared.enable_tenant_rls('scoring.contact_rate_base');

grant select, insert, delete on scoring.contact_attempt to app_runtime;
grant update (success, responded_by_event_id) on scoring.contact_attempt to app_runtime;
grant select, insert, delete on scoring.contact_profile to app_runtime;
grant select, insert, update on scoring.agreement_contact_outcome to app_runtime;
grant select, insert, delete on scoring.contact_rate_base to app_runtime;
