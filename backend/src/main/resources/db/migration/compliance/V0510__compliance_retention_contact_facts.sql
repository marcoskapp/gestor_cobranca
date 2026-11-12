-- =============================================================================
-- Retenção dos fatos da inteligência de contato do score (scoring.contact_attempt e o perfil derivado):
-- categoria nova SCORING_CONTACT_FACTS, em dias, com padrão do sistema (descarte obrigatório), mesmos limites
-- de SCORING_CASE_FACTS (limites no domínio, RetentionCategory). Recria os checks que listam categorias
-- (tabela pequena: uma linha por categoria e assessoria).
-- =============================================================================
alter table compliance.retention_policy
    drop constraint ck_retention_policy_category,
    drop constraint ck_retention_policy_action_category,
    drop constraint ck_retention_policy_period,
    drop constraint ck_retention_policy_mandatory_enabled;

alter table compliance.retention_policy
    add constraint ck_retention_policy_category check (category in (
        'DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS',
        'PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'PORTAL_SESSION_CLIENT_DATA',
        'SCORING_CASE_FACTS', 'SCORING_FEATURE_SNAPSHOTS', 'SCORING_CONTACT_FACTS')),
    add constraint ck_retention_policy_action_category check (
        (category = 'DEBTOR_INACTIVE' and action = 'ANONYMIZE')
        or (category in ('IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS') and action = 'DELETE_FILE')
        or (category in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'SCORING_CASE_FACTS',
                         'SCORING_FEATURE_SNAPSHOTS', 'SCORING_CONTACT_FACTS') and action = 'DELETE_RECORD')
        or (category = 'PORTAL_SESSION_CLIENT_DATA' and action = 'CLEAR_FIELDS')),
    add constraint ck_retention_policy_period check (
        (category in ('DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS')
            and retention_months is not null and retention_months > 0 and retention_days is null)
        or (category in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'PORTAL_SESSION_CLIENT_DATA',
                         'SCORING_CASE_FACTS', 'SCORING_FEATURE_SNAPSHOTS', 'SCORING_CONTACT_FACTS')
            and retention_days is not null and retention_days > 0 and retention_months is null)),
    add constraint ck_retention_policy_mandatory_enabled check (
        enabled or category not in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES',
                                    'PORTAL_SESSION_CLIENT_DATA', 'SCORING_CASE_FACTS',
                                    'SCORING_FEATURE_SNAPSHOTS', 'SCORING_CONTACT_FACTS'));
