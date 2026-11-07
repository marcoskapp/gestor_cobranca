-- =============================================================================
-- Identity: seções do menu lateral recolhidas pelo usuário (menu por tarefa, revisão de
-- produto de 11/2026 — docs/tecnico/desenho/ux.md §9). Guarda só o identificador técnico da
-- seção (definido pelo frontend, ex.: "score-de-propensao"); nenhum dado pessoal.
-- Filha de user_ui_state, protegida por RLS através da linha pai (padrão de V0201).
-- =============================================================================

create table identity.user_ui_nav_section (
    ui_state_id  uuid         not null references identity.user_ui_state (id) on delete cascade,
    section_key  varchar(64)  not null,
    primary key (ui_state_id, section_key)
);
alter table identity.user_ui_nav_section enable row level security;
alter table identity.user_ui_nav_section force row level security;
create policy tenant_isolation on identity.user_ui_nav_section
    using (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id))
    with check (exists (select 1 from identity.user_ui_state s where s.id = ui_state_id));

grant select, insert, update, delete on identity.user_ui_nav_section to app_runtime;
