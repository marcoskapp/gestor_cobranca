-- =============================================================================
-- Contato novo recebido na carga (docs/tecnico/desenho/contatos-recebidos.md §2):
--  * no contato: carga que o criou, última carga que o enviou (criação ou reenvio),
--    se chegou para devedor já existente, fim da janela de novidade e primeira
--    tentativa/primeiro contato efetivo por ele (de qualquer origem). Sem dado pessoal novo.
--  * no devedor: carga que o criou (nulo se criado à mão), para saber se o devedor
--    "já existia antes da carga".
--  * prazo da janela de novidade por assessoria (padrão do sistema enquanto não gravado).
-- Os contatos e devedores existentes ficam sem novidade (nenhum é marcado como novo
-- retroativamente) e sem carga de origem: sem backfill.
-- =============================================================================

alter table debt.debtor_contact
    add column first_import_batch_id       uuid,
    add column last_import_batch_id        uuid,
    add column last_received_at            timestamptz,
    add column received_after_registration boolean not null default false,
    add column novelty_until               timestamptz,
    add column first_attempt_at            timestamptz,
    add column first_effective_at          timestamptz,
    -- só contato vindo de carga é "recebido depois do cadastro", e só ele tem janela de novidade
    add constraint ck_debtor_contact_novelty check (
        (not received_after_registration or first_import_batch_id is not null)
        and ((novelty_until is null) = (not received_after_registration))),
    add constraint ck_debtor_contact_received check ((last_import_batch_id is null) = (last_received_at is null)),
    add constraint ck_debtor_contact_effective check (first_effective_at is null or first_attempt_at is not null);

-- recálculo do fim da novidade do devedor
create index ix_debtor_contact_novelty on debt.debtor_contact (tenant_id, debtor_id) where novelty_until is not null;
-- varredura diária: janelas de contato novo sem tentativa vencidas no intervalo, por devedor (cursor por debtor_id)
create index ix_debtor_contact_novelty_due on debt.debtor_contact (tenant_id, novelty_until, debtor_id)
    where novelty_until is not null and first_attempt_at is null;
-- relatório da carga ("N devedores receberam contato novo") e filtro da lista de devedores pela carga
create index ix_debtor_contact_first_batch on debt.debtor_contact (tenant_id, first_import_batch_id)
    where received_after_registration;

alter table debt.debtor
    add column created_import_batch_id uuid;

-- -----------------------------------------------------------------------------
-- Prazo da janela de novidade por assessoria (uma linha por assessoria; sem linha =
-- padrão do sistema app.debt.contact-novelty.window-days). Alteração auditada
-- (contact_novelty_settings.updated).
-- -----------------------------------------------------------------------------
create table debt.contact_novelty_settings (
    id          uuid        not null primary key,
    tenant_id   uuid        not null references organization.tenant (id),
    version     bigint      not null,
    created_at  timestamptz not null,
    created_by  uuid,
    updated_at  timestamptz not null,
    updated_by  uuid,
    window_days integer     not null,
    constraint uq_contact_novelty_settings unique (tenant_id),
    constraint ck_contact_novelty_window check (window_days between 1 and 180)
);
select shared.enable_tenant_rls('debt.contact_novelty_settings');

-- configuração é alterada, nunca removida
grant select, insert, update on debt.contact_novelty_settings to app_runtime;
