-- =============================================================================
-- Módulo pendingwork ("Minhas pendências", docs/tecnico/desenho/pendencias.md).
-- Registro diário da primeira observação de cada tipo de pendência por usuário:
-- garante um único evento de negócio pending_work.observed por usuário, tipo e
-- dia mesmo com várias instâncias e abas (insert ... on conflict do nothing).
-- Só ids, códigos e contagens — nenhum dado pessoal. Retenção curta (job diário).
-- =============================================================================
create schema if not exists pendingwork;
grant usage on schema pendingwork to app_runtime;

create table pendingwork.daily_observation (
    tenant_id    uuid         not null references organization.tenant (id),
    user_id      uuid         not null,
    observed_on  date         not null,
    item_type    varchar(60)  not null,
    total        bigint       not null,
    overdue      bigint       not null,
    observed_at  timestamptz  not null,
    constraint pk_pendingwork_daily_observation primary key (tenant_id, user_id, observed_on, item_type),
    constraint ck_pendingwork_observation_counts check (total > 0 and overdue >= 0 and overdue <= total)
);
create index ix_pendingwork_observation_day on pendingwork.daily_observation (tenant_id, observed_on);
select shared.enable_tenant_rls('pendingwork.daily_observation');

grant select, insert, delete on pendingwork.daily_observation to app_runtime;
