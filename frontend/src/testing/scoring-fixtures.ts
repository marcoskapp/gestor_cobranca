import {
  CaseScoreView,
  EvaluationMetrics,
  ModelView,
  MonitoringView,
  RunView,
  ScoreFactor,
  ScoringSettingsView,
} from '../app/features/scoring/data/scoring.models';

export function settings(overrides: Partial<ScoringSettingsView> = {}): ScoringSettingsView {
  return {
    outcomeDays: 30,
    minCases: 500,
    minPositives: 50,
    minPartitionPositives: 10,
    validationShare: 0.3,
    l2Lambda: 1,
    minAuc: 0.6,
    trainingWindowDays: 365,
    snapshotIntervalDays: 7,
    publishMinDelta: 10,
    psiWarning: 0.1,
    psiAlert: 0.25,
    bandCutoffs: [400, 250, 150, 75],
    defaultPaidShare: 0.3,
    autoTrain: false,
    legalBasis: 'LEGITIMATE_INTEREST',
    legalBasisReference: 'LIA-2026-01 aprovada pelo DPO',
    legalBasisRegisteredAt: '2026-10-01T12:00:00Z',
    legalBasisRegisteredBy: 'u-9',
    scoringEnabled: true,
    ...overrides,
  };
}

export function evaluation(overrides: Partial<EvaluationMetrics> = {}): EvaluationMetrics {
  return {
    rows: 1200,
    positives: 180,
    auc: 0.72,
    gini: 0.44,
    ks: 0.33,
    brier: 0.11,
    meanPredicted: 0.15,
    observedRate: 0.15,
    calibration: [
      { band: 'A', cases: 100, positives: 45, meanPredicted: 0.45, observedRate: 0.45 },
      { band: 'E', cases: 500, positives: 20, meanPredicted: 0.05, observedRate: 0.04 },
    ],
    liftByDecile: [{ decile: 1, cases: 120, positives: 60, rate: 0.5, lift: 3.33, cumulativeCapture: 0.33 }],
    ...overrides,
  };
}

export function model(overrides: Partial<ModelView> = {}): ModelView {
  return {
    id: 'm-2',
    modelVersion: 2,
    kind: 'LOGISTIC_REGRESSION',
    status: 'CHALLENGER',
    description: 'Regressão logística L2 — treino de 2026-10-20',
    outcomeDays: 30,
    definition: { intercept: -1.5, terms: [{ feature: 'DAYS_OVERDUE', coefficient: -0.8, mean: 4, std: 1.2 }, { feature: 'ACTIVE_AGREEMENT', coefficient: 1.1, mean: 0.2, std: 0.4 }] },
    bandCutoffs: [400, 250, 150, 75],
    paidShare: 0.3,
    trainingFrom: '2026-01-01',
    trainingTo: '2026-07-31',
    validationFrom: '2026-09-01',
    validationTo: '2026-09-30',
    trainingRows: 3000,
    trainingPositives: 400,
    validationRows: 1200,
    validationPositives: 180,
    metrics: { validation: evaluation(), championOnValidation: evaluation({ auc: 0.65, gini: 0.3, ks: 0.25, brier: 0.12 }), converged: true, iterations: 8, lambda: 1 },
    failureReasons: [],
    trainedBy: 'u-2',
    trainedAt: '2026-10-20T03:00:00Z',
    promotedAt: null,
    promotedBy: null,
    endedAt: null,
    ...overrides,
  };
}

export function scorecardModel(overrides: Partial<ModelView> = {}): ModelView {
  return model({
    id: 'm-1',
    modelVersion: 1,
    kind: 'SCORECARD',
    status: 'CHAMPION',
    description: 'Scorecard padrão',
    definition: {
      basePoints: 500,
      pdo: 20,
      anchorPoints: 500,
      anchorOdds: 1 / 9,
      rules: [{ feature: 'DAYS_OVERDUE', bins: [{ from: null, to: 31, points: 30 }, { from: 31, to: 91, points: 15 }], missingPoints: 0 }],
    },
    metrics: {},
    trainingFrom: null,
    trainingTo: null,
    validationFrom: null,
    validationTo: null,
    trainedBy: null,
    ...overrides,
  });
}

export function factor(overrides: Partial<ScoreFactor> = {}): ScoreFactor {
  return {
    feature: 'EFFECTIVE_CONTACTS_90D',
    label: 'Contatos efetivos (com a pessoa certa) nos últimos 90 dias',
    value: 0,
    contribution: -0.5,
    points: -10,
    effect: 'DECREASES',
    explanation: 'Contatos efetivos (com a pessoa certa) nos últimos 90 dias: 0 — reduz a propensão de pagamento',
    ...overrides,
  };
}

export function caseScore(overrides: Partial<CaseScoreView> = {}): CaseScoreView {
  const f1 = factor();
  const f2 = factor({ feature: 'ACTIVE_AGREEMENT', label: 'Acordo em vigor', value: 1, contribution: 2.77, points: 40, effect: 'INCREASES', explanation: 'Acordo em vigor: sim — aumenta a propensão de pagamento' });
  return {
    caseId: 'k-1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    score: 312,
    band: 'B',
    probability: 0.3125,
    outcomeDays: 30,
    modelId: 'm-1',
    modelVersion: 1,
    modelKind: 'SCORECARD',
    referenceDate: '2026-10-25',
    calculatedAt: '2026-10-25T05:30:00Z',
    trigger: 'BATCH',
    topFactors: [f2, f1],
    factors: [f1, f2],
    notice: { purpose: 'Score usado somente para priorizar o atendimento.', legalBasis: 'LEGITIMATE_INTEREST', baseline: 'Contribuições em pontos do scorecard.' },
    ...overrides,
  };
}

export function run(overrides: Partial<RunView> = {}): RunView {
  return {
    id: 'r-1',
    kind: 'BATCH',
    trigger: 'SCHEDULED',
    status: 'COMPLETED',
    referenceDate: '2026-10-25',
    referenceAt: '2026-10-25T05:30:00Z',
    snapshot: true,
    outcomeDays: 30,
    championModelId: 'm-1',
    challengerModelId: null,
    resultModelId: null,
    scored: 1234,
    skipped: 3,
    published: 200,
    bandChanges: 45,
    failures: 0,
    details: {},
    requestedBy: null,
    startedAt: '2026-10-25T05:30:00Z',
    finishedAt: '2026-10-25T05:32:05Z',
    cohortMeasuredAt: null,
    ...overrides,
  };
}

export function monitoring(overrides: Partial<MonitoringView> = {}): MonitoringView {
  return {
    id: 'mo-1',
    runId: 'r-1',
    modelId: 'm-1',
    referenceDate: '2026-10-25',
    status: 'ALERT',
    population: 1234,
    scorePsi: 0.08,
    featurePsi: { DAYS_OVERDUE: 0.31, OPEN_BALANCE: 0.02 },
    realized: [
      {
        referenceDate: '2026-09-13',
        cases: 1000,
        positives: 150,
        auc: 0.7,
        challengerAuc: 0.73,
        challengerCases: 1000,
        meanPredicted: 0.16,
        observedRate: 0.15,
        bands: [{ band: 'A', cases: 100, positives: 40, meanPredicted: 0.45, observedRate: 0.4 }],
      },
    ],
    alerts: [{ level: 'ALERT', kind: 'feature_psi', subject: 'DAYS_OVERDUE', value: 0.31, threshold: 0.25 }],
    ...overrides,
  };
}

export const FEATURES = {
  allowed: [
    { name: 'DAYS_OVERDUE', label: 'Dias de atraso do título mais antigo', transform: 'LOG1P' as const },
    { name: 'OPEN_BALANCE', label: 'Saldo em aberto (R$)', transform: 'LOG1P' as const },
    { name: 'ACTIVE_AGREEMENT', label: 'Acordo em vigor', transform: 'IDENTITY' as const },
  ],
  neverUsed: ['nome', 'endereço, CEP, cidade, UF, DDD ou outra localização'],
  purpose: 'Score usado somente para priorizar o atendimento.',
};
