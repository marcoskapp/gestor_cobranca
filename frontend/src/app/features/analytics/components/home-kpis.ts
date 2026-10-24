import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { presetRange } from '../data/analytics.filters';
import { AnalyticsApi } from '../data/analytics.api';
import { count, percent, ratioDetail } from '../data/analytics.format';
import { AnalyticsFilter } from '../data/analytics.models';
import { KpiCard } from './kpi-card';

/**
 * Resumo da operação na página inicial (carregado sob demanda): carteira, saldo vencido, fila
 * pendente e o recuperado dos últimos 30 dias, com atalho para o painel operacional.
 */
@Component({
  selector: 'app-home-kpis',
  imports: [RouterLink, MatButtonModule, MatIconModule, Amount, AppDatePipe, KpiCard],
  template: `
    <section class="hk" aria-labelledby="hk-title">
      <div class="hk__head">
        <h2 class="app-section-title" id="hk-title">Operação</h2>
        <a mat-button routerLink="/paineis">
          Abrir painéis
          <mat-icon iconPositionEnd aria-hidden="true">arrow_forward</mat-icon>
        </a>
      </div>
      @if (portfolio(); as p) {
        <div class="hk__grid">
          <app-kpi-card label="Valor da carteira" [detail]="count(p.totals.openTitles) + ' títulos'"><app-amount [value]="p.totals.portfolioAmount" [compact]="true" /></app-kpi-card>
          <app-kpi-card label="Saldo vencido" [detail]="percent(p.totals.overdueShare) + ' da carteira'"><app-amount [value]="p.totals.overdueAmount" [compact]="true" /></app-kpi-card>
          <app-kpi-card label="Fila pendente" [detail]="ratioDetail(p.totals.pendingQueueShare) + ' elegíveis'">{{ count(p.totals.pendingQueueCases) }}</app-kpi-card>
          @if (recovery(); as r) {
            <app-kpi-card label="Recuperado em 30 dias" [detail]="'Taxa ' + percent(r.totals.recoveryRate)"><app-amount [value]="r.totals.recoveredNet" [compact]="true" /></app-kpi-card>
          }
        </div>
        @if (p.meta.dataUntil) {
          <p class="hk__fresh">Dados até {{ p.meta.dataUntil | appDate: 'datetime' }}</p>
        }
      } @else if (portfolioData.error()) {
        <p class="hk__fresh" role="status">Indicadores indisponíveis no momento.</p>
      } @else {
        <p class="hk__fresh" role="status">Carregando indicadores…</p>
      }
    </section>
  `,
  styles: `
    .hk { margin-bottom: 24px; }
    .hk__head { display: flex; align-items: center; justify-content: space-between; }
    .hk__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: var(--app-gap); }
    .hk__fresh { margin: 8px 0 0; font-size: var(--app-text-xs); color: var(--mat-sys-on-surface-variant); }
  `,
})
export class HomeKpis {
  private readonly api = inject(AnalyticsApi);
  private readonly auth = inject(AuthService);
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly ratioDetail = ratioDetail;
  private readonly filter: AnalyticsFilter = {
    ...presetRange('30d', new Date()),
    creditorIds: [],
    portfolioIds: [],
    operatorIds: [],
    teamIds: [],
    channels: [],
    groupBy: null,
  };
  private readonly canRecovery = computed(() => this.auth.hasPermission(Permission.REPORT_VIEW));
  protected readonly portfolioData = rxResource({ stream: () => this.api.portfolio(this.filter) });
  protected readonly portfolio = valueOr(this.portfolioData, undefined);
  private readonly recoveryData = rxResource({
    params: () => this.canRecovery(),
    stream: ({ params }) => (params ? this.api.recovery(this.filter) : of(undefined)),
  });
  protected readonly recovery = valueOr(this.recoveryData, undefined);
}
