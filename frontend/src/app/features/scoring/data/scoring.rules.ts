import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { formatAmount, toAmount } from '../../../shared/components/amount/amount';
import { ChartRow } from '../../analytics/components/chart.models';
import { ALERT_KIND_LABELS, BANDS, TRAINING_REASON_LABELS, featureLabel } from './scoring.labels';
import {
  BandCalibration,
  Decimal,
  Decile,
  EvaluationMetrics,
  ExpectedRecoveryLine,
  FeatureInfo,
  LogisticDefinitionView,
  ModelView,
  MonitoringAlert,
  MonitoringStatus,
  MonitoringView,
  RealizedCohort,
  RunView,
  ScoreBand,
  ScoreFactor,
  ScoreReviewView,
  ScorecardBin,
  ScorecardDefinitionView,
  ScorecardRequest,
  ScorecardRule,
  ScoringSettingsRequest,
  ScoringSettingsView,
  TrainingDetails,
} from './scoring.models';

const PERCENT1 = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
const INTEGER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });
const DEC2 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const DEC3 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
const DEC4 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
const SIGNED2 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: 'exceptZero' });
const SIGNED_INT = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0, signDisplay: 'exceptZero' });

/** Número de um `BigDecimal` serializado (nulo ou texto inválido → nulo). */
export function dec(value: Decimal | null | undefined): number | null {
  return toAmount(value);
}

/** Fração como percentual com 1 casa ("41,2%"); ausente → "—". */
export function percent(value: Decimal | null | undefined): string {
  const n = dec(value);
  return n === null ? '—' : PERCENT1.format(n);
}

export function integer(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : INTEGER.format(value);
}

/** Métricas de discriminação (AUC, Gini, KS, PSI) com 3 casas. */
export function metric(value: Decimal | null | undefined): string {
  const n = dec(value);
  return n === null ? '—' : DEC3.format(n);
}

export function brier(value: Decimal | null | undefined): string {
  const n = dec(value);
  return n === null ? '—' : DEC4.format(n);
}

export function decimal2(value: Decimal | null | undefined): string {
  const n = dec(value);
  return n === null ? '—' : DEC2.format(n);
}

/** Score em pontos → probabilidade (o score é a probabilidade × 1000). */
export function scoreProbability(points: number): number {
  return points / 1000;
}

/** Faixa de um score pelos cortes A, B, C e D (espelho de `BandCutoffs#band`). */
export function bandFor(score: number, cutoffs: readonly number[]): ScoreBand {
  const [a, b, c, d] = cutoffs;
  return score >= a ? 'A' : score >= b ? 'B' : score >= c ? 'C' : score >= d ? 'D' : 'E';
}

/** Cortes válidos: decrescentes entre 1 e 1000 (A > B > C > D > 0). */
export function cutoffsValid(cutoffs: readonly (number | null)[]): boolean {
  if (cutoffs.length !== 4 || cutoffs.some((c) => c === null || !Number.isInteger(c))) {
    return false;
  }
  const [a, b, c, d] = cutoffs as number[];
  return a <= 1000 && a > b && b > c && c > d && d > 0;
}

/** Texto de cada faixa ("A: score ≥ 400 (≥ 40,0%)"…, "E: abaixo de 75 (< 7,5%)"). */
export function bandRanges(cutoffs: readonly number[]): { band: ScoreBand; text: string }[] {
  return BANDS.map((band, i) =>
    i < 4
      ? { band, text: `score ≥ ${cutoffs[i]} (chance ≥ ${PERCENT1.format(scoreProbability(cutoffs[i]))})` }
      : { band, text: `score abaixo de ${cutoffs[3]} (chance < ${PERCENT1.format(scoreProbability(cutoffs[3]))})` },
  );
}

/** Valores do formulário de parâmetros (frações em percentual para leitura). */
export interface SettingsForm {
  outcomeDays: number | null;
  minCases: number | null;
  minPositives: number | null;
  minPartitionPositives: number | null;
  validationSharePct: number | null;
  l2Lambda: number | null;
  minAuc: number | null;
  trainingWindowDays: number | null;
  snapshotIntervalDays: number | null;
  publishMinDelta: number | null;
  psiWarning: number | null;
  psiAlert: number | null;
  cutA: number | null;
  cutB: number | null;
  cutC: number | null;
  cutD: number | null;
  defaultPaidSharePct: number | null;
  autoTrain: boolean;
}

export function settingsForm(s: ScoringSettingsView): SettingsForm {
  const pct = (v: Decimal) => round((dec(v) ?? 0) * 100, 2);
  return {
    outcomeDays: s.outcomeDays,
    minCases: s.minCases,
    minPositives: s.minPositives,
    minPartitionPositives: s.minPartitionPositives,
    validationSharePct: pct(s.validationShare),
    l2Lambda: dec(s.l2Lambda),
    minAuc: dec(s.minAuc),
    trainingWindowDays: s.trainingWindowDays,
    snapshotIntervalDays: s.snapshotIntervalDays,
    publishMinDelta: s.publishMinDelta,
    psiWarning: dec(s.psiWarning),
    psiAlert: dec(s.psiAlert),
    cutA: s.bandCutoffs[0],
    cutB: s.bandCutoffs[1],
    cutC: s.bandCutoffs[2],
    cutD: s.bandCutoffs[3],
    defaultPaidSharePct: pct(s.defaultPaidShare),
    autoTrain: s.autoTrain,
  };
}

export function settingsRequest(f: SettingsForm): ScoringSettingsRequest {
  const n = (v: number | null) => v ?? 0;
  return {
    outcomeDays: n(f.outcomeDays),
    minCases: n(f.minCases),
    minPositives: n(f.minPositives),
    minPartitionPositives: n(f.minPartitionPositives),
    validationShare: round(n(f.validationSharePct) / 100, 4),
    l2Lambda: n(f.l2Lambda),
    minAuc: n(f.minAuc),
    trainingWindowDays: n(f.trainingWindowDays),
    snapshotIntervalDays: n(f.snapshotIntervalDays),
    publishMinDelta: n(f.publishMinDelta),
    psiWarning: n(f.psiWarning),
    psiAlert: n(f.psiAlert),
    bandCutoffs: [n(f.cutA), n(f.cutB), n(f.cutC), n(f.cutD)],
    defaultPaidShare: round(n(f.defaultPaidSharePct) / 100, 4),
    autoTrain: f.autoTrain,
  };
}

/**
 * Regras que cruzam campos (espelho de `ScoringSettings.Parameters`): pagantes ≤ casos, janela de
 * treino ≥ 2× a de desfecho, PSI de atenção < PSI de alerta e cortes decrescentes.
 */
export const settingsCrossValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const f = group.getRawValue() as SettingsForm;
  const errors: ValidationErrors = {};
  if (f.minCases !== null && f.minPositives !== null && f.minPositives > f.minCases) {
    errors['positivesAboveCases'] = true;
  }
  if (f.outcomeDays !== null && f.trainingWindowDays !== null && f.trainingWindowDays < f.outcomeDays * 2) {
    errors['trainingWindow'] = true;
  }
  if (f.psiWarning !== null && f.psiAlert !== null && f.psiWarning >= f.psiAlert) {
    errors['psiOrder'] = true;
  }
  if (!cutoffsValid([f.cutA, f.cutB, f.cutC, f.cutD])) {
    errors['cutoffs'] = true;
  }
  return Object.keys(errors).length ? errors : null;
};

export const SETTINGS_CROSS_MESSAGES: Record<string, string> = {
  positivesAboveCases: 'O mínimo de casos que pagaram não pode passar do mínimo de casos com desfecho.',
  trainingWindow: 'A janela de treino precisa ter ao menos o dobro da janela de desfecho.',
  psiOrder: 'O PSI de atenção precisa ser menor que o PSI de alerta.',
  cutoffs: 'Os cortes das faixas devem ser inteiros decrescentes (A > B > C > D), entre 1 e 1000.',
};

/** Linhas "o que muda" do resumo antes de salvar (só os campos alterados). */
export function settingsChanges(before: ScoringSettingsRequest, after: ScoringSettingsRequest): { label: string; value: string }[] {
  const labels: Record<keyof ScoringSettingsRequest, [string, (v: never) => string]> = {
    outcomeDays: ['Janela de desfecho', (v: number) => `${v} dias`],
    minCases: ['Mínimo de casos com desfecho', (v: number) => integer(v)],
    minPositives: ['Mínimo de casos que pagaram', (v: number) => integer(v)],
    minPartitionPositives: ['Mínimo por partição', (v: number) => integer(v)],
    validationShare: ['Fração de validação', (v: number) => percent(v)],
    l2Lambda: ['Regularização (λ)', (v: number) => String(v).replace('.', ',')],
    minAuc: ['AUC mínima', (v: number) => decimal2(v)],
    trainingWindowDays: ['Janela de treino', (v: number) => `${v} dias`],
    snapshotIntervalDays: ['Intervalo dos retratos', (v: number) => `${v} dias`],
    publishMinDelta: ['Variação mínima para publicar', (v: number) => `${v} pontos`],
    psiWarning: ['PSI de atenção', (v: number) => decimal2(v)],
    psiAlert: ['PSI de alerta', (v: number) => decimal2(v)],
    bandCutoffs: ['Cortes das faixas (A, B, C, D)', (v: number[]) => v.join(' · ')],
    defaultPaidShare: ['Fração paga típica', (v: number) => percent(v)],
    autoTrain: ['Treino mensal automático', (v: boolean) => (v ? 'Ligado' : 'Desligado')],
  };
  const out: { label: string; value: string }[] = [];
  for (const key of Object.keys(labels) as (keyof ScoringSettingsRequest)[]) {
    const a = before[key];
    const b = after[key];
    if (JSON.stringify(a) !== JSON.stringify(b)) {
      const [label, fmt] = labels[key];
      out.push({ label, value: `${fmt(a as never)} → ${fmt(b as never)}` });
    }
  }
  return out;
}

/** Ações de governança disponíveis num modelo para o usuário atual. */
export interface ModelActions {
  promote: boolean;
  reject: boolean;
  /** Motivo de a decisão não estar disponível a um gestor (segregação de funções, escopo). */
  blockedReason: string | null;
}

export function modelActions(model: ModelView, ctx: { userId: string | null; canManage: boolean; allCreditors: boolean }): ModelActions {
  if (model.status !== 'CHALLENGER' || !ctx.canManage) {
    return { promote: false, reject: false, blockedReason: null };
  }
  if (!ctx.allCreditors) {
    return { promote: false, reject: false, blockedReason: 'A decisão sobre modelos exige acesso a todos os credores.' };
  }
  if (model.trainedBy && model.trainedBy === ctx.userId) {
    return {
      promote: false,
      reject: true,
      blockedReason: 'Você pediu o treino ou criou este scorecard: a promoção precisa ser feita por outro gestor (segregação de funções).',
    };
  }
  return { promote: true, reject: true, blockedReason: null };
}

export function isScorecard(definition: Record<string, unknown>): definition is Record<string, unknown> & ScorecardDefinitionView {
  return Array.isArray((definition as { rules?: unknown }).rules);
}

export function isLogistic(definition: Record<string, unknown>): definition is Record<string, unknown> & LogisticDefinitionView {
  return Array.isArray((definition as { terms?: unknown }).terms);
}

/** Faixa de uma regra em texto ("31 a 90", "até 30", "361 ou mais"); `[de, até)`. */
export function binText(bin: ScorecardBin): string {
  const f = (v: number) => INTEGER.format(v);
  if (bin.from === null && bin.to === null) {
    return 'qualquer valor';
  }
  if (bin.from === null) {
    return `abaixo de ${f(bin.to!)}`;
  }
  if (bin.to === null) {
    return `${f(bin.from)} ou mais`;
  }
  return `de ${f(bin.from)} até antes de ${f(bin.to)}`;
}

export function signedPoints(points: number): string {
  return SIGNED_INT.format(points);
}

/** Termos da regressão em ordem de efeito (|coeficiente| desc). */
export function sortedTerms(definition: LogisticDefinitionView): LogisticDefinitionView['terms'] {
  return [...definition.terms].sort((a, b) => Math.abs(b.coefficient) - Math.abs(a.coefficient));
}

/** Linha da comparação de métricas: maior é melhor, salvo o Brier. */
export interface MetricComparison {
  key: string;
  label: string;
  challenger: string;
  champion: string;
  /** `better`/`worse`/`equal` para o desafiante; nulo sem os dois valores. */
  verdict: 'better' | 'worse' | 'equal' | null;
}

const COMPARED: { key: keyof EvaluationMetrics; label: string; lowerIsBetter?: boolean; format: (v: number | null) => string }[] = [
  { key: 'auc', label: 'AUC', format: metric },
  { key: 'gini', label: 'Gini', format: metric },
  { key: 'ks', label: 'KS', format: metric },
  { key: 'brier', label: 'Brier (menor é melhor)', lowerIsBetter: true, format: brier },
];

export function compareMetrics(challenger: EvaluationMetrics | undefined, champion: EvaluationMetrics | undefined): MetricComparison[] {
  return COMPARED.map((m) => {
    const a = (challenger?.[m.key] as number | null | undefined) ?? null;
    const b = (champion?.[m.key] as number | null | undefined) ?? null;
    let verdict: MetricComparison['verdict'] = null;
    if (a !== null && b !== null) {
      const diff = m.lowerIsBetter ? b - a : a - b;
      verdict = Math.abs(diff) < 0.0005 ? 'equal' : diff > 0 ? 'better' : 'worse';
    }
    return { key: m.key, label: m.label, challenger: m.format(a), champion: m.format(b), verdict };
  });
}

export const VERDICT_TEXT: Record<'better' | 'worse' | 'equal', string> = {
  better: 'Desafiante melhor',
  worse: 'Desafiante pior',
  equal: 'Equivalentes',
};

/** Calibração por faixa: previsto × observado (gráfico de barras com 2 séries). */
export function calibrationRows(bands: readonly BandCalibration[]): ChartRow[] {
  return bands.map((b) => ({
    key: b.band,
    label: `Faixa ${b.band}`,
    values: [b.meanPredicted, b.observedRate ?? 0],
    display: [percent(b.meanPredicted), b.observedRate === null ? '—' : percent(b.observedRate)],
    note: `${integer(b.positives)} pagaram de ${integer(b.cases)} casos`,
  }));
}

export function calibrationSummary(bands: readonly BandCalibration[]): string {
  const valid = bands.filter((b) => b.observedRate !== null && b.cases > 0);
  if (!valid.length) {
    return 'Sem casos apurados por faixa.';
  }
  const worst = valid.reduce((w, b) => (Math.abs(b.meanPredicted - b.observedRate!) > Math.abs(w.meanPredicted - w.observedRate!) ? b : w));
  return `Chance prevista × taxa observada de pagamento por faixa. Maior diferença na faixa ${worst.band}: previsto ${percent(worst.meanPredicted)}, observado ${percent(worst.observedRate)}.`;
}

/** Lift por decil (1 = 10% de maior score). */
export function liftRows(deciles: readonly Decile[]): ChartRow[] {
  return deciles.map((d) => ({
    key: String(d.decile),
    label: `${d.decile}º decil`,
    values: [d.lift],
    display: [decimal2(d.lift) + '×'],
    note: `${percent(d.rate)} pagaram · ${percent(d.cumulativeCapture)} dos pagantes acumulados`,
  }));
}

export type PsiLevel = MonitoringStatus;

export function psiLevel(value: number | null, warning: number, alert: number): PsiLevel {
  if (value === null) {
    return 'STABLE';
  }
  return value >= alert ? 'ALERT' : value >= warning ? 'WARNING' : 'STABLE';
}

export const PSI_LEVEL_TEXT: Record<PsiLevel, string> = {
  STABLE: 'estável',
  WARNING: 'atenção',
  ALERT: 'alerta',
};

/** PSI por variável, da maior para a menor (barras horizontais). */
export function featurePsiRows(featurePsi: Record<string, number>, features: readonly FeatureInfo[], warning: number, alert: number): ChartRow[] {
  return Object.entries(featurePsi)
    .sort(([, a], [, b]) => b - a)
    .map(([name, value]) => ({
      key: name,
      label: featureLabel(features, name),
      values: [value],
      display: [metric(value)],
      note: PSI_LEVEL_TEXT[psiLevel(value, warning, alert)],
    }));
}

/** PSI do score por relatório, do mais antigo ao mais recente (colunas). */
export function scorePsiRows(reports: readonly MonitoringView[], warning: number, alert: number): ChartRow[] {
  return [...reports]
    .filter((r) => dec(r.scorePsi) !== null)
    .sort((a, b) => a.referenceDate.localeCompare(b.referenceDate))
    .map((r) => {
      const v = dec(r.scorePsi)!;
      return { key: r.id, label: shortDate(r.referenceDate), values: [v], display: [metric(v)], note: `${PSI_LEVEL_TEXT[psiLevel(v, warning, alert)]} · ${integer(r.population)} casos` };
    });
}

/** AUC realizada do campeão por safra apurada (colunas), com a do desafiante na nota. */
export function realizedAucRows(cohorts: readonly RealizedCohort[]): ChartRow[] {
  return cohorts
    .filter((c) => c.auc !== null)
    .map((c) => ({
      key: c.referenceDate,
      label: shortDate(c.referenceDate),
      values: [c.auc!],
      display: [metric(c.auc)],
      note:
        `${integer(c.positives)} pagaram de ${integer(c.cases)}` +
        (c.challengerAuc !== null ? ` · desafiante (sombra) ${metric(c.challengerAuc)} em ${integer(c.challengerCases)} casos` : ''),
    }));
}

/** Última safra apurada do relatório mais recente que tiver alguma. */
export function latestCohort(reports: readonly MonitoringView[]): RealizedCohort | null {
  for (const r of [...reports].sort((a, b) => b.referenceDate.localeCompare(a.referenceDate))) {
    if (r.realized.length) {
      return r.realized[r.realized.length - 1];
    }
  }
  return null;
}

export function alertText(a: MonitoringAlert, features: readonly FeatureInfo[]): string {
  const subject = a.kind === 'feature_psi' ? featureLabel(features, a.subject) : a.kind === 'realized_auc' ? `safra de ${shortDate(a.subject)}` : 'score';
  const what = ALERT_KIND_LABELS[a.kind] ?? a.kind;
  const sign = a.kind === 'realized_auc' ? '<' : '≥';
  const measure = a.kind === 'realized_auc' ? 'AUC' : 'PSI';
  return `${what} (${subject}): ${measure} ${metric(a.value)} ${sign} ${metric(a.threshold)}`;
}

export interface RecoveryTotals {
  cases: number;
  expectedPayers: number;
  realizedPayers: number;
  expectedAmount: number;
  realizedAmount: number;
  ratio: number | null;
}

export function recoveryTotals(lines: readonly ExpectedRecoveryLine[]): RecoveryTotals {
  const t = lines.reduce(
    (acc, l) => ({
      cases: acc.cases + l.cases,
      expectedPayers: acc.expectedPayers + (dec(l.expectedPayers) ?? 0),
      realizedPayers: acc.realizedPayers + l.realizedPayers,
      expectedAmount: acc.expectedAmount + (dec(l.expectedAmount) ?? 0),
      realizedAmount: acc.realizedAmount + (dec(l.realizedAmount) ?? 0),
    }),
    { cases: 0, expectedPayers: 0, realizedPayers: 0, expectedAmount: 0, realizedAmount: 0 },
  );
  return { ...t, ratio: t.expectedAmount > 0 ? t.realizedAmount / t.expectedAmount : null };
}

/** Esperado × recuperado por grupo (barras com 2 séries); a razão vai na nota. */
export function recoveryRows(lines: readonly ExpectedRecoveryLine[], name: (key: string) => string): ChartRow[] {
  return lines.map((l) => ({
    key: l.groupKey,
    label: name(l.groupKey),
    values: [dec(l.expectedAmount) ?? 0, dec(l.realizedAmount) ?? 0],
    display: [formatAmount(l.expectedAmount), formatAmount(l.realizedAmount)],
    note: `recuperado ÷ esperado ${ratioText(l.ratio)} · ${integer(l.cases)} casos`,
  }));
}

/** Razão "1,08×" (recuperado ÷ esperado); sem esperado → "—". */
export function ratioText(ratio: Decimal | null | undefined): string {
  const n = dec(ratio);
  return n === null ? '—' : DEC2.format(n) + '×';
}

/** Resumo de uma execução para a lista. */
export function runSummary(run: RunView): string {
  if (run.kind === 'TRAINING') {
    const d = run.details as TrainingDetails;
    if (run.status === 'RUNNING') {
      return 'Treinando…';
    }
    if (run.status === 'INSUFFICIENT_DATA') {
      return `Histórico insuficiente: ${integer(d.distinctCases ?? 0)} casos e ${integer(d.distinctPositiveCases ?? 0)} pagantes (mínimo ${integer(d.minCases ?? 0)} e ${integer(d.minPositives ?? 0)})`;
    }
    if (run.status === 'FAILED') {
      return reasonsText(d.reasons) || 'O treino falhou';
    }
    const failures = d.failures ?? [];
    return failures.length
      ? `Modelo reprovado: ${reasonsText(failures)}`
      : `Novo desafiante · AUC de validação ${metric(d.validationAuc ?? null)}`;
  }
  const parts = [`${integer(run.scored)} escorados`];
  if (run.skipped) {
    parts.push(`${integer(run.skipped)} ignorados`);
  }
  if (run.bandChanges) {
    parts.push(`${integer(run.bandChanges)} mudaram de faixa`);
  }
  if (run.failures) {
    parts.push(`${integer(run.failures)} falha(s)`);
  }
  return parts.join(' · ');
}

export function reasonsText(reasons: readonly string[] | undefined): string {
  return (reasons ?? []).map((r) => TRAINING_REASON_LABELS[r] ?? r).join('; ');
}

/** Duração legível entre dois instantes ("2 min 5 s", "< 1 s"). */
export function durationText(startedAt: string, finishedAt: string | null): string {
  if (!finishedAt) {
    return 'em andamento';
  }
  const seconds = Math.max(0, Math.round((Date.parse(finishedAt) - Date.parse(startedAt)) / 1000));
  if (seconds < 1) {
    return '< 1 s';
  }
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return [h ? `${h} h` : '', m ? `${m} min` : '', s && !h ? `${s} s` : ''].filter(Boolean).join(' ');
}

/** Contribuição de um fator: pontos no scorecard ("+20 pts"), log-odds na regressão ("+0,35"). */
export function contributionText(f: ScoreFactor): string {
  if (f.points !== null) {
    return `${signedPoints(f.points)} pts`;
  }
  const n = dec(f.contribution) ?? 0;
  return SIGNED2.format(n);
}

/** Largura relativa (0–100) da barra de contribuição de cada fator. */
export function contributionWidths(factors: readonly ScoreFactor[]): Map<string, number> {
  const magnitude = (f: ScoreFactor) => Math.abs(f.points ?? dec(f.contribution) ?? 0);
  const max = Math.max(0, ...factors.map(magnitude));
  return new Map(factors.map((f) => [f.feature, max > 0 ? Math.round((magnitude(f) / max) * 100) : 0]));
}

/** "B (310) → A (420)" de uma revisão. */
export function reviewChange(r: ScoreReviewView): string {
  const before = r.previousScore === null ? 'sem score' : `${r.previousBand} (${r.previousScore})`;
  return `${before} → ${r.newBand} (${r.newScore})`;
}

/** Formulário de regra do scorecard (texto nos limites para aceitar vazio = sem limite). */
export interface BinForm {
  from: number | null;
  to: number | null;
  points: number | null;
}

export interface RuleForm {
  feature: string;
  missingPoints: number | null;
  bins: BinForm[];
}

export const SCORECARD_LIMITS = { rules: 30, bins: 20, points: 500, basePoints: 5000, pdoMin: 1, pdoMax: 200 } as const;

/**
 * Problemas de uma regra (espelho de `ScorecardDefinition.Rule`): faixa vazia, sobreposta ou
 * pontos fora do limite. Lista vazia = regra válida.
 */
export function ruleProblems(rule: RuleForm): string[] {
  const problems: string[] = [];
  if (!rule.feature) {
    problems.push('Escolha a variável.');
  }
  if (rule.bins.length > SCORECARD_LIMITS.bins) {
    problems.push(`No máximo ${SCORECARD_LIMITS.bins} faixas por variável.`);
  }
  const outOfLimit = (p: number | null) => p === null || !Number.isInteger(p) || Math.abs(p) > SCORECARD_LIMITS.points;
  if (outOfLimit(rule.missingPoints) || rule.bins.some((b) => outOfLimit(b.points))) {
    problems.push(`Pontos inteiros entre −${SCORECARD_LIMITS.points} e ${SCORECARD_LIMITS.points}.`);
  }
  const lower = (b: BinForm) => b.from ?? Number.NEGATIVE_INFINITY;
  const upper = (b: BinForm) => b.to ?? Number.POSITIVE_INFINITY;
  if (rule.bins.some((b) => lower(b) >= upper(b))) {
    problems.push('Em cada faixa o início precisa ser menor que o fim.');
  }
  const sorted = [...rule.bins].sort((a, b) => lower(a) - lower(b));
  for (let i = 1; i < sorted.length; i++) {
    if (upper(sorted[i - 1]) > lower(sorted[i])) {
      problems.push('Há faixas sobrepostas.');
      break;
    }
  }
  return problems;
}

/** Variáveis repetidas entre as regras. */
export function duplicatedFeatures(rules: readonly RuleForm[]): string[] {
  const seen = new Set<string>();
  const dup = new Set<string>();
  for (const r of rules) {
    if (r.feature && seen.has(r.feature)) {
      dup.add(r.feature);
    }
    seen.add(r.feature);
  }
  return [...dup];
}

/** Probabilidade (fração) → chance (odds) da âncora do scorecard. */
export function oddsFromProbability(p: number): number {
  return round(p / (1 - p), 6);
}

export function probabilityFromOdds(odds: number): number {
  return odds / (1 + odds);
}

export function rulesFromDefinition(definition: ScorecardDefinitionView): RuleForm[] {
  return definition.rules.map((r) => ({
    feature: r.feature,
    missingPoints: r.missingPoints,
    bins: r.bins.map((b) => ({ from: b.from, to: b.to, points: b.points })),
  }));
}

export function scorecardRequest(v: {
  description: string;
  basePoints: number;
  pdo: number;
  anchorPoints: number;
  anchorProbabilityPct: number;
  rules: RuleForm[];
}): ScorecardRequest {
  return {
    description: v.description.trim() || null,
    basePoints: v.basePoints,
    pdo: v.pdo,
    anchorPoints: v.anchorPoints,
    anchorOdds: oddsFromProbability(v.anchorProbabilityPct / 100),
    rules: v.rules.map(
      (r): ScorecardRule => ({
        feature: r.feature,
        missingPoints: r.missingPoints ?? 0,
        bins: r.bins.map((b) => ({ from: b.from, to: b.to, points: b.points ?? 0 })),
      }),
    ),
  };
}

/** `yyyy-MM-dd` → `dd/MM` (eixos e rótulos curtos). */
export function shortDate(iso: string): string {
  const [, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}`;
}

function round(value: number, digits: number): number {
  const f = Math.pow(10, digits);
  return Math.round(value * f) / f;
}

/** Período máximo do recuperado × esperado (`ScoringOperationsService.MAX_RANGE_DAYS`). */
export const RECOVERY_MAX_DAYS = 400;

/** Período padrão: últimos 180 dias até `today` (yyyy-MM-dd). */
export function defaultRecoveryRange(today: string): { from: string; to: string } {
  const [y, m, d] = today.split('-').map(Number);
  const from = new Date(Date.UTC(y, m - 1, d - 180));
  return { from: from.toISOString().slice(0, 10), to: today };
}

/** Problema do período (vazio, invertido ou longo demais) ou nulo. */
export function recoveryRangeProblem(from: string | null, to: string | null): string | null {
  if (!from || !to) {
    return 'Informe o início e o fim do período.';
  }
  if (to < from) {
    return 'O fim do período precisa ser igual ou posterior ao início.';
  }
  const days = (Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / 86_400_000;
  return days > RECOVERY_MAX_DAYS ? `Período máximo de ${RECOVERY_MAX_DAYS} dias.` : null;
}
