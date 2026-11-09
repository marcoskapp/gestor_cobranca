import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, days, decimal, num, percent, ratioDetail } from '../data/analytics.format';
import { DisputeRow } from '../data/analytics.models';
import { urlFilter } from '../data/url-filter';
import { DrillTarget, disputesTarget, drillAccess, todayIso, withRow } from '../data/drill-down';
import { DrillLink } from '../components/drill-link';

const TYPE_LABELS: Record<string, string> = {
  NOT_RECOGNIZED: 'Não reconhece a dívida',
  ALREADY_PAID: 'Já pagou',
  WRONG_AMOUNT: 'Valor incorreto',
  FRAUD: 'Fraude',
  PRESCRIBED: 'Dívida prescrita',
  PRODUCT_OR_SERVICE: 'Produto ou serviço',
  OTHER: 'Outro motivo',
};

const ORIGIN_LABELS: Record<string, string> = {
  DEBTOR: 'Devedor',
  OPERATOR: 'Operador (pelo devedor)',
  CREDITOR: 'Credor',
  LEGAL: 'Jurídico',
};

/**
 * Painel de contestações (F21): volume, prazo, procedência, retorno do credor e fila por responsável — insumo para a
 * conversa de nível de serviço com o credor e para equilibrar a carga da equipe. Cada taxa mostra a base.
 */
@Component({
  selector: 'app-disputes-panel',
  imports: [RouterLink, EmptyState, Skeleton, AnalyticsFilters, BarChart, DrillLink, InfoHint, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Contestações" subtitle="Volume, prazos, procedência, retorno do credor e fila por responsável."
                     report="disputes" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()" [options]="{ period: true, creditor: true, portfolio: true }" (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar as contestações" message="Tente novamente em instantes.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.totals;
        <section class="app-kpi-grid" aria-label="Indicadores de contestações" data-tour="analytics-kpis">
          <app-kpi-card label="Abertas no período" info="analytics.disputes.opened" [detail]="count(t.resolved) + ' resolvidas · ' + count(t.withdrawn) + ' desistências'">{{ count(t.opened) }}</app-kpi-card>
          <app-kpi-card label="Em aberto agora" info="analytics.disputes.open-now" data-tour="kpi-disputes-open" [detail]="count(t.overdueNow) + ' com prazo de resposta vencido'"><app-drill-link [to]="openNowLink()">{{ count(t.openNow) }}</app-drill-link></app-kpi-card>
          <app-kpi-card label="Resolvidas no prazo" info="analytics.disputes.within-deadline" data-tour="kpi-disputes-deadline" [detail]="ratioDetail(t.withinDeadline) + ' resolvidas'">{{ percent(t.withinDeadline) }}</app-kpi-card>
          <app-kpi-card label="Tempo médio de resolução" info="analytics.disputes.resolution-time" [detail]="'sobre ' + count(t.resolved) + ' resolvidas'">{{ days(t.averageResolutionDays) }}</app-kpi-card>
          <app-kpi-card label="Procedência" info="analytics.disputes.upheld-rate" data-tour="kpi-disputes-upheld" [detail]="ratioDetail(t.upheldRate) + ' resolvidas'">{{ percent(t.upheldRate) }}</app-kpi-card>
          <app-kpi-card label="Retorno do credor" info="analytics.disputes.creditor-response" data-tour="kpi-disputes-creditor" [detail]="count(t.creditorRequestsAnswered) + ' de ' + count(t.creditorRequestsSent) + ' pedidos respondidos'">{{ hours(t.averageCreditorResponseHours) }}</app-kpi-card>
          <app-kpi-card label="Aguardando o credor" info="analytics.disputes.creditor-awaiting" [detail]="count(t.creditorOverdueNow) + ' com prazo do credor vencido'">{{ count(t.creditorAwaitingNow) }}</app-kpi-card>
        </section>
        <div class="pn__grid app-columns">
          <section class="app-card">
            <app-bar-chart title="Abertas por motivo" info="analytics.disputes.opened" categoryHeader="Motivo" [summary]="summaryOf(r.byType, 'motivo', typeLabels)"
                           [series]="[{ name: 'Abertas', color: '--app-chart-1' }]" [rows]="openedRows(r.byType, typeLabels)" />
          </section>
          <section class="app-card">
            <app-bar-chart title="Abertas por origem" info="analytics.disputes.opened" categoryHeader="Origem" [summary]="summaryOf(r.byOrigin, 'origem', originLabels)"
                           [series]="[{ name: 'Abertas', color: '--app-chart-1' }]" [rows]="openedRows(r.byOrigin, originLabels)" />
          </section>
        </div>
        <section class="app-card pn__table-wrap" aria-labelledby="dp-creditors" data-tour="analytics-disputes-creditors">
          <h2 class="app-card__title" id="dp-creditors">Por credor <app-info-hint indicator="analytics.disputes.upheld-rate" label="Procedência por credor" /></h2>
          <table class="pn__table">
            <thead>
              <tr>
                <th scope="col">Credor</th>
                <th scope="col" class="num">Abertas</th>
                <th scope="col" class="num">Resolvidas</th>
                <th scope="col" class="num">No prazo</th>
                <th scope="col" class="num">Procedência</th>
                <th scope="col" class="num">Tempo médio</th>
                <th scope="col" class="num">Em aberto (vencidas)</th>
                <th scope="col" class="num">Retorno do credor</th>
              </tr>
            </thead>
            <tbody>
              @for (row of r.byCreditor; track row.key) {
                <tr>
                  <th scope="row">{{ row.label }}</th>
                  <td class="num app-numeric">{{ count(row.opened) }}</td>
                  <td class="num app-numeric">{{ count(row.resolved) }}</td>
                  <td class="num">{{ percent(row.withinDeadline) }}<span class="sub">{{ ratioDetail(row.withinDeadline) }}</span></td>
                  <td class="num">{{ percent(row.upheldRate) }}<span class="sub">{{ ratioDetail(row.upheldRate) }}</span></td>
                  <td class="num">{{ days(row.averageResolutionDays) }}</td>
                  <td class="num app-numeric"><app-drill-link [to]="creditorLinks().get(row.key) ?? null">{{ count(row.openNow) }}</app-drill-link> ({{ count(row.overdueNow) }})</td>
                  <td class="num">{{ hours(row.averageCreditorResponseHours) }}<span class="sub">{{ count(row.creditorRequestsAnswered) }} respondidos</span></td>
                </tr>
              } @empty {
                <tr><td colspan="8">Nenhuma contestação no período.</td></tr>
              }
            </tbody>
          </table>
        </section>
        <section class="app-card pn__table-wrap" aria-labelledby="dp-backlog" data-tour="analytics-disputes-backlog">
          <h2 class="app-card__title" id="dp-backlog">Fila por responsável <app-info-hint indicator="analytics.disputes.backlog" label="Fila por responsável" /></h2>
          @if (r.meta.peopleRestricted) {
            <p class="pn__hint">Mostrando só as pessoas do seu escopo.</p>
          }
          <table class="pn__table">
            <thead>
              <tr>
                <th scope="col">Responsável</th>
                <th scope="col" class="num">Em aberto</th>
                <th scope="col" class="num">Prazo interno vencido</th>
                <th scope="col" class="num">Resposta vencida</th>
                <th scope="col" class="num">Aguardando o credor</th>
              </tr>
            </thead>
            <tbody>
              @for (b of r.backlog; track b.key) {
                <tr>
                  <th scope="row">
                    @if (canOpenQueue && b.key === '-') {
                      <a routerLink="/cobranca/contestacoes" [queryParams]="{ responsavel: 'supervisao' }">{{ b.label }}</a>
                    } @else {
                      {{ b.label }}
                    }
                  </th>
                  <td class="num app-numeric">{{ count(b.open) }}</td>
                  <td class="num app-numeric">{{ count(b.internalOverdue) }}</td>
                  <td class="num app-numeric">{{ count(b.overdue) }}</td>
                  <td class="num app-numeric">{{ count(b.awaitingCreditor) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5">Nenhuma contestação em aberto.</td></tr>
              }
            </tbody>
          </table>
        </section>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class DisputesPanel {
  private readonly api = inject(AnalyticsApi);
  protected readonly state = urlFilter();
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly days = days;
  protected readonly ratioDetail = ratioDetail;
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly originLabels = ORIGIN_LABELS;
  protected readonly canOpenQueue = inject(AuthService).hasPermission(Permission.DISPUTE_MANAGE);

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.disputes(params) });
  protected readonly report = valueOr(this.data, undefined);

  /**
   * Drill-down à fila de contestações (situação atual: abertas ou em análise), só quando o "agora" do painel é hoje e
   * sem carteira (a fila não filtra carteira). Abertas, resolvidas e procedência são do período, que a fila não filtra:
   * sem link.
   */
  private readonly access = drillAccess();
  protected readonly openNowLink = computed(() => this.access(disputesTarget(this.state.filter(), todayIso(), {}, 'ver as contestações em aberto na fila')));
  protected readonly creditorLinks = computed(() => {
    const f = this.state.filter();
    const out = new Map<string, DrillTarget | null>();
    for (const row of this.report()?.byCreditor ?? []) {
      const scoped = withRow(f, 'CREDITOR', row.key);
      out.set(row.key, scoped ? this.access(disputesTarget(scoped, todayIso(), {}, 'ver as contestações em aberto deste credor na fila')) : null);
    }
    return out;
  });

  protected hours(value: string | number | null): string {
    return value === null ? '—' : `${decimal(value)} h`;
  }

  protected openedRows(rows: DisputeRow[], labels: Record<string, string>): ChartRow[] {
    return rows.map((r) => ({ key: r.key, label: labels[r.key] ?? r.label, values: [num(r.opened)], display: [count(r.opened)] }));
  }

  protected summaryOf(rows: DisputeRow[], what: string, labels: Record<string, string>): string {
    const top = [...rows].sort((a, b) => b.opened - a.opened)[0];
    const total = rows.reduce((s, r) => s + r.opened, 0);
    return top ? `${count(total)} contestações abertas no período; ${what} mais frequente: ${labels[top.key] ?? top.label} (${count(top.opened)}).` : 'Nenhuma contestação aberta no período.';
  }
}
