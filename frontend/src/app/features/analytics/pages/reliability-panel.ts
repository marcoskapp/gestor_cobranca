import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { KpiCard } from '../components/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, decimal, percent, ratioDetail, seconds } from '../data/analytics.format';
import { CountRow } from '../data/analytics.models';
import { urlFilter } from '../data/url-filter';

/**
 * Confiabilidade da operação: falhas de integração (instruções de pagamento, chamadas, extratos),
 * mensagens com falha, duplicidades, divergências de conciliação, processamentos em falha (DEAD) e
 * tempo das importações de carteira — cada taxa com a base.
 */
@Component({
  selector: 'app-reliability-panel',
  imports: [Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, InfoHint, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Confiabilidade" subtitle="Falhas de integração, mensagens, conciliação, processamentos e importações."
                     report="reliability" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()" [options]="{ period: true, creditor: true, portfolio: true, channel: true }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a confiabilidade" message="Tente novamente em instantes.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        <section class="pn__kpis" aria-label="Indicadores de confiabilidade" data-tour="analytics-kpis">
          <app-kpi-card label="Falha em instruções de pagamento" info="analytics.reliability.instruction-failures" data-tour="kpi-instructions" [detail]="ratioDetail(r.integrations.instructionFailureRate) + ' geradas'">{{ percent(r.integrations.instructionFailureRate) }}</app-kpi-card>
          <app-kpi-card label="Mensagens com falha" info="analytics.reliability.message-failures" data-tour="kpi-messages" [detail]="ratioDetail(r.messages.failureRate) + ' enviadas'">{{ percent(r.messages.failureRate) }}</app-kpi-card>
          <app-kpi-card label="Chamadas com falha" info="analytics.reliability.call-failures" [detail]="ratioDetail(r.integrations.callFailureRate) + ' chamadas'">{{ percent(r.integrations.callFailureRate) }}</app-kpi-card>
          <app-kpi-card label="Divergências em aberto" info="analytics.reliability.divergences" data-tour="kpi-divergences" [detail]="count(r.reconciliation.opened) + ' abertas no período'">{{ count(r.reconciliation.openNow) }}</app-kpi-card>
          @if (r.jobs; as j) {
            <app-kpi-card label="Processamentos em falha (DEAD)" info="analytics.reliability.dead-jobs" [detail]="percent(j.deadRate) + ' dos concluídos no período'">{{ count(j.deadNow) }}</app-kpi-card>
          }
          <app-kpi-card label="Importação (mediana)" info="analytics.reliability.import-time" [detail]="'p95 ' + seconds(r.imports.p95Seconds) + ' · ' + count(r.imports.batches) + ' lotes'">{{ seconds(r.imports.medianSeconds) }}</app-kpi-card>
        </section>
        <div class="pn__grid">
          <section class="app-card">
            <app-bar-chart title="Falhas de mensagens por causa" info="analytics.reliability.message-failures" categoryHeader="Causa" [summary]="summaryOf(r.messages.failuresByCode, 'falhas de mensagem')"
                           [series]="[{ name: 'Mensagens', color: '--app-chart-1' }]" [rows]="countRows(r.messages.failuresByCode)" />
          </section>
          <section class="app-card">
            <app-bar-chart title="Falhas de instruções de pagamento por causa" info="analytics.reliability.instruction-failures" categoryHeader="Causa"
                           [summary]="summaryOf(r.integrations.instructionFailuresByCode, 'falhas de instrução')"
                           [series]="[{ name: 'Instruções', color: '--app-chart-1' }]" [rows]="countRows(r.integrations.instructionFailuresByCode)" />
          </section>
        </div>
        <div class="pn__grid">
          <section class="app-card" aria-labelledby="rl-rec" data-tour="analytics-reconciliation">
            <h2 class="app-card__title" id="rl-rec">Conciliação e duplicidades <app-info-hint indicator="analytics.reliability.divergences" label="Divergências de conciliação" /></h2>
            <table class="pn__table">
              <tbody>
                <tr><th scope="row">Divergências abertas no período</th><td class="num app-numeric">{{ count(r.reconciliation.opened) }}</td></tr>
                @for (d of r.reconciliation.openedByType; track d.key) {
                  <tr><th scope="row">&nbsp;&nbsp;{{ divergenceLabels[d.key] ?? d.key }}</th><td class="num app-numeric">{{ count(d.count) }}</td></tr>
                }
                <tr><th scope="row">Em aberto agora</th><td class="num">{{ count(r.reconciliation.openNow) }} · <app-amount [value]="r.reconciliation.openNowAmount" /></td></tr>
                <tr><th scope="row">Resolvidas no período (mediana)</th><td class="num">{{ count(r.reconciliation.resolved) }} · {{ decimal(r.reconciliation.medianResolutionHours) }} h</td></tr>
                <tr><th scope="row">Linhas de extrato duplicadas <app-info-hint indicator="analytics.reliability.duplicate-lines" label="Linhas de extrato duplicadas" /></th><td class="num">{{ percent(r.messages.duplicateLineRate) }}<span class="sub">{{ ratioDetail(r.messages.duplicateLineRate) }}</span></td></tr>
                <tr><th scope="row">Extratos com falha <app-info-hint indicator="analytics.reliability.statement-failures" label="Extratos com falha" /></th><td class="num app-numeric">{{ count(r.integrations.statementImportsFailed) }} de {{ count(r.integrations.statementImports) }}</td></tr>
                <tr><th scope="row">Conversas com SLA de 1ª resposta estourado <app-info-hint indicator="analytics.reliability.sla-breaches" label="SLA de 1ª resposta estourado" /></th><td class="num app-numeric">{{ count(r.messages.slaBreaches) }}</td></tr>
              </tbody>
            </table>
          </section>
          <section class="app-card" aria-labelledby="rl-jobs">
            <h2 class="app-card__title" id="rl-jobs">Processamentos e importações <app-info-hint indicator="analytics.reliability.import-time" label="Importação de carteiras" /></h2>
            <table class="pn__table">
              <tbody>
                @if (r.jobs; as j) {
                  @for (d of j.deadByType; track d.key) {
                    <tr><th scope="row"><code>{{ d.key }}</code></th><td class="num app-numeric">{{ count(d.count) }} em DEAD</td></tr>
                  } @empty {
                    <tr><th scope="row">Processamentos em DEAD</th><td class="num">nenhum</td></tr>
                  }
                }
                <tr><th scope="row">Lotes de importação</th><td class="num app-numeric">{{ count(r.imports.batches) }}</td></tr>
                <tr><th scope="row">Lotes com falha</th><td class="num">{{ percent(r.imports.failureRate) }}<span class="sub">{{ ratioDetail(r.imports.failureRate) }}</span></td></tr>
                <tr><th scope="row">Linhas com erro</th><td class="num">{{ percent(r.imports.rowErrorRate) }}<span class="sub">{{ ratioDetail(r.imports.rowErrorRate) }} linhas</span></td></tr>
                <tr><th scope="row">Tempo de processamento (mediana · p95)</th><td class="num">{{ seconds(r.imports.medianSeconds) }} · {{ seconds(r.imports.p95Seconds) }}</td></tr>
              </tbody>
            </table>
          </section>
        </div>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class ReliabilityPanel {
  private readonly api = inject(AnalyticsApi);
  protected readonly state = urlFilter();
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly decimal = decimal;
  protected readonly seconds = seconds;
  protected readonly ratioDetail = ratioDetail;
  protected readonly divergenceLabels: Record<string, string> = {
    AMOUNT_MISMATCH: 'Valor divergente',
    DUPLICATE_PAYMENT: 'Pagamento em duplicidade',
    UNMATCHED: 'Sem correspondência',
    NOT_CONFIRMED: 'Não confirmado',
    PARTIAL_REFUND: 'Devolução parcial',
  };

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.reliability(params) });
  protected readonly report = valueOr(this.data, undefined);
  protected readonly empty = computed(() => !this.report());

  protected countRows(rows: CountRow[]): ChartRow[] {
    return rows.slice(0, 10).map((r) => ({ key: r.key, label: r.label, values: [r.count], display: [count(r.count)] }));
  }

  protected summaryOf(rows: CountRow[], what: string): string {
    const total = rows.reduce((s, r) => s + r.count, 0);
    return rows.length ? `${count(total)} ${what} no período; principal causa: ${rows[0].label} (${count(rows[0].count)}).` : `Nenhuma ocorrência de ${what} no período.`;
  }
}
