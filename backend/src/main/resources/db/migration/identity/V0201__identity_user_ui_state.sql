-- =============================================================================
-- Módulo identity: estado de interface do usuário (preferências visuais e progresso do
-- onboarding). Acompanha o usuário entre dispositivos. Guarda apenas o id do usuário e
-- identificadores técnicos de tours/telas — nenhum dado pessoal ou de devedor.
-- Tabelas filhas são protegidas por RLS através da linha pai (padrão de V0200).
-- =============================================================================

create table identity.user_ui_state (
    id                    uuid          not null primary key,
    tenant_id             uuid          not null references organization.tenant (id),
    version               bigint        not null,
    created_at            timestamptz   not null,
    created_by            uuid,
    updated_at            timestamptz   not null,
    updated_by            uuid,
    user_id               uuid          not null references identity.app_user (id) on delete cascade,
    theme                 varchar(10)   not null,
    density               varchar(12)   not null,
    navigation_collapsed  boolean       not null default false,
    hints_enabled         boolean       not null default true,
    checklist_dismissed   boolean       not null default false,
    constraint uk_user_ui_state_user unique (tenant_id, user_id),
    constraint ck_user_ui_state_theme check (theme in ('SYSTEM', 'LIGHT', 'DARK')),
    constraint ck_user_ui_state_density check (density in ('COMFORTABLE', 'COMPACT'))
);
select shared.enable_tenant_rls('identity.user_ui_state');

-- Tours concluídos ou dispensados, com a versão do tour no momento do registro.
create table identity.user_ui_tour (
    ui_state_id  uuid          not null references identity.user_ui_state (id) on delete cascade,
    tour_id      varchar(64)   not null,
    tour_version integer       not null,
    outcome      varchar(10)   not null,
    recorded_at  timestamptz   not null,
    primary key (ui_state_id, tour_id),
    constraint ck_user_ui_tour_version check (tour_version between 1 and 9999),
    constraint ck_user_ui_tour_outcome check (outcome in ('COMPLETED', 'DISMISSED'))
);
alter table identity.user_ui_tour enable row level security;
alter table identity.user_ui_tour force row level security;
create policy tenant_isolation on identity.user_ui_tour
    using (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id))
    with check (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id));

-- Telas já navegadas e a última versão vista de cada uma (base do destaque de novidades).
create table identity.user_ui_screen (
    ui_state_id    uuid          not null references identity.user_ui_state (id) on delete cascade,
    screen_key     varchar(64)   not null,
    seen_version   integer       not null,
    seen_at        timestamptz   not null,
    primary key (ui_state_id, screen_key),
    constraint ck_user_ui_screen_version check (seen_version between 1 and 9999)
);
alter table identity.user_ui_screen enable row level security;
alter table identity.user_ui_screen force row level security;
create policy tenant_isolation on identity.user_ui_screen
    using (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id))
    with check (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id));

-- Itens concluídos do checklist de primeiros passos.
create table identity.user_ui_checklist (
    ui_state_id   uuid          not null references identity.user_ui_state (id) on delete cascade,
    item_id       varchar(64)   not null,
    completed_at  timestamptz   not null,
    primary key (ui_state_id, item_id)
);
alter table identity.user_ui_checklist enable row level security;
alter table identity.user_ui_checklist force row level security;
create policy tenant_isolation on identity.user_ui_checklist
    using (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id))
    with check (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id));

grant select, insert, update, delete on identity.user_ui_state, identity.user_ui_tour, identity.user_ui_screen,
    identity.user_ui_checklist to app_runtime;
