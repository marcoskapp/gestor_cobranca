-- =============================================================================
-- Score de propensão na projeção de audiência (docs/tecnico/desenho/scoring.md §6):
-- o campaign consome CaseScoreChanged do scoring e grava o score com o instante do
-- cálculo. Depois do primeiro score por evento, a posição lida do collection não
-- altera mais o score (o evento é a fonte, ordenada por score_calculated_at).
-- =============================================================================
alter table campaign.audience_case
    add column score_calculated_at timestamptz;
