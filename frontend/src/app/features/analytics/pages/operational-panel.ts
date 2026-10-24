import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { KpiCard } from '../components/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, num, percent, ratioDetail } from '../data/analytics.format';
import { CASE_STATUS_LABELS, DELAY_BUCKET_LABELS } from '../data/analytics.labels';
import { PortfolioRow } from '../data/analytics.models';
import { urlFilter } from '../data/url-filter';

/**
 * Painel operacional do gestor (F11): carteira, saldo vencido e elegível, casos por estado e fila
 * pendente, com o recuperado do período quando o perfil vê relatórios. Posição na data do último
 * refresh; o período vale para a recuperação.
 */
@Component({
  selector: 'app-operational-panel',
  imports: [MatButtonModule, Amount, EmptyState, Skeleton, AppDatePipe, AnalyticsFilters, BarChart, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Painel operacional" subtitle="Carteira, saldos, casos e fila de trabalho da operação."
                     report="portfolio" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()"
                     (refreshed)="reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()" [options]="{ period: canRecovery(), creditor: true, portfolio: true }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="error" title="Não foi possível carregar o painel" message="Verifique a conexão e tente novamente.">
          <button mat-flat-button type="button" (click)="reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.totals;
        <section class="pn__kpis" aria-label="Indicadores da carteira" data-tour="analytics-kpis">
          <app-kpi-card label="Valor da carteira" [detail]="count(t.openTitles) + ' títulos em cobrança'">
            <app-amount [value]="t.portfolioAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Saldo vencido" [detail]="percent(t.overdueShare) + ' da carteira · ' + count(t.overdueTitles) + ' títulos'">
            <app-amount [value]="t.overdueAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Saldo elegível" [detail]="percent(t.eligibleShare) + ' do saldo dos casos abertos'">
            <app-amount [value]="t.eligibleBalance" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Casos em aberto" [detail]="count(t.eligibleCases) + ' elegíveis para cobrança'">
            {{ count(t.openCases) }}
          </app-kpi-card>
          <app-kpi-card label="Fila pendente" [detail]="percent(t.pendingQueueShare) + ' · ' + ratioDetail(t.pendingQueueShare) + ' casos elegíveis'">
            {{ count(t.pendingQueueCases) }}
          </app-kpi-card>
          @if (recoveryReport(); as rec) {
            <app-kpi-card label="Recuperado no período" [detail]="'Taxa ' + percent(rec.totals.recoveryRate) + ' · ' + ratioDetail(rec.totals.recoveryRate, 'money')">
              <app-amount [value]="rec.totals.recoveredNet" [compact]="true" />
            </app-kpi-card>
          }
        </section>
        @if (r.referenceDate) {
          <p class="pn__hint">Posição em {{ r.referenceDate | appDate: 'date' }}. Valor recuperado = pagamentos confirmados, líquidos de estornos.</p>
        }
        <div class="pn__grid">
          <section class="app-card">
            <app-bar-chart title="Carteira por credor" categoryHeader="Credor" [summary]="creditorSummary()"
                           [series]="[{ name: 'Valor da carteira', color: '--app-chart-1' }, { name: 'Saldo vencido', color: '--app-chart-2' }]"
                           [rows]="creditorRows()" noteHeader="Vencido / carteira" />
          </section>
          <section class="app-card">
            <app-bar-chart title="Carteira por faixa de atraso" categoryHeader="Faixa" [summary]="bucketSummary()"
                           [series]="[{ name: 'Valor da carteira', color: '--app-chart-1' }]" [rows]="bucketRows()" noteHeader="Saldo elegível" />
          </section>
        </div>
        <div class="pn__grid">
          <section class="app-card" aria-labelledby="op-status">
            <h2 class="app-card__title" id="op-status">Casos por estado</h2>
            <div class="pn__table-wrap">
              <table class="pn__table">
                <thead><tr><th scope="col">Estado</th><th scope="col" class="num">Casos</th><th scope="col" class="num">Elegíveis</th><th scope="col" class="num">Saldo em aberto</th><th scope="col" class="num">Saldo elegível</th></tr></thead>
                <tbody>
                  @for (s of r.casesByStatus; track s.status) {
                    <tr>
                      <th scope="row">{{ statusLabels[s.status] ?? s.status }}</th>
                      <td class="num app-numeric">{{ count(s.cases) }}</td>
                      <td class="num app-numeric">{{ count(s.eligibleCases) }}</td>
                      <td class="num"><app-amount [value]="s.openBalance" /></td>
                      <td class="num"><app-amount [value]="s.eligibleBalance" /></td>
                    </tr>
                  } @empty {
                    <tr><td colspan="5">Nenhum caso em aberto.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
          <section class="app-card" aria-labelledby="op-queue">
            <h2 class="app-card__title" id="op-queue">Fila pendente</h2>
            <p class="pn__hint">Casos elegíveis aguardando na fila (sem negociador atribuído).</p>
            <div class="pn__table-wrap">
              <table class="pn__table">
                <thead><tr><th scope="col">Fila</th><th scope="col" class="num">Casos</th><th scope="col" class="num">Saldo elegível</th></tr></thead>
                <tbody>
                  @for (q of r.pendingQueue; track q.queueId) {
                    <tr><th scope="row">{{ q.label }}</th><td class="num app-numeric">{{ count(q.cases) }}</td><td class="num"><app-amount [value]="q.eligibleBalance" /></td></tr>
                  } @empty {
                    <tr><td colspan="3">Nenhum caso aguardando na fila.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        </div>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class OperationalPanel {
  private readonly api = inject(AnalyticsApi);
  private readonly auth = inject(AuthService);
  protected readonly state = urlFilter();
  protected readonly statusLabels = CASE_STATUS_LABELS;
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly ratioDetail = ratioDetail;
  protected readonly canRecovery = computed(() => this.auth.hasPermission(Permission.REPORT_VIEW));

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.portfolio(params) });
  protected readonly report = valueOr(this.data, undefined);
  protected readonly recovery = rxResource({
    params: () => ({ filter: this.state.filter(), allowed: this.canRecovery() }),
    stream: ({ params }) => (params.allowed ? this.api.recovery({ ...params.filter, groupBy: null }) : of(undefined)),
  });
  protected readonly recoveryReport = valueOr(this.recovery, undefined);

  protected readonly creditorRows = computed<ChartRow[]>(() =>
    (this.report()?.byCreditor ?? []).slice(0, 10).map((r) => ({
      key: r.key,
      label: r.label,
      values: [num(r.portfolioAmount), num(r.overdueAmount)],
      display: [formatAmount(r.portfolioAmount), formatAmount(r.overdueAmount)],
      note: `${percent(r.overdueShare)} vencido`,
    })),
  );
  protected readonly bucketRows = computed<ChartRow[]>(() =>
    (this.report()?.byDelayBucket ?? []).map((r: PortfolioRow) => ({
      key: r.key,
      label: DELAY_BUCKET_LABELS[r.key] ?? r.key,
      values: [num(r.portfolioAmount)],
      display: [formatAmount(r.portfolioAmount)],
      note: `elegível ${formatAmount(r.eligibleBalance)}`,
    })),
  );
  protected readonly creditorSummary = computed(() => {
    const rows = this.creditorRows();
    return rows.length
      ? `Valor da carteira e saldo vencido por credor. Maior carteira: ${rows[0].label}, ${rows[0].display[0]}.`
      : 'Valor da carteira e saldo vencido por credor.';
  });
  protected readonly bucketSummary = computed(() => {
    const rows = this.bucketRows();
    const top = [...rows].sort((a, b) => b.values[0] - a.values[0])[0];
    return top ? `Valor da carteira por faixa de atraso dos títulos. Maior concentração: ${top.label}, ${top.display[0]}.` : 'Valor da carteira por faixa de atraso.';
  });

  protected reload(): void {
    this.data.reload();
    this.recovery.reload();
  }
}
