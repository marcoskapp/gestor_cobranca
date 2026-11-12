-- =============================================================================
-- Portal para pessoa jurídica (cobrança B2B, B4 — docs/tecnico/desenho/cobranca-b2b.md §5.2):
--   * a sessão registra quem declarou estar negociando pela empresa e se o CPF conferiu com um
--     representante com poder para negociar (o CPF digitado nunca é gravado em claro: só a forma
--     mascarada, para o atendente; o nome declarado fica criptografado). Ambos são apagados com o
--     IP e o agente do navegador (retenção SESSION_CLIENT_DATA);
--   * bloqueio progressivo da confirmação do representante por devedor PJ (REP_DEBTOR) e por IP
--     (REP_IP), no mesmo mecanismo persistido do acesso (renovar a sessão não zera a contagem);
--   * o acordo fechado no portal guarda o representante que aceitou (id e papel);
--   * aceite sem representante confirmado vira pedido de atendimento (COMPANY_ACCEPTANCE).
-- =============================================================================

alter table portal.portal_session
    add column representative_status             varchar(12),
    add column representative_id                 uuid,
    add column representative_checked_at         timestamptz,
    add column representative_name_encrypted     varchar(1024),
    add column representative_document_masked    varchar(14);
alter table portal.portal_session
    add constraint ck_portal_session_representative check (
        (representative_status is null and representative_id is null and representative_checked_at is null)
        or (representative_status = 'CONFIRMED' and representative_id is not null and representative_checked_at is not null)
        or (representative_status = 'UNCONFIRMED' and representative_id is null and representative_checked_at is not null));

alter table portal.access_throttle drop constraint ck_access_throttle_type;
alter table portal.access_throttle
    add constraint ck_access_throttle_type check (key_type in ('DOCUMENT', 'IP', 'REP_DEBTOR', 'REP_IP'));

alter table portal.portal_agreement
    add column representative_id    uuid,
    add column representative_role  varchar(20);
alter table portal.portal_agreement
    add constraint ck_portal_agreement_representative check (
        (representative_id is null and representative_role is null)
        or (representative_id is not null
            and representative_role in ('MANAGING_PARTNER', 'ATTORNEY_IN_FACT', 'OTHER')));

alter table portal.service_request drop constraint ck_service_request_reason;
alter table portal.service_request
    add constraint ck_service_request_reason check (reason in ('TALK_TO_AGENT', 'DISPUTE', 'CANNOT_AFFORD', 'OTHER',
                                                               'COMPANY_ACCEPTANCE'));
