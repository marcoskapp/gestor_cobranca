import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, num, percent, ratioDetail } from '../data/analytics.format';
import { CASE_STATUS_LABELS, DELAY_BUCKET_LABELS } from '../data/analytics.labels';
import { CaseStatusRow, PortfolioRow, QueueRow } from '../data/analytics.models';
import { DrillTarget, casesTarget, drillAccess, ledgerTarget } from '../data/drill-down';
import { DrillLink } from '../components/drill-link';
import { urlFilter } from '../data/url-filter';

/**
 * Painel operacional do gestor (F11): carteira, saldo vencido e elegível, casos por estado e fila
 * pendente, com o recuperado do período quando o perfil vê relatórios. Posição na data do último
 * refresh; o período vale para a recuperação.
 */
@Component({
  selector: 'app-operational-panel',
  imports: [Amount, EmptyState, Skeleton, AppDatePipe, AnalyticsFilters, BarChart, DrillLink, InfoHint, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Painel operacional" subtitle="Carteira, saldos, casos e fila de trabalho da operação."
                     report="portfolio" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()"
                     (refreshed)="reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()" [options]="{ period: canRecovery(), creditor: true, portfolio: true }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar o painel" message="Verifique a conexão e tente novamente.">
          <button class="btn btn-primary" type="button" (click)="reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.totals;
        <section class="app-kpi-grid" aria-label="Indicadores da carteira" data-tour="analytics-kpis">
          <app-kpi-card label="Valor da carteira" info="analytics.portfolio.amount" data-tour="kpi-portfolio" [detail]="count(t.openTitles) + ' títulos em cobrança'">
            <app-amount [value]="t.portfolioAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Saldo vencido" info="analytics.portfolio.overdue" [detail]="percent(t.overdueShare) + ' da carteira · ' + count(t.overdueTitles) + ' títulos'">
            <app-amount [value]="t.overdueAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Saldo elegível" info="analytics.portfolio.eligible" data-tour="kpi-eligible" [detail]="percent(t.eligibleShare) + ' do saldo dos casos abertos'">
            <app-amount [value]="t.eligibleBalance" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Casos em aberto" info="analytics.portfolio.open-cases" [detail]="count(t.eligibleCases) + ' elegíveis para cobrança'">
            <app-drill-link [to]="links().openCases">{{ count(t.openCases) }}</app-drill-link>
          </app-kpi-card>
          <app-kpi-card label="Fila pendente" info="analytics.portfolio.pending-queue" data-tour="kpi-queue" [detail]="percent(t.pendingQueueShare) + ' · ' + ratioDetail(t.pendingQueueShare) + ' casos elegíveis'">
            <app-drill-link [to]="links().pendingQueue">{{ count(t.pendingQueueCases) }}</app-drill-link>
          </app-kpi-card>
          @if (recoveryReport(); as rec) {
            <app-kpi-card label="Recuperado no período" info="analytics.portfolio.recovered-period" [detail]="'Taxa ' + percent(rec.totals.recoveryRate) + ' · ' + ratioDetail(rec.totals.recoveryRate, 'money')">
              <app-drill-link [to]="links().recovered"><app-amount [value]="rec.totals.recoveredNet" [compact]="true" /></app-drill-link>
            </app-kpi-card>
          }
        </section>
        @if (r.referenceDate) {
          <p class="pn__hint">Posição em {{ r.referenceDate | appDate: 'date' }}. Valor recuperado = pagamentos confirmados, líquidos de estornos.</p>
        }
        <div class="pn__grid app-columns">
          <section class="app-card">
            <app-bar-chart title="Carteira por credor" info="analytics.portfolio.by-creditor" data-tour="analytics-chart" categoryHeader="Credor" [summary]="creditorSummary()"
                           [series]="[{ name: 'Valor da carteira', color: '--app-chart-1' }, { name: 'Saldo vencido', color: '--app-chart-2' }]"
                           [rows]="creditorRows()" noteHeader="Vencido / carteira" />
          </section>
          <section class="app-card">
            <app-bar-chart title="Carteira por faixa de atraso" info="analytics.portfolio.by-delay" categoryHeader="Faixa" [summary]="bucketSummary()"
                           [series]="[{ name: 'Valor da carteira', color: '--app-chart-1' }]" [rows]="bucketRows()" noteHeader="Saldo elegível" />
          </section>
        </div>
        <div class="pn__grid app-columns">
          <section class="app-card" aria-labelledby="op-status">
            <h2 class="app-card__title" id="op-status">Casos por estado <app-info-hint indicator="analytics.portfolio.cases-by-status" label="Casos por estado" /></h2>
            <div class="pn__table-wrap">
              <table class="pn__table">
                <thead><tr><th scope="col">Estado</th><th scope="col" class="num">Casos</th><th scope="col" class="num">Elegíveis</th><th scope="col" class="num">Saldo em aberto</th><th scope="col" class="num">Saldo elegível <app-info-hint indicator="analytics.portfolio.eligible" label="Saldo elegível" /></th></tr></thead>
                <tbody>
                  @for (s of statusRows(); track s.row.status) {
                    <tr>
                      <th scope="row"><app-drill-link [to]="s.all">{{ statusLabels[s.row.status] ?? s.row.status }}</app-drill-link></th>
                      <td class="num app-numeric"><app-drill-link [to]="s.all">{{ count(s.row.cases) }}</app-drill-link></td>
                      <td class="num app-numeric"><app-drill-link [to]="s.eligible">{{ count(s.row.eligibleCases) }}</app-drill-link></td>
                      <td class="num"><app-amount [value]="s.row.openBalance" /></td>
                      <td class="num"><app-amount [value]="s.row.eligibleBalance" /></td>
                    </tr>
                  } @empty {
                    <tr><td colspan="5">Nenhum caso em aberto.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
          <section class="app-card" aria-labelledby="op-queue">
            <h2 class="app-card__title" id="op-queue">Fila pendente <app-info-hint indicator="analytics.portfolio.pending-queue" label="Fila pendente" /></h2>
            <p class="pn__hint">Casos elegíveis aguardando na fila (sem negociador atribuído).</p>
            <div class="pn__table-wrap">
              <table class="pn__table">
                <thead><tr><th scope="col">Fila</th><th scope="col" class="num">Casos</th><th scope="col" class="num">Saldo elegível</th></tr></thead>
                <tbody>
                  @for (q of queueRows(); track q.row.queueId) {
                    <tr><th scope="row"><app-drill-link [to]="q.link">{{ q.row.label }}</app-drill-link></th><td class="num app-numeric"><app-drill-link [to]="q.link">{{ count(q.row.cases) }}</app-drill-link></td><td class="num"><app-amount [value]="q.row.eligibleBalance" /></td></tr>
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

  /**
   * Drill-down (data/drill-down.ts): casos não encerrados e na fila (sem negociador) vão à lista de casos; o recuperado,
   * ao razão (mesma base). Sem link: valor da carteira, saldo vencido e as barras por credor e faixa (títulos; a lista
   * ainda não filtra "em cobrança" nem as faixas do painel) e o saldo elegível (soma do saldo elegível por título dos
   * casos; a elegibilidade da lista é do caso inteiro — outra base).
   */
  private readonly access = drillAccess();
  protected readonly links = computed(() => {
    const f = this.state.filter();
    const cases = (extra: Record<string, string>, label: string) => this.access(casesTarget(f, extra, label));
    return {
      openCases: cases({}, 'ver os casos em aberto na lista de casos'),
      pendingQueue: cases({ elegivel: 'true', atendimento: 'QUEUE' }, 'ver os casos elegíveis aguardando na fila'),
      recovered: this.access(ledgerTarget(f, {}, 'ver os recebimentos e estornos do período no razão')),
    };
  });
  protected readonly statusRows = computed<{ row: CaseStatusRow; all: DrillTarget | null; eligible: DrillTarget | null }[]>(() => {
    const f = this.state.filter();
    return (this.report()?.casesByStatus ?? []).map((row) => ({
      row,
      all: this.access(casesTarget(f, { situacao: row.status }, 'ver os casos nesta situação')),
      eligible: this.access(casesTarget(f, { situacao: row.status, elegivel: 'true' }, 'ver os casos elegíveis nesta situação')),
    }));
  });
  protected readonly queueRows = computed<{ row: QueueRow; link: DrillTarget | null }[]>(() => {
    const f = this.state.filter();
    return (this.report()?.pendingQueue ?? []).map((row) => ({
      row,
      link: row.queueId === '-' ? null : this.access(casesTarget(f, { fila: row.queueId, elegivel: 'true', atendimento: 'QUEUE' }, 'ver os casos desta fila')),
    }));
  });

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
