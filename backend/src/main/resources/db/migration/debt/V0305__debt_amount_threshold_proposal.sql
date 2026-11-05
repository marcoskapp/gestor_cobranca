-- =============================================================================
-- Aumento do limite da correção de valor só com a confirmação de outra pessoa (RB06; decisão 805:
-- a segregação vale também para o administrador). Reduzir o limite (mais restritivo) vale na hora;
-- aumentar fica proposto até outra pessoa com TITLE_AMOUNT_APPROVE confirmar. Sem isso, quem aprova
-- subiria o limite, aplicaria a própria redução sem segunda pessoa e voltaria o limite.
-- O nome de quem propôs é guardado como retrato (o debt não depende do identity; decisão 809).
-- =============================================================================

alter table debt.amount_adjustment_settings
    add column proposed_threshold numeric(19, 2),
    add column proposed_by        uuid,
    add column proposed_by_name   varchar(200),
    add column proposed_at        timestamptz,
    add constraint ck_amount_adjustment_proposal check (
        (proposed_threshold is null and proposed_by is null and proposed_at is null)
        or (proposed_threshold is not null and proposed_by is not null and proposed_at is not null
            and proposed_threshold > approval_threshold and proposed_threshold <= 100000.00));
