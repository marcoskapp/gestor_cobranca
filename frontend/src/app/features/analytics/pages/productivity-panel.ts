import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { KpiCard } from '../components/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, decimal, fixed2, num, percent, ratioDetail, seconds } from '../data/analytics.format';
import { urlFilter } from '../data/url-filter';

const GROUPS = ['OPERATOR', 'TEAM'];

/**
 * Produtividade (F18) a partir dos fatos do incentive: casos trabalhados, tentativas, contatos
 * efetivos, tempo médio de atendimento, horas logadas, acordos por hora e recuperado. O operador vê
 * só o próprio desempenho; supervisor, a equipe; gestor, todos.
 */
@Component({
  selector: 'app-productivity-panel',
  imports: [MatButtonModule, Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, KpiCard, PanelFrame],
  template: `
    <app-panel-frame [title]="ownOnly() ? 'Meu desempenho' : 'Produtividade da equipe'"
                     subtitle="Esforço, contato, tempo de atendimento e resultado por pessoa (negociadores humanos)."
                     report="productivity" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()"
                             [options]="{ period: true, creditor: true, portfolio: true, people: !ownOnly(), channel: true, groupBy: ownOnly() ? [] : groups }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="error" title="Não foi possível carregar a produtividade" message="Verifique os filtros e tente novamente.">
          <button mat-flat-button type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.total;
        <section class="pn__kpis" aria-label="Indicadores de produtividade" data-tour="analytics-kpis">
          <app-kpi-card label="Casos trabalhados" [detail]="count(t.attempts) + ' tentativas de contato'">{{ count(t.workedCases) }}</app-kpi-card>
          <app-kpi-card label="Contatos efetivos" [detail]="percent(t.contactRate) + ' das tentativas (' + ratioDetail(t.contactRate) + ')'">{{ count(t.effectiveContacts) }}</app-kpi-card>
          <app-kpi-card label="Tempo médio de atendimento" [detail]="count(t.answeredCalls) + ' chamadas atendidas'">{{ seconds(t.averageHandlingSeconds.value) }}</app-kpi-card>
          <app-kpi-card label="Acordos por hora logada" [detail]="count(t.agreements) + ' acordos em ' + decimal(t.loggedHours) + ' h'">{{ rate(t.agreementsPerLoggedHour.value) }}</app-kpi-card>
          <app-kpi-card label="Recuperado líquido" detail="Pagamentos confirmados dos acordos fechados">
            <app-amount [value]="t.recoveredNet" [compact]="true" />
          </app-kpi-card>
        </section>
        @if (!ownOnly()) {
          <section class="app-card">
            <app-bar-chart title="Recuperado líquido por pessoa" categoryHeader="Pessoa" [summary]="chartSummary()"
                           [series]="[{ name: 'Recuperado líquido', color: '--app-chart-1' }]" [rows]="chartRows()" noteHeader="Acordos por hora" />
          </section>
        }
        <section class="app-card" aria-labelledby="pd-table">
          <h2 class="app-card__title" id="pd-table">{{ state.filter().groupBy === 'TEAM' ? 'Por equipe' : 'Por pessoa' }}</h2>
          <div class="pn__table-wrap">
            <table class="pn__table">
              <thead>
                <tr>
                  <th scope="col">{{ state.filter().groupBy === 'TEAM' ? 'Equipe' : 'Pessoa' }}</th>
                  <th scope="col" class="num">Casos trabalhados</th>
                  <th scope="col" class="num">Tentativas</th>
                  <th scope="col" class="num">Contatos efetivos</th>
                  <th scope="col" class="num">TMA</th>
                  <th scope="col" class="num">Horas logadas</th>
                  <th scope="col" class="num">Acordos</th>
                  <th scope="col" class="num">Acordos/hora</th>
                  <th scope="col" class="num">Recuperado</th>
                </tr>
              </thead>
              <tbody>
                @for (row of r.rows; track row.key) {
                  <tr>
                    <th scope="row">{{ row.label }}</th>
                    <td class="num app-numeric">{{ count(row.workedCases) }}</td>
                    <td class="num app-numeric">{{ count(row.attempts) }}</td>
                    <td class="num">{{ count(row.effectiveContacts) }}<span class="sub">{{ percent(row.contactRate) }}</span></td>
                    <td class="num">{{ seconds(row.averageHandlingSeconds.value) }}<span class="sub">{{ count(row.answeredCalls) }} atendidas</span></td>
                    <td class="num app-numeric">{{ decimal(row.loggedHours) }}</td>
                    <td class="num app-numeric">{{ count(row.agreements) }}</td>
                    <td class="num app-numeric">{{ rate(row.agreementsPerLoggedHour.value) }}</td>
                    <td class="num"><app-amount [value]="row.recoveredNet" /></td>
                  </tr>
                } @empty {
                  <tr><td colspan="9">Nenhuma atividade registrada no período.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <p class="pn__hint">Equipe e total somam as pessoas (um caso trabalhado por duas pessoas conta para as duas). Tempo logado não se divide por credor, carteira ou canal.</p>
        </section>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class ProductivityPanel {
  private readonly api = inject(AnalyticsApi);
  private readonly auth = inject(AuthService);
  protected readonly groups = GROUPS;
  protected readonly state = urlFilter(GROUPS, 'OPERATOR');
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly decimal = decimal;
  protected readonly seconds = seconds;
  protected readonly ratioDetail = ratioDetail;
  protected readonly ownOnly = computed(
    () =>
      !this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL]) &&
      this.auth.hasPermission(Permission.PERFORMANCE_VIEW_OWN),
  );

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.productivity(params) });
  protected readonly report = valueOr(this.data, undefined);
  protected readonly chartRows = computed<ChartRow[]>(() =>
    (this.report()?.rows ?? []).slice(0, 15).map((r) => ({
      key: r.key,
      label: r.label,
      values: [Math.max(0, num(r.recoveredNet))],
      display: [formatAmount(r.recoveredNet)],
      note: `${this.rate(r.agreementsPerLoggedHour.value)} acordos/h`,
    })),
  );
  protected readonly chartSummary = computed(() => {
    const top = this.chartRows()[0];
    return top ? `Recuperado líquido por pessoa no período. Maior resultado: ${top.label}, ${top.display[0]}.` : 'Recuperado líquido por pessoa no período.';
  });

  protected rate(value: number | string | null): string {
    return fixed2(value);
  }
}
