import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { ShowMoreButton } from '../../../shared/components/show-more/show-more';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { showMore } from '../../../shared/util/show-more';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow, ChartSeries } from '../components/chart.models';
import { FocusChip } from '../components/focus-chip';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, decimal, fixed2, num, percent, ratioDetail, seconds } from '../data/analytics.format';
import { focusFilter, focusableKey, unfocusFilter, urlFilter } from '../data/url-filter';

const GROUPS = ['OPERATOR', 'TEAM'];

/**
 * Produtividade (F18) a partir dos fatos do incentive: casos trabalhados, tentativas, contatos
 * efetivos, tempo médio de atendimento, horas logadas, acordos por hora e recuperado. O operador vê
 * só o próprio desempenho; supervisor, a equipe; gestor, todos.
 */
@Component({
  selector: 'app-productivity-panel',
  imports: [Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, FocusChip, InfoHint, KpiCard, PanelFrame, ShowMoreButton],
  template: `
    <app-panel-frame [title]="ownOnly() ? 'Minha produtividade' : 'Produtividade da equipe'"
                     subtitle="Esforço, contato, tempo de atendimento e resultado por pessoa (negociadores humanos)."
                     report="productivity" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()"
                             [options]="{ period: true, creditor: true, portfolio: true, people: !ownOnly(), channel: true, groupBy: ownOnly() ? [] : groups }"
                             (changed)="state.update($event)" />
      <app-focus-chip [label]="state.focused()" (removed)="state.update(unfocus())" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a produtividade" message="Verifique os filtros e tente novamente.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let t = r.total;
        <section class="app-kpi-grid" aria-label="Indicadores de produtividade" data-tour="analytics-kpis">
          <app-kpi-card label="Casos trabalhados" info="analytics.productivity.worked-cases" data-tour="kpi-worked" [detail]="count(t.attempts) + ' tentativas de contato'">{{ count(t.workedCases) }}</app-kpi-card>
          <app-kpi-card label="Contatos efetivos" info="analytics.productivity.effective-contacts" [detail]="percent(t.contactRate) + ' das tentativas (' + ratioDetail(t.contactRate) + ')'">{{ count(t.effectiveContacts) }}</app-kpi-card>
          <app-kpi-card label="Tempo médio de atendimento" info="analytics.productivity.handling-time" data-tour="kpi-aht" [detail]="count(t.answeredCalls) + ' chamadas atendidas'">{{ seconds(t.averageHandlingSeconds.value) }}</app-kpi-card>
          <app-kpi-card label="Acordos por hora logada" info="analytics.productivity.agreements-per-hour" data-tour="kpi-per-hour" [detail]="count(t.agreements) + ' acordos em ' + decimal(t.loggedHours) + ' h'">{{ rate(t.agreementsPerLoggedHour.value) }}</app-kpi-card>
          <app-kpi-card label="Recuperado líquido" info="analytics.recovery.net" detail="Pagamentos confirmados dos acordos fechados">
            <app-amount [value]="t.recoveredNet" [compact]="true" />
          </app-kpi-card>
        </section>
        @if (!ownOnly()) {
          <section class="app-card">
            <app-bar-chart title="Recuperado líquido por pessoa" info="analytics.recovery.net" categoryHeader="Pessoa" [summary]="chartSummary()"
                           [series]="series" [rows]="chartRows()" noteHeader="Acordos por hora"
                           [limit]="15" [noun]="personNoun()" shareOf="do recuperado líquido"
                           tableKey="pessoas" [searchInUrl]="false" [selectable]="!ownOnly()" [selectableRow]="focusableRow" [selectHint]="'Filtrar o painel por ' + (team() ? 'esta equipe.' : 'esta pessoa.')"
                           (selected)="focusOn($event)" />
          </section>
        }
        <section class="app-card" aria-labelledby="pd-table" data-tour="analytics-table">
          <h2 class="app-card__title" id="pd-table">{{ state.filter().groupBy === 'TEAM' ? 'Por equipe' : 'Por pessoa' }}</h2>
          <div class="pn__table-wrap">
            <table class="pn__table" #tableEl>
              <thead>
                <tr>
                  <th scope="col">{{ state.filter().groupBy === 'TEAM' ? 'Equipe' : 'Pessoa' }}</th>
                  <th scope="col" class="num">Casos trabalhados <app-info-hint indicator="analytics.productivity.worked-cases" label="Casos trabalhados" /></th>
                  <th scope="col" class="num">Tentativas <app-info-hint indicator="analytics.productivity.attempts" label="Tentativas" /></th>
                  <th scope="col" class="num">Contatos efetivos <app-info-hint indicator="analytics.productivity.effective-contacts" label="Contatos efetivos" /></th>
                  <th scope="col" class="num"><abbr title="Tempo médio de atendimento">TMA</abbr> <app-info-hint indicator="analytics.productivity.handling-time" label="TMA (tempo médio de atendimento)" /></th>
                  <th scope="col" class="num">Horas logadas <app-info-hint indicator="analytics.productivity.logged-hours" label="Horas logadas" /></th>
                  <th scope="col" class="num">Acordos <app-info-hint indicator="analytics.productivity.agreements" label="Acordos" /></th>
                  <th scope="col" class="num">Acordos/hora <app-info-hint indicator="analytics.productivity.agreements-per-hour" label="Acordos por hora logada" /></th>
                  <th scope="col" class="num">Recuperado <app-info-hint indicator="analytics.recovery.net" label="Recuperado líquido" /></th>
                </tr>
              </thead>
              <tbody>
                @for (row of table.visible(); track row.key) {
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
          <app-show-more [list]="table" [table]="tableEl" [singular]="state.filter().groupBy === 'TEAM' ? 'equipe' : 'pessoa'"
                         [plural]="state.filter().groupBy === 'TEAM' ? 'equipes' : 'pessoas'" />
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
  protected readonly series: ChartSeries[] = [{ name: 'Recuperado líquido', color: '--app-chart-1', format: (v) => formatAmount(v) }];
  protected readonly team = computed(() => this.state.filter().groupBy === 'TEAM');
  protected readonly personNoun = computed(() => (this.team() ? { one: 'equipe', many: 'equipes' } : { one: 'pessoa', many: 'pessoas' }));

  /** Clique numa barra: filtra o painel pela pessoa ou equipe (entra no histórico; o chip e o "voltar" desfazem). */
  protected focusOn(row: ChartRow): void {
    const dimension = this.team() ? 'TEAM' : 'OPERATOR';
    const next = focusFilter(this.state.filter(), dimension, row.key);
    if (next) {
      this.state.focus(next, `${this.team() ? 'Equipe' : 'Pessoa'}: ${row.label}`, dimension);
    }
  }

  protected readonly focusableRow = (r: ChartRow) => focusableKey(r.key);

  protected unfocus() {
    return unfocusFilter(this.state.filter(), this.team() ? 'TEAM' : 'OPERATOR');
  }

  /** Tabela "Por pessoa/Por equipe" em passos de 25 (na ordem do servidor); filtro ou agrupamento novos voltam a 25. */
  protected readonly table = showMore(() => this.report()?.rows ?? [], { resetOn: () => this.state.filter() });
  protected readonly chartRows = computed<ChartRow[]>(() =>
    [...(this.report()?.rows ?? [])]
      .sort((a, b) => num(b.recoveredNet) - num(a.recoveredNet))
      .map((r) => ({
      key: r.key,
      label: r.label,
      values: [num(r.recoveredNet)],
      display: [formatAmount(r.recoveredNet)],
      note: this.rate(r.agreementsPerLoggedHour.value),
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
