-- =============================================================================
-- Score de propensão no caso (docs/tecnico/desenho/scoring.md §6): o módulo scoring
-- publica CaseScoreChanged e o collection guarda o score (coluna `score`, já
-- existente) com o instante do cálculo, para descartar entregas fora de ordem ou
-- republicadas (o cálculo mais recente sempre prevalece).
-- =============================================================================
alter table collection.collection_case
    add column score_calculated_at timestamptz,
    add constraint ck_case_score check (score is null or score between 0 and 1000);
