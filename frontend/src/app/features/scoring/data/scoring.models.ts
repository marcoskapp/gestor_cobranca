/**
 * Contratos da API do módulo scoring (`/api/v1/scoring`), espelho dos records de
 * `scoring.application.port.in`. Valores `BigDecimal` chegam como número ou texto (`Decimal`).
 */

export type Decimal = number | string;

export type LegalBasis = 'LEGITIMATE_INTEREST' | 'CREDIT_PROTECTION';
export type ScoreBand = 'A' | 'B' | 'C' | 'D' | 'E';

/** `ScoringSettingsUseCases.SettingsView`. */
export interface ScoringSettingsView {
  outcomeDays: number;
  minCases: number;
  minPositives: number;
  minPartitionPositives: number;
  validationShare: Decimal;
  l2Lambda: Decimal;
  minAuc: Decimal;
  trainingWindowDays: number;
  snapshotIntervalDays: number;
  publishMinDelta: number;
  psiWarning: Decimal;
  psiAlert: Decimal;
  /** Cortes A, B, C e D em pontos (score = probabilidade × 1000). */
  bandCutoffs: number[];
  defaultPaidShare: Decimal;
  autoTrain: boolean;
  legalBasis: LegalBasis | null;
  legalBasisReference: string | null;
  legalBasisRegisteredAt: string | null;
  legalBasisRegisteredBy: string | null;
  scoringEnabled: boolean;
}

/** `ScoringAdminController.SettingsRequest`. */
export interface ScoringSettingsRequest {
  outcomeDays: number;
  minCases: number;
  minPositives: number;
  minPartitionPositives: number;
  validationShare: number;
  l2Lambda: number;
  minAuc: number;
  trainingWindowDays: number;
  snapshotIntervalDays: number;
  publishMinDelta: number;
  psiWarning: number;
  psiAlert: number;
  bandCutoffs: number[];
  defaultPaidShare: number;
  autoTrain: boolean;
}

export interface LegalBasisRequest {
  legalBasis: LegalBasis;
  reference: string;
}

export type RunKind = 'BATCH' | 'TRAINING';
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'INSUFFICIENT_DATA';
export type RunTrigger = 'SCHEDULED' | 'MANUAL';

/** `ScoringOperations.RunView`. `details` traz os números do treino (ver `TrainingDetails`). */
export interface RunView {
  id: string;
  kind: RunKind;
  trigger: RunTrigger;
  status: RunStatus;
  referenceDate: string;
  referenceAt: string;
  snapshot: boolean;
  outcomeDays: number;
  championModelId: string | null;
  challengerModelId: string | null;
  resultModelId: string | null;
  scored: number;
  skipped: number;
  published: number;
  bandChanges: number;
  failures: number;
  details: Record<string, unknown>;
  requestedBy: string | null;
  startedAt: string;
  finishedAt: string | null;
  cohortMeasuredAt: string | null;
}

/** Campos de `RunView.details` gravados pelo `TrainingService`. */
export interface TrainingDetails {
  observations?: number;
  distinctCases?: number;
  distinctPositiveCases?: number;
  minCases?: number;
  minPositives?: number;
  reasons?: string[];
  modelId?: string;
  validationAuc?: number | null;
  failures?: string[];
}

export type RunKindFilter = RunKind | null;

/** `TrainingRequest`: o treino é assíncrono e acompanhado pela execução. */
export interface TrainingRequestView {
  runId: string;
  status: RunStatus;
}

/** `ModelMetrics.BandCalibration`. */
export interface BandCalibration {
  band: ScoreBand;
  cases: number;
  positives: number;
  meanPredicted: number;
  observedRate: number | null;
}

/** `ModelMetrics.Decile`. */
export interface Decile {
  decile: number;
  cases: number;
  positives: number;
  rate: number;
  lift: number;
  cumulativeCapture: number;
}

/** Métricas de uma amostra (`TrainingService#evaluate`). */
export interface EvaluationMetrics {
  rows: number;
  positives: number;
  auc: number | null;
  gini: number | null;
  ks: number | null;
  brier: number | null;
  meanPredicted: number | null;
  observedRate: number | null;
  calibration: BandCalibration[];
  liftByDecile: Decile[];
}

/** `ScoringModel.metrics`: treino da regressão ou retroteste do scorecard. */
export interface ModelMetricsView {
  validation?: EvaluationMetrics;
  training?: EvaluationMetrics;
  championOnValidation?: EvaluationMetrics;
  championModelId?: string;
  iterations?: number;
  converged?: boolean;
  lambda?: Decimal;
  backtest?: boolean;
  backtestDays?: number;
}

export type ModelKind = 'SCORECARD' | 'LOGISTIC_REGRESSION';
export type ModelStatus = 'CHALLENGER' | 'CHAMPION' | 'RETIRED' | 'REJECTED' | 'SUPERSEDED' | 'FAILED_VALIDATION';

/** `ScorecardDefinition` serializado. Faixa `[from, to)`; nulo = sem limite. */
export interface ScorecardBin {
  from: number | null;
  to: number | null;
  points: number;
}

export interface ScorecardRule {
  feature: string;
  bins: ScorecardBin[];
  missingPoints: number;
}

export interface ScorecardDefinitionView {
  basePoints: number;
  pdo: number;
  anchorPoints: number;
  anchorOdds: number;
  rules: ScorecardRule[];
}

/** `LogisticDefinition` serializado. */
export interface LogisticTerm {
  feature: string;
  coefficient: number;
  mean: number;
  std: number;
}

export interface LogisticDefinitionView {
  intercept: number;
  terms: LogisticTerm[];
}

/** `ModelUseCases.ModelView`. */
export interface ModelView {
  id: string;
  modelVersion: number;
  kind: ModelKind;
  status: ModelStatus;
  description: string | null;
  outcomeDays: number;
  definition: Record<string, unknown>;
  bandCutoffs: number[];
  paidShare: Decimal;
  trainingFrom: string | null;
  trainingTo: string | null;
  validationFrom: string | null;
  validationTo: string | null;
  trainingRows: number;
  trainingPositives: number;
  validationRows: number;
  validationPositives: number;
  metrics: ModelMetricsView;
  failureReasons: string[];
  trainedBy: string | null;
  trainedAt: string;
  promotedAt: string | null;
  promotedBy: string | null;
  endedAt: string | null;
}

/** `ScoringModelController.ScorecardRequest`. */
export interface ScorecardRequest {
  description: string | null;
  basePoints: number;
  pdo: number;
  anchorPoints: number;
  anchorOdds: number;
  rules: ScorecardRule[];
}

export type DecisionKind = 'PROMOTE' | 'REJECT';

/** `ModelUseCases.DecisionView`: `comparison` é o que quem decidiu viu. */
export interface DecisionView {
  id: string;
  modelId: string;
  decision: DecisionKind;
  previousChampionId: string | null;
  justification: string;
  comparison: Record<string, unknown>;
  decidedBy: string;
  decidedAt: string;
}

export interface FeatureInfo {
  name: string;
  label: string;
  transform: 'LOG1P' | 'RECENCY' | 'IDENTITY';
}

/** Catálogo fechado de variáveis (`Feature`) e o que nunca entra no score. */
export interface FeatureCatalog {
  allowed: FeatureInfo[];
  neverUsed: string[];
  purpose: string;
}

/** Safra apurada de um relatório de monitoramento (`MonitoringService.Safra`). */
export interface RealizedCohort {
  referenceDate: string;
  cases: number;
  positives: number;
  auc: number | null;
  challengerAuc: number | null;
  challengerCases: number;
  meanPredicted: number;
  observedRate: number;
  bands: BandCalibration[];
}

export type AlertLevel = 'WARNING' | 'ALERT';

/** `MonitoringService.Alert`. */
export interface MonitoringAlert {
  level: AlertLevel;
  kind: 'score_psi' | 'feature_psi' | 'realized_auc' | string;
  subject: string;
  value: number;
  threshold: number;
}

export type MonitoringStatus = 'STABLE' | 'WARNING' | 'ALERT';

/** `ScoringOperations.MonitoringView`. */
export interface MonitoringView {
  id: string;
  runId: string;
  modelId: string;
  referenceDate: string;
  status: MonitoringStatus;
  population: number;
  scorePsi: Decimal | null;
  featurePsi: Record<string, number>;
  realized: RealizedCohort[];
  alerts: MonitoringAlert[];
}

export type RecoveryGroupBy = 'NONE' | 'CREDITOR' | 'PORTFOLIO' | 'ASSIGNEE';

export interface ExpectedRecoveryFilter {
  from: string;
  to: string;
  creditorId: string | null;
  portfolioId: string | null;
  groupBy: RecoveryGroupBy;
}

/** `ExpectedRecoveryLine`: `ratio` = realizado ÷ esperado (valor), nulo sem esperado. */
export interface ExpectedRecoveryLine {
  groupKey: string;
  cases: number;
  expectedPayers: Decimal;
  realizedPayers: number;
  expectedAmount: Decimal;
  realizedAmount: Decimal;
  ratio: Decimal | null;
}

export interface ExpectedRecoveryReport {
  from: string;
  to: string;
  groupBy: RecoveryGroupBy;
  lines: ExpectedRecoveryLine[];
}

export type FactorEffect = 'INCREASES' | 'DECREASES' | 'NEUTRAL';

/** `CaseScoreUseCases.Factor`: contribuição em log-odds (pontos no scorecard). */
export interface ScoreFactor {
  feature: string;
  label: string;
  value: number | null;
  contribution: Decimal;
  points: number | null;
  effect: FactorEffect;
  explanation: string;
}

export interface ScoreNotice {
  purpose: string;
  legalBasis: LegalBasis | null;
  baseline: string;
}

export type ScoreTrigger = 'BATCH' | 'ON_DEMAND' | 'EVENT' | 'REVIEW';

/** `CaseScoreUseCases.CaseScoreView`. */
export interface CaseScoreView {
  caseId: string;
  creditorId: string;
  portfolioId: string;
  score: number;
  band: ScoreBand;
  probability: Decimal;
  outcomeDays: number;
  modelId: string;
  modelVersion: number;
  modelKind: ModelKind;
  referenceDate: string;
  calculatedAt: string;
  trigger: ScoreTrigger;
  topFactors: ScoreFactor[];
  factors: ScoreFactor[];
  notice: ScoreNotice;
}

/** Score do caso num snapshot; `paid` nulo enquanto o desfecho não foi apurado. */
export interface ScoreHistoryEntry {
  referenceDate: string;
  score: number;
  band: ScoreBand;
  modelId: string;
  paid: boolean | null;
}

export type ReviewOrigin = 'DATA_SUBJECT_REQUEST' | 'OPERATION_REQUEST' | 'QUALITY_CHECK';

export interface ReviewRequest {
  origin: ReviewOrigin;
  requestReference: string | null;
}

/** `CaseScoreUseCases.ReviewView`; `score` só vem na resposta da revisão. */
export interface ScoreReviewView {
  id: string;
  caseId: string;
  origin: ReviewOrigin;
  requestReference: string | null;
  previousScore: number | null;
  previousBand: ScoreBand | null;
  previousModelId: string | null;
  newScore: number;
  newBand: ScoreBand;
  newModelId: string;
  reviewedBy: string;
  reviewedAt: string;
  score: CaseScoreView | null;
}
