import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { ColumnChart } from '../components/column-chart';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, days, num, percent, ratioDetail, unavailable } from '../data/analytics.format';
import { GROUP_BY_LABELS, groupLabel } from '../data/analytics.labels';
import { AnalyticsFilter } from '../data/analytics.models';
import { urlFilter } from '../data/url-filter';
import { DrillTarget, bucketPeriod, drillAccess, ledgerTarget, withRow } from '../data/drill-down';
import { DrillLink } from '../components/drill-link';
import { peopleGroupBy } from './people-scope';

interface Links {
  all: DrillTarget | null;
  payments: DrillTarget | null;
  reversals: DrillTarget | null;
}

const GROUPS = ['MONTH', 'WEEK', 'DAY', 'CREDITOR', 'PORTFOLIO', 'OPERATOR', 'TEAM', 'CHANNEL', 'ORIGIN', 'DELAY_BUCKET'];

/**
 * Recuperação (F18): valor recuperado = pago confirmado, líquido de estornos (acordo não pago não
 * entra), taxa sobre o saldo elegível no início do período, tempo até o 1º recebimento e custo de
 * comissão por real recuperado, por período ou por credor, carteira, operador, canal e origem.
 */
@Component({
  selector: 'app-recovery-panel',
  imports: [Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, ColumnChart, DrillLink, InfoHint, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Recuperação" subtitle="Valor efetivamente pago (confirmado, líquido de estornos), taxa, tempo e custo."
                     report="recovery" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()"
                             [options]="{ period: true, creditor: true, portfolio: true, people: true, channel: true, groupBy: groups() }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a recuperação" message="Verifique os filtros e tente novamente.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.totals;
        <section class="app-kpi-grid" aria-label="Indicadores de recuperação" data-tour="analytics-kpis">
          <app-kpi-card label="Recuperado líquido" info="analytics.recovery.net" data-tour="kpi-recovered" [detail]="count(t.receipts) + ' recebimentos confirmados'">
            <app-drill-link [to]="totalLinks().all"><app-amount [value]="t.recoveredNet" [compact]="true" /></app-drill-link>
          </app-kpi-card>
          <app-kpi-card label="Taxa de recuperação" info="analytics.recovery.rate" data-tour="kpi-rate" [detail]="rateDetail()">
            {{ percent(t.recoveryRate) }}
          </app-kpi-card>
          <app-kpi-card label="Estornos" info="analytics.recovery.reversals" [detail]="percent(t.reversalShare) + ' do recebido bruto (' + money(t.recoveredGross) + ')'">
            <app-drill-link [to]="totalLinks().reversals"><app-amount [value]="t.reversed" [compact]="true" /></app-drill-link>
          </app-kpi-card>
          <app-kpi-card label="Tempo até o 1º recebimento" info="analytics.recovery.time-to-receipt" data-tour="kpi-time" [detail]="'Mediana de ' + count(r.timeToReceipt.agreements) + ' acordos · p90 ' + days(r.timeToReceipt.p90Days)">
            {{ days(r.timeToReceipt.medianDays) }}
          </app-kpi-card>
          <app-kpi-card label="Comissão por real recuperado" info="analytics.recovery.cost" [detail]="costDetail()">
            {{ costValue() }}
          </app-kpi-card>
        </section>
        <section class="app-card" data-tour="analytics-chart">
          @if (temporal()) {
            <app-column-chart [title]="'Recuperado líquido por ' + groupName().toLowerCase()" info="analytics.recovery.by-group" [summary]="summary()"
                              [series]="{ name: 'Recuperado líquido', color: '--app-chart-1' }" [rows]="rows()" />
          } @else {
            <app-bar-chart [title]="'Recuperado líquido por ' + groupName().toLowerCase()" info="analytics.recovery.by-group" [categoryHeader]="groupName()"
                           [summary]="summary()" [series]="[{ name: 'Recuperado líquido', color: '--app-chart-1' }]" [rows]="rows()"
                           noteHeader="Taxa de recuperação" />
          }
        </section>
        <section class="app-card" aria-labelledby="rc-table">
          <h2 class="app-card__title" id="rc-table">Detalhe por {{ groupName().toLowerCase() }}</h2>
          <div class="pn__table-wrap">
            <table class="pn__table">
              <thead>
                <tr><th scope="col">{{ groupName() }}</th><th scope="col" class="num">Recebido <app-info-hint indicator="analytics.recovery.gross" label="Recebido" /></th><th scope="col" class="num">Estornado <app-info-hint indicator="analytics.recovery.reversals" label="Estornado" /></th><th scope="col" class="num">Recuperado líquido <app-info-hint indicator="analytics.recovery.net" label="Recuperado líquido" /></th><th scope="col" class="num">Recebimentos <app-info-hint indicator="analytics.recovery.receipts" label="Recebimentos" /></th>@if (showRate()) {<th scope="col" class="num">Taxa (base: saldo elegível no início) <app-info-hint indicator="analytics.recovery.rate" label="Taxa de recuperação" /></th>}</tr>
              </thead>
              <tbody>
                @for (row of r.rows; track row.key) {
                  @let link = rowLinks().get(row.key);
                  <tr>
                    <th scope="row"><app-drill-link [to]="link?.all ?? null">{{ label(row.key, row.label) }}</app-drill-link></th>
                    <td class="num"><app-drill-link [to]="link?.payments ?? null"><app-amount [value]="row.recoveredGross" /></app-drill-link></td>
                    <td class="num"><app-drill-link [to]="link?.reversals ?? null"><app-amount [value]="row.reversed" /></app-drill-link></td>
                    <td class="num"><app-drill-link [to]="link?.all ?? null"><app-amount [value]="row.recoveredNet" /></app-drill-link></td>
                    <td class="num app-numeric"><app-drill-link [to]="link?.payments ?? null">{{ count(row.receipts) }}</app-drill-link></td>
                    @if (showRate()) {
                      <td class="num">{{ percent(row.recoveryRate) }}<span class="sub">{{ ratioDetail(row.recoveryRate, 'money') }}</span></td>
                    }
                  </tr>
                } @empty {
                  <tr><td [attr.colspan]="showRate() ? 6 : 5">Nenhum recebimento no período.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class RecoveryPanel {
  private readonly api = inject(AnalyticsApi);
  private readonly auth = inject(AuthService);
  protected readonly groups = computed(() => peopleGroupBy(GROUPS, this.auth));
  protected readonly state = urlFilter(GROUPS, 'MONTH');
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly days = days;
  protected readonly ratioDetail = ratioDetail;
  protected readonly money = formatAmount;

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.recovery(params) });
  protected readonly report = valueOr(this.data, undefined);
  protected readonly groupBy = computed(() => this.state.filter().groupBy ?? 'MONTH');
  protected readonly groupName = computed(() => GROUP_BY_LABELS[this.groupBy()] ?? 'Grupo');
  protected readonly temporal = computed(() => ['DAY', 'WEEK', 'MONTH'].includes(this.groupBy()));

  /** Taxa por linha só quando o agrupamento tem saldo inicial (credor, carteira, pessoa). */
  protected readonly showRate = computed(() => (this.report()?.rows ?? []).some((r) => r.recoveryRate.unavailableReason !== 'not_segmentable'));
  protected readonly rows = computed<ChartRow[]>(() =>
    (this.report()?.rows ?? []).map((r) => ({
      key: r.key,
      label: this.label(r.key, r.label),
      values: [Math.max(0, num(r.recoveredNet))],
      display: [formatAmount(r.recoveredNet)],
      note: r.recoveryRate.value !== null ? `taxa ${percent(r.recoveryRate)} (${ratioDetail(r.recoveryRate, 'money')})` : undefined,
      link: this.rowLinks().get(r.key)?.all ?? null,
    })),
  );
  protected readonly summary = computed(() => {
    const t = this.report()?.totals;
    const rows = this.rows();
    const top = [...rows].sort((a, b) => b.values[0] - a.values[0])[0];
    const base = t ? `Total recuperado líquido de ${formatAmount(t.recoveredNet)} no período.` : '';
    return top ? `${base} Maior valor: ${top.label}, ${top.display[0]}.` : base;
  });
  protected readonly rateDetail = computed(() => {
    const rate = this.report()?.totals.recoveryRate;
    const reason = unavailable(rate);
    return reason && rate?.denominator === null ? reason : `${ratioDetail(rate, 'money')} de saldo elegível no início`;
  });
  protected readonly costValue = computed(() => percent(this.report()?.cost.commissionPerReal));
  protected readonly costDetail = computed(() => {
    const cost = this.report()?.cost;
    if (!cost) {
      return '';
    }
    const reason = unavailable(cost.commissionPerReal);
    return reason ?? `${ratioDetail(cost.commissionPerReal, 'money')} · ${count(cost.statements)} extratos`;
  });

  /**
   * Drill-down ao razão de comissão (recebimentos e estornos do settlement: a mesma base do recuperado). Linhas por
   * credor, carteira e período levam ao razão com o recorte da linha; por operador, equipe, canal, origem e faixa de
   * atraso não há link (o razão não tem essas dimensões, que vêm do acordo). Taxa, tempo até o 1º recebimento e
   * comissão por real não têm lista que represente a base.
   */
  private readonly access = drillAccess();
  private links(f: AnalyticsFilter | null, period?: { from: string; to: string }): Links {
    if (!f) {
      return { all: null, payments: null, reversals: null };
    }
    return {
      all: this.access(ledgerTarget(f, {}, 'ver os recebimentos e estornos no razão', period)),
      payments: this.access(ledgerTarget(f, { tipo: 'PAYMENT' }, 'ver os recebimentos no razão', period)),
      reversals: this.access(ledgerTarget(f, { tipo: 'REVERSAL' }, 'ver os estornos no razão', period)),
    };
  }
  protected readonly totalLinks = computed(() => this.links(this.state.filter()));
  protected readonly rowLinks = computed(() => {
    const f = this.state.filter();
    const groupBy = this.groupBy();
    const temporal = ['DAY', 'WEEK', 'MONTH'].includes(groupBy);
    const out = new Map<string, Links>();
    for (const row of this.report()?.rows ?? []) {
      if (temporal) {
        const period = bucketPeriod(groupBy, row.key, f);
        out.set(row.key, period ? this.links(f, period) : this.links(null));
      } else if (groupBy === 'CREDITOR' || groupBy === 'PORTFOLIO') {
        out.set(row.key, this.links(withRow(f, groupBy, row.key)));
      }
    }
    return out;
  });

  protected label(key: string, serverLabel: string): string {
    return groupLabel(this.groupBy(), key, serverLabel);
  }
}
