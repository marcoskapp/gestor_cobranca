-- Portal de autonegociação (F19): o devedor autenticado no portal passa a ser ator da negociação,
-- com a alçada do canal (sujeito PORTAL, perfil padrão PORTAL) e o motivo de concessão
-- pré-autorizado do canal. Versão na faixa do módulo portal (V1300–V1399), pasta do schema dono.
-- Ver docs/tecnico/desenho/portal.md §4.

alter table negotiation.authority_assignment drop constraint ck_authority_assignment_subject;
alter table negotiation.authority_assignment add constraint ck_authority_assignment_subject
    check (subject_type in ('USER', 'ASSISTANT_POLICY', 'PORTAL'));

alter table negotiation.simulation drop constraint ck_simulation_actor;
alter table negotiation.simulation add constraint ck_simulation_actor
    check (actor_type in ('USER', 'ASSISTANT', 'DEBTOR'));

alter table negotiation.simulation drop constraint ck_simulation_reason;
alter table negotiation.simulation add constraint ck_simulation_reason check (justification_reason is null or justification_reason in (
    'PAYMENT_CAPACITY', 'INCOME_LOSS', 'HEALTH_OR_FAMILY', 'COMPETING_OFFER', 'LOW_RECOVERY_PROSPECT',
    'IMMEDIATE_PAYMENT', 'OTHER', 'SELF_SERVICE_PREAUTHORIZED'));

alter table negotiation.proposal drop constraint ck_proposal_actor;
alter table negotiation.proposal add constraint ck_proposal_actor
    check (actor_type in ('USER', 'ASSISTANT', 'DEBTOR'));

alter table negotiation.proposal drop constraint ck_proposal_reason;
alter table negotiation.proposal add constraint ck_proposal_reason check (justification_reason is null or justification_reason in (
    'PAYMENT_CAPACITY', 'INCOME_LOSS', 'HEALTH_OR_FAMILY', 'COMPETING_OFFER', 'LOW_RECOVERY_PROSPECT',
    'IMMEDIATE_PAYMENT', 'OTHER', 'SELF_SERVICE_PREAUTHORIZED'));
