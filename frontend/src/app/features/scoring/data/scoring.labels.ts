import { FeatureInfo, LegalBasis, ModelKind, RecoveryGroupBy, ReviewOrigin, RunKind, RunTrigger, ScoreBand, ScoreTrigger } from './scoring.models';

export const LEGAL_BASIS_LABELS: Record<LegalBasis, string> = {
  LEGITIMATE_INTEREST: 'Legítimo interesse (LGPD, art. 7º, IX)',
  CREDIT_PROTECTION: 'Proteção do crédito (LGPD, art. 7º, X)',
};

export const LEGAL_BASIS_HINTS: Record<LegalBasis, string> = {
  LEGITIMATE_INTEREST: 'Informe a referência da avaliação de legítimo interesse (LIA) aprovada pelo encarregado de dados.',
  CREDIT_PROTECTION: 'Informe a referência do parecer ou registro interno que fundamenta o uso para proteção do crédito.',
};

export const RUN_KIND_LABELS: Record<RunKind, string> = {
  BATCH: 'Escoragem em lote',
  TRAINING: 'Treino de modelo',
};

export const RUN_TRIGGER_LABELS: Record<RunTrigger, string> = {
  SCHEDULED: 'Agendada',
  MANUAL: 'Manual',
};

export const MODEL_KIND_LABELS: Record<ModelKind, string> = {
  SCORECARD: 'Scorecard por pontos',
  LOGISTIC_REGRESSION: 'Regressão logística',
};

export const SCORE_TRIGGER_LABELS: Record<ScoreTrigger, string> = {
  BATCH: 'escoragem diária',
  ON_DEMAND: 'recálculo sob demanda',
  EVENT: 'fato novo no caso',
  REVIEW: 'revisão',
};

export const REVIEW_ORIGIN_LABELS: Record<ReviewOrigin, string> = {
  DATA_SUBJECT_REQUEST: 'Pedido do titular (LGPD, art. 20)',
  OPERATION_REQUEST: 'Pedido do credor ou da operação',
  QUALITY_CHECK: 'Verificação interna de qualidade',
};

export const REVIEW_ORIGINS: readonly ReviewOrigin[] = ['DATA_SUBJECT_REQUEST', 'OPERATION_REQUEST', 'QUALITY_CHECK'];

export const DECISION_LABELS: Record<string, string> = {
  PROMOTE: 'Promovido a campeão',
  REJECT: 'Desafiante rejeitado',
};

export const TRANSFORM_LABELS: Record<FeatureInfo['transform'], string> = {
  LOG1P: 'Logaritmo, ln(1 + x)',
  RECENCY: 'Recência, 1 ÷ (1 + dias ÷ 30)',
  IDENTITY: 'Valor direto (ausente = média do treino)',
};

export const BAND_DESCRIPTIONS: Record<ScoreBand, string> = {
  A: 'maior propensão',
  B: 'propensão alta',
  C: 'propensão média',
  D: 'propensão baixa',
  E: 'menor propensão',
};

export const BANDS: readonly ScoreBand[] = ['A', 'B', 'C', 'D', 'E'];

export const GROUP_BY_LABELS: Record<RecoveryGroupBy, string> = {
  NONE: 'Total',
  CREDITOR: 'Credor',
  PORTFOLIO: 'Carteira',
  ASSIGNEE: 'Responsável',
};

/** Motivos de treino não executado ou modelo reprovado (`TrainingPolicy`, `TrainingService`). */
export const TRAINING_REASON_LABELS: Record<string, string> = {
  min_cases: 'menos casos com desfecho conhecido do que o mínimo',
  min_positives: 'menos casos que pagaram do que o mínimo',
  single_reference_date: 'histórico com uma única data de retrato (sem como validar em período posterior)',
  training_partition_too_small: 'poucos pagantes ou não pagantes no período de treino',
  validation_partition_too_small: 'poucos pagantes ou não pagantes no período de validação',
  training_error: 'falha numérica no ajuste do modelo',
  not_converged: 'o ajuste não convergiu',
  validation_auc_below_minimum: 'AUC de validação abaixo da mínima',
};

export const ALERT_KIND_LABELS: Record<string, string> = {
  score_psi: 'Distribuição do score',
  feature_psi: 'Distribuição da variável',
  realized_auc: 'AUC realizada da safra',
};

/** Mensagens para os códigos de erro do módulo (as demais usam o texto do servidor). */
export const SCORING_ERROR_LABELS: Record<string, string> = {
  'scoring.legal_basis_missing': 'O score está desabilitado: registre a base legal do tratamento em Parâmetros do score.',
  'scoring.model.self_promotion': 'Quem pediu o treino ou criou o scorecard não pode decidir sobre ele. Peça a outro gestor (segregação de funções).',
  'scoring.model.not_challenger': 'Este modelo não é mais o desafiante: atualize a tela para ver a situação atual.',
  'scoring.case_not_scorable': 'Este caso não recebe score (encerrado, no jurídico ou devedor anonimizado).',
  'scoring.calculation_failed': 'Não foi possível calcular o score agora. Tente novamente em instantes.',
  'scoring.band_cutoffs_invalid': 'Os cortes das faixas devem ser decrescentes (A > B > C > D), entre 1 e 1000.',
  'scorecard.bins_overlap': 'Há faixas sobrepostas numa mesma variável.',
  'scorecard.bin_invalid': 'Há faixa vazia: o limite inicial precisa ser menor que o final.',
  'scorecard.feature_duplicated': 'Cada variável pode aparecer em uma regra só.',
  'scorecard.points_invalid': 'Pontos fora do limite permitido.',
};

export function featureLabel(features: readonly FeatureInfo[], name: string): string {
  return features.find((f) => f.name === name)?.label ?? name;
}
