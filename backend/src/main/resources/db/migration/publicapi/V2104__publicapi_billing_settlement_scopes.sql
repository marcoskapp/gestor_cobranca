-- =============================================================================
-- Escopos de leitura da posição do faturamento da API e do repasse ao credor
-- (billing:read, settlement:read — decisão 628). Só amplia a lista permitida.
-- =============================================================================
alter table publicapi.integration_token_scope drop constraint ck_integration_token_scope;
alter table publicapi.integration_token_scope add constraint ck_integration_token_scope check (scope in (
    'PORTFOLIOS_READ', 'PORTFOLIOS_WRITE', 'IMPORTS_READ', 'IMPORTS_WRITE', 'TITLES_READ', 'TITLES_WRITE',
    'DEBTORS_READ', 'DEBTORS_WRITE', 'COLLECTION_READ', 'AGREEMENTS_READ', 'PAYMENTS_READ', 'PAYMENTS_WRITE',
    'DISPUTES_READ', 'DISPUTES_WRITE', 'BILLING_READ', 'SETTLEMENT_READ'));
