import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { formatCycle } from '../../public-api/data/public-api.labels';
import { PlatformBillingApi } from '../data/platform-billing.api';
import { TenantSummary } from '../data/platform-billing.models';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Faturamento da plataforma, visão consolidada: o ciclo em andamento de cada assessoria (ativas e suspensas, estas com
 * selo e valores zerados), com consumo somado de todos os tokens, tráfego não cobrável e recusado, receita até agora e
 * projetada, o último extrato fechado, o ranking por consumo, as assessorias ainda sem política e o acesso direto aos
 * tokens de cada uma. Independente da cobrança de cada assessoria aos seus credores.
 */
@Component({
  selector: 'app-platform-billing-overview',
  imports: [Amount, CellDef, DataTable, Icon, KpiCard, PageHeader, RouterLink],
  template: `
    <app-page-header title="Faturamento da plataforma" subtitle="Cobrança das assessorias pelo consumo de todos os seus tokens de integração — independente da cobrança delas aos credores.">
      <button class="btn btn-outline-secondary d-inline-flex align-items-center gap-2" type="button" (click)="overview.reload()" [disabled]="overview.isLoading()">
        <app-icon name="arrow-clockwise" />
        Atualizar
      </button>
    </app-page-header>

    @if (overview.status() === 'error') {
      <div class="alert alert-danger" role="alert">Não foi possível carregar o faturamento da plataforma.</div>
    }
    @if (overview.value(); as o) {
      <div class="app-kpi-grid" data-tour="overview-kpis">
        <app-kpi-card label="Receita até agora" info="platform.overview.revenue" data-tour="kpi-revenue" [detail]="'Ciclos em andamento de ' + o.totals.withPolicy + ' assessorias com política'"><app-amount [value]="o.totals.amountSoFar" /></app-kpi-card>
        <app-kpi-card label="Receita projetada" info="platform.overview.projection" detail="Pela média diária de cada assessoria"><app-amount [value]="o.totals.projectedAmount" /></app-kpi-card>
        <app-kpi-card label="Últimos extratos fechados" info="platform.overview.last-closed" detail="Soma do último extrato de cada assessoria"><app-amount [value]="o.totals.lastClosedAmount" /></app-kpi-card>
        <app-kpi-card label="Assessorias sem política" info="platform.overview.without-policy" data-tour="kpi-without-policy" [detail]="'de ' + o.totals.tenants + ' assessorias'">{{ o.totals.withoutPolicy }}</app-kpi-card>
        <app-kpi-card label="Chamadas cobráveis" info="platform.billing.billable-requests" [detail]="n(o.totals.billableMbIn) + ' MB recebidos · ' + n(o.totals.billableMbOut) + ' MB enviados'">{{ n(o.totals.billableRequests) }}</app-kpi-card>
        <app-kpi-card label="Chamadas recusadas" info="platform.traffic.rejected" data-tour="kpi-rejected" [detail]="n(o.totals.nonBillableRequests) + ' autenticadas não cobráveis'">{{ n(o.totals.rejectedRequests) }}</app-kpi-card>
        <app-kpi-card label="Assessorias suspensas" info="platform.overview.suspended" [detail]="n(o.totals.pausedTokens) + ' tokens pausados pela plataforma'">{{ o.totals.suspended }}</app-kpi-card>
      </div>
    }

    <section class="app-card" aria-labelledby="pbo-ranking">
      <h2 class="app-card__title" id="pbo-ranking">Assessorias por consumo no ciclo em andamento</h2>
      <app-data-table
        caption="Assessorias por consumo"
        [rows]="rows()"
        [columns]="columns"
        [loading]="overview.isLoading()"
        [failed]="overview.status() === 'error'"
        (retry)="overview.reload()"
        emptyIcon="building"
        emptyMessage="Nenhuma assessoria."
      >
        <ng-template appCell="rank" [appCellOf]="rows()" let-t>{{ t.rank }}º</ng-template>
        <ng-template appCell="tenant" [appCellOf]="rows()" let-t>
          <strong>{{ t.tradeName }}</strong>
          @if (t.suspended) { <span class="badge rounded-pill border fw-normal bg-danger-subtle text-danger-emphasis border-danger-subtle"><app-icon name="exclamation-octagon" /> suspensa</span> }
          @if (!t.hasPolicy) { <span class="app-status app-status--warning">sem política</span> }
          <div class="app-muted pbo-small app-mono">{{ t.slug }}</div>
        </ng-template>
        <ng-template appCell="cycle" [appCellOf]="rows()" let-t>{{ cycle(t) }}<div class="app-muted pbo-small">dia {{ t.daysElapsed }} de {{ t.cycleDays }}{{ t.policyVersion ? ' · política v' + t.policyVersion : '' }}</div></ng-template>
        <ng-template appCell="usage" [appCellOf]="rows()" let-t><span class="pbo-small">{{ n(t.billableRequests) }} cham. · {{ n(t.billableMbIn) }}/{{ n(t.billableMbOut) }} MB · {{ n(t.records) }} reg. · {{ n(t.tokens) }} tokens</span></ng-template>
        <ng-template appCell="traffic" [appCellOf]="rows()" let-t>
          <span class="pbo-small">{{ n(t.nonBillableRequests) }} não cobráveis</span>
          <div class="pbo-small" [class.text-warning-emphasis]="t.rejectedRequests > 0">{{ n(t.rejectedRequests) }} recusadas{{ t.pausedTokens ? ' · ' + t.pausedTokens + ' pausado(s)' : '' }}</div>
        </ng-template>
        <ng-template appCell="amount" [appCellOf]="rows()" let-t><app-amount [value]="t.amountSoFar" /></ng-template>
        <ng-template appCell="projection" [appCellOf]="rows()" let-t><app-amount [value]="t.projectedAmount" /></ng-template>
        <ng-template appCell="last" [appCellOf]="rows()" let-t>
          @if (t.lastStatement; as s) { <app-amount [value]="s.totalAmount" /><div class="app-muted pbo-small">{{ range(s.periodStart, s.periodEnd) }}</div> } @else { — }
        </ng-template>
        <ng-template appCell="actions" [appCellOf]="rows()" let-t>
          <a class="btn btn-sm btn-link" [routerLink]="['/plataforma/faturamento', t.tenantId]" [queryParams]="{ nome: t.tradeName }">{{ t.hasPolicy ? 'Detalhar' : 'Cadastrar política' }}</a>
          <a class="btn btn-sm btn-link" [routerLink]="['/plataforma/faturamento', t.tenantId]" [queryParams]="{ nome: t.tradeName }" fragment="tokens" data-tour="ranking-tokens">Tokens</a>
        </ng-template>
      </app-data-table>
    </section>
  `,
  styles: `
    .pbo-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class PlatformBillingOverview {
  private readonly api = inject(PlatformBillingApi);
  protected readonly overview = rxResource({ stream: () => this.api.overview() });
  protected readonly rows = computed<TenantSummary[]>(() => this.overview.value()?.tenants ?? []);

  protected readonly columns: TableColumn[] = [
    { key: 'rank', header: 'Posição', info: 'platform.overview.rank' },
    { key: 'tenant', header: 'Assessoria' },
    { key: 'cycle', header: 'Ciclo em andamento' },
    { key: 'usage', header: 'Consumo cobrável', info: 'platform.billing.billable-requests' },
    { key: 'traffic', header: 'Não cobrável e recusado', info: 'platform.traffic.rejected' },
    { key: 'amount', header: 'Valor até agora', align: 'end', info: 'platform.billing.amount' },
    { key: 'projection', header: 'Projeção', align: 'end', info: 'platform.billing.projection' },
    { key: 'last', header: 'Último extrato', align: 'end', info: 'platform.statement.total' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected cycle(t: TenantSummary): string {
    return formatCycle(t.periodStart, t.periodEnd);
  }

  protected range(start: string, end: string): string {
    return formatCycle(start, end);
  }
}
