-- =============================================================================
-- Retenção por assessoria dos prazos do portal e do scoring (antes fixos em app.portal.retention e
-- app.scoring.*-retention-days): cinco categorias novas, com prazo em dias, ações de descarte novas e a
-- coluna retention_days ao lado de retention_months — cada política usa só a coluna da unidade da categoria
-- (a outra fica nula).
--
-- Deploy gradual: as instâncias antigas continuam gravando retention_months nas categorias antigas (a coluna
-- só deixa de ser obrigatória; as categorias antigas continuam exigindo o valor nela). Tabela pequena (uma
-- linha por categoria e assessoria): os checks são recriados validados, sem varredura relevante.
-- =============================================================================
alter table compliance.retention_policy
    add column retention_days integer,
    alter column retention_months drop not null;

alter table compliance.retention_policy
    drop constraint ck_retention_policy_category,
    drop constraint ck_retention_policy_action,
    drop constraint ck_retention_policy_action_category,
    drop constraint ck_retention_policy_months;

alter table compliance.retention_policy
    add constraint ck_retention_policy_category check (category in (
        'DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS',
        'PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'PORTAL_SESSION_CLIENT_DATA',
        'SCORING_CASE_FACTS', 'SCORING_FEATURE_SNAPSHOTS')),
    add constraint ck_retention_policy_action check (action in ('ANONYMIZE', 'DELETE_FILE', 'DELETE_RECORD',
                                                                'CLEAR_FIELDS')),
    add constraint ck_retention_policy_action_category check (
        (category = 'DEBTOR_INACTIVE' and action = 'ANONYMIZE')
        or (category in ('IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS') and action = 'DELETE_FILE')
        or (category in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'SCORING_CASE_FACTS',
                         'SCORING_FEATURE_SNAPSHOTS') and action = 'DELETE_RECORD')
        or (category = 'PORTAL_SESSION_CLIENT_DATA' and action = 'CLEAR_FIELDS')),
    -- prazo obrigatório só na coluna da unidade da categoria (a outra nula); o "is not null" explícito evita que o
    -- check passe com prazo nulo (em SQL, "null > 0" é desconhecido, e o check só rejeita o falso). Limites por
    -- categoria ficam no domínio (RetentionCategory)
    add constraint ck_retention_policy_period check (
        (category in ('DEBTOR_INACTIVE', 'IMPORT_FILES', 'MESSAGE_CONTENT', 'CALL_RECORDINGS')
            and retention_months is not null and retention_months > 0 and retention_days is null)
        or (category in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES', 'PORTAL_SESSION_CLIENT_DATA',
                         'SCORING_CASE_FACTS', 'SCORING_FEATURE_SNAPSHOTS')
            and retention_days is not null and retention_days > 0 and retention_months is null)),
    -- o descarte das categorias com padrão do sistema não pode ser desligado
    add constraint ck_retention_policy_mandatory_enabled check (
        enabled or category not in ('PORTAL_ACCESS_CHALLENGES', 'PORTAL_ACCESS_THROTTLES',
                                    'PORTAL_SESSION_CLIENT_DATA', 'SCORING_CASE_FACTS',
                                    'SCORING_FEATURE_SNAPSHOTS'));

-- "Usar o padrão" (categorias com padrão do sistema) apaga a política da assessoria; a V0500 revogou o delete porque
-- até aqui as políticas só eram alteradas. A trilha (retention_policy.reset_to_default) guarda o prazo apagado.
grant delete on compliance.retention_policy to app_runtime;

comment on column compliance.retention_policy.retention_months is
    'Prazo em meses (categorias com unidade MONTHS); nulo nas categorias em dias';
comment on column compliance.retention_policy.retention_days is
    'Prazo em dias (categorias com unidade DAYS: portal e scoring); nulo nas categorias em meses';
