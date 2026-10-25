import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ExperimentsApi } from '../data/campaign.api';
import { CONTROL_ARM, METRIC_HINTS, METRIC_LABELS, RESULT_WARNING_LABELS } from '../data/campaign.labels';
import { ExperimentMetric, ExperimentResults, ExperimentView, MetricResult } from '../data/campaign.models';
import { armLabel, count, differenceScale, differenceValue, estimateScale, isRateMetric, metricValue, position } from '../data/campaign.rules';

interface ArmRow {
  arm: string;
  label: string;
  baseline: boolean;
  debtors: number;
  result: MetricResult;
  /** Posições (0–100%) do intervalo e da estimativa na escala comum. */
  ci: { lower: number; upper: number; estimate: number } | null;
  diff: { lower: number; upper: number; estimate: number; zero: number } | null;
  text: string;
  diffText: string | null;
  verdict: 'better' | 'worse' | 'none' | null;
}

interface MetricBlock {
  metric: ExperimentMetric;
  primary: boolean;
  scaleText: [string, string];
  diffScaleText: [string, string];
  rows: ArmRow[];
}

/**
 * Resultados do teste A/B por braço, com intervalo de confiança de 95% desenhado como barra e
 * sempre descrito em texto (estimativa, limites e se a diferença é significativa), avisos de
 * amostra e a explicação curta do método.
 */
@Component({
  selector: 'app-experiment-results',
  imports: [FormsModule, Icon, EmptyState, Skeleton, AppDatePipe],
  templateUrl: './experiment-results.html',
  styleUrl: './experiment-results.scss',
})
export class ExperimentResultsView {
  readonly experiment = input.required<ExperimentView>();

  private readonly api = inject(ExperimentsApi);
  protected readonly mature = signal(true);
  protected readonly metricLabels = METRIC_LABELS;
  protected readonly metricHints = METRIC_HINTS;
  protected readonly count = count;

  protected readonly results = rxResource({
    params: () => ({ id: this.experiment().id, mature: this.mature() }),
    stream: ({ params }) => this.api.results(params.id, params.mature),
  });
  protected readonly data = valueOr(this.results, undefined);

  protected readonly generalWarnings = computed(() => (this.data()?.warnings ?? []).map((w) => RESULT_WARNING_LABELS[w] ?? w));
  protected readonly totalDebtors = computed(() => (this.data()?.arms ?? []).reduce((sum, a) => sum + a.debtors, 0));
  protected readonly blocks = computed<MetricBlock[]>(() => {
    const r = this.data();
    return r ? blocks(r, this.experiment()) : [];
  });

  protected warningText(code: string): string {
    return RESULT_WARNING_LABELS[code] ?? code;
  }
}

function blocks(r: ExperimentResults, e: ExperimentView): MetricBlock[] {
  const metrics = [...new Set(r.arms.flatMap((a) => a.metrics.map((m) => m.metric)))];
  metrics.sort((a, b) => (a === r.primaryMetric ? -1 : b === r.primaryMetric ? 1 : 0));
  return metrics.map((metric) => {
    const results = r.arms.map((a) => ({ arm: a, m: a.metrics.find((x) => x.metric === metric)! })).filter((x) => x.m);
    const scale = estimateScale(metric, results.map((x) => x.m.upper));
    const dScale = differenceScale(results.flatMap((x) => [x.m.difference?.lower ?? null, x.m.difference?.upper ?? null]));
    const rows = results.map(({ arm, m }): ArmRow => {
      const d = m.difference;
      const hasCi = m.estimate !== null && m.lower !== null && m.upper !== null;
      const baseline = arm.arm === r.baselineArm;
      const estimateText = metricValue(metric, m.estimate);
      const ciText = hasCi ? ` (IC 95%: ${metricValue(metric, m.lower)} a ${metricValue(metric, m.upper)})` : ' (sem intervalo)';
      const base = isRateMetric(metric) && m.successes !== null ? ` · ${count(m.successes)} de ${count(m.denominator)} devedores` : ` · ${count(m.denominator)} devedores`;
      return {
        arm: arm.arm,
        label: armLabel(arm.arm, e.variants),
        baseline,
        debtors: arm.debtors,
        result: m,
        ci: hasCi ? { lower: position(m.lower!, scale), upper: position(m.upper!, scale), estimate: position(m.estimate!, scale) } : null,
        diff: d ? { lower: position(d.lower, dScale), upper: position(d.upper, dScale), estimate: position(d.estimate, dScale), zero: position(0, dScale) } : null,
        text: `${estimateText}${ciText}${base}`,
        diffText: d
          ? `${differenceValue(metric, d.estimate)} (IC 95%: ${differenceValue(metric, d.lower)} a ${differenceValue(metric, d.upper)}) — ${
              d.significant ? (d.estimate > 0 ? 'melhor que a referência, diferença significativa' : 'pior que a referência, diferença significativa') : 'sem diferença significativa'
            }`
          : baseline
            ? 'Braço de referência das diferenças'
            : null,
        verdict: d ? (d.significant ? (d.estimate > 0 ? 'better' : 'worse') : 'none') : null,
      };
    });
    return {
      metric,
      primary: metric === r.primaryMetric,
      scaleText: [metricValue(metric, scale.min), metricValue(metric, scale.max)],
      diffScaleText: [differenceValue(metric, dScale.min), differenceValue(metric, dScale.max)],
      rows: rows.sort((a, b) => Number(b.baseline) - Number(a.baseline) || Number(b.arm === CONTROL_ARM) - Number(a.arm === CONTROL_ARM)),
    };
  });
}
