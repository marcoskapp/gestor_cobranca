import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../../analytics/components/analytics-filters';
import { AnalyticsFilter } from '../../analytics/data/analytics.models';
import { MarginBasisBar } from '../components/margin-basis-bar';
import { MarginExport } from '../components/margin-export';
import { MarginTable } from '../components/margin-table';
import { MarginWarnings } from '../components/margin-warnings';
import { MarginApi } from '../data/costing.api';
import { MARGIN_ROUTE } from '../data/costing.labels';
import { marginNames } from '../data/margin-names';
import { marginUrlState } from '../data/margin-url-state';
import { asAnalyticsFilter, fromAnalyticsFilter, marginReportParams, num } from '../data/margin.rules';

/**
 * Relatório completo "Margem por credor e carteira" (`/paineis/margem/relatorio`, margem-por-carteira.md §15): a tabela
 * com todas as colunas, o custo por categoria, a busca, a ordenação por coluna, as carteiras, "Mostrar mais" e os
 * totais, com os filtros, a base dos custos e a exportação. Aberto pelo painel em nova aba com o mesmo recorte na URL
 * (`de`, `ate`, `credor`, `carteira`, `base`, `ordem`); mesma permissão do painel (`COST_VIEW`).
 */
@Component({
  selector: 'app-margin-report',
  imports: [AnalyticsFilters, EmptyState, Icon, MarginBasisBar, MarginExport, MarginTable, MarginWarnings, PageHeader, RouterLink, Skeleton],
  template: `
    <app-page-header title="Relatório de margem por carteira" subtitle="Todos os credores e carteiras do período, com todas as colunas e o custo por categoria.">
      <a class="btn btn-outline-secondary" [routerLink]="panelRoute" [queryParams]="sameCut()" data-tour="margin-report-panel">
        <app-icon name="pie-chart" />
        Ver o painel
      </a>
      @if (report()) {
        <app-margin-export [filter]="state.filter()" [basis]="state.basis()" />
      }
    </app-page-header>

    <app-analytics-filters [filter]="analyticsFilter()" [options]="{ period: true, creditor: true, portfolio: true }" (changed)="changeFilter($event)" />

    <app-margin-basis-bar [basis]="state.basis()" [report]="report()" (changed)="state.setBasis($event)" />

    @if (partialScope()) {
      <div class="alert alert-info d-flex align-items-start gap-2" role="note">
        <app-icon name="info-circle" />
        <span>Você vê só os credores do seu escopo: ociosidade e custos fixos aparecem só na parte rateada a eles, e os totais são a soma desses credores.</span>
      </div>
    }

    @if (data.isLoading() && report()) {
      <div class="progress mb-2" role="progressbar" aria-label="Atualizando o relatório">
        <div class="progress-bar progress-bar-striped progress-bar-animated w-100"></div>
      </div>
    }

    @if (data.error()) {
      <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a margem" message="Verifique os filtros e tente novamente.">
        <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!report()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else {
      @let r = report()!;
      <app-margin-warnings [warnings]="r.warnings" />
      @if (empty()) {
        <app-empty-state icon="pie-chart" title="Sem receita nem custo no período"
                         message="A apuração dos custos roda todo dia. Escolha outro período ou outro credor; para o mês corrente, os números mudam até o fechamento." />
      } @else {
        <app-margin-table mode="full" [report]="r" [trend]="trend()" [trendError]="!!trendData.error()" [filter]="state.filter()" [basis]="state.basis()"
                          [sort]="state.sort()" [names]="names" (sortChange)="state.setSort($event)" />
      }
    }
  `,
})
export class MarginReportPage {
  private readonly api = inject(MarginApi);
  private readonly auth = inject(AuthService);

  protected readonly state = marginUrlState();
  protected readonly analyticsFilter = computed(() => asAnalyticsFilter(this.state.filter()));
  protected readonly data = rxResource({
    params: () => ({ filter: this.state.filter(), basis: this.state.basis() }),
    stream: ({ params }) => this.api.margin(params.filter, params.basis),
  });
  protected readonly report = valueOr(this.data, undefined);
  /** Tendência de 6 meses (pedido à parte, como no painel): falha ou demora só afetam a coluna "Tendência". */
  protected readonly trendData = rxResource({
    params: () => ({ filter: this.state.filter(), basis: this.state.basis() }),
    stream: ({ params }) => this.api.trend(params.filter, params.basis),
  });
  protected readonly trend = valueOr(this.trendData, undefined);
  protected readonly names = marginNames(this.report);

  /** Quem não vê todos os credores recebe ociosidade e fixos só dos próprios credores, sem total da assessoria (§7). */
  protected readonly partialScope = computed(() => this.auth.user()?.allCreditors === false);
  protected readonly empty = computed(() => {
    const r = this.report();
    return !!r && r.creditors.length === 0 && num(r.totals.revenue) === 0 && num(r.totals.totalCost) === 0;
  });

  /** Volta ao painel com o mesmo recorte (a aba do relatório costuma ter sido aberta sem histórico do painel). */
  protected readonly panelRoute = MARGIN_ROUTE;
  protected readonly sameCut = computed(() => marginReportParams(this.state.filter(), this.state.basis(), this.state.sort()));

  protected changeFilter(filter: AnalyticsFilter): void {
    this.state.update(fromAnalyticsFilter(filter));
  }
}
