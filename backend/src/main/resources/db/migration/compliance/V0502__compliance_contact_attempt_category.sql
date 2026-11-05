-- =============================================================================
-- Compliance: categoria da comunicação no porteiro de contato (docs/negocio/contestacoes.md §6).
-- COLLECTION (cobrança, padrão de todas as tentativas existentes) conta para os limites de
-- frequência; TRANSACTIONAL (resposta devida ao devedor, ex.: resultado da contestação) é
-- registrada para rastreabilidade e não entra nos limites.
-- =============================================================================
alter table compliance.contact_attempt
    add column category varchar(20) not null default 'COLLECTION',
    add constraint ck_contact_attempt_category check (category in ('COLLECTION', 'TRANSACTIONAL'));
