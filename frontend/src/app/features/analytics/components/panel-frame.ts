import { Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ANALYTICS_PANELS } from '../analytics.panels';
import { filterParams } from '../data/analytics.filters';
import { AnalyticsApi } from '../data/analytics.api';
import { AnalyticsFilter, ReportMeta, ReportName } from '../data/analytics.models';

const FILTER_LABELS: Record<string, string> = {
  from: 'período',
  to: 'período',
  operatorIds: 'operador',
  teamIds: 'equipe',
  channels: 'canal',
  'portfolioIds:exceto importações': 'carteira (só importações)',
  'loggedTime:creditorIds,portfolioIds,channels': 'credor, carteira e canal no tempo logado',
};

const UNAVAILABLE_METRICS: Record<string, string> = {
  'cost.channelCosts': 'custos de canal (sem tarifas cadastradas)',
  'messages.duplicates': 'mensagens duplicadas do provedor (descartadas na entrada)',
  'jobs:requer acesso a todos os credores': 'processamentos em falha (exige acesso a todos os credores)',
};

/**
 * Moldura comum dos painéis: título, navegação entre painéis (preservando os filtros), carimbo
 * "dados até" com atualização manual, exportação (CSV/XLSX) e avisos de escopo e de filtros que não
 * se aplicam. O conteúdo (filtros e gráficos) é projetado.
 */
@Component({
  selector: 'app-panel-frame',
  imports: [RouterLink, RouterLinkActive, MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTooltipModule, PageHeader, AppDatePipe],
  template: `
    <app-page-header [title]="title()" [subtitle]="subtitle()">
      @if (report() && canExport()) {
        <button mat-stroked-button type="button" [matMenuTriggerFor]="exportMenu" [disabled]="exporting()" data-tour="analytics-export">
          <mat-icon aria-hidden="true">download</mat-icon>
          Exportar
        </button>
        <mat-menu #exportMenu="matMenu">
          <button mat-menu-item type="button" (click)="export('XLSX')">Planilha (XLSX)</button>
          <button mat-menu-item type="button" (click)="export('CSV')">Texto separado por ponto e vírgula (CSV)</button>
        </mat-menu>
      }
    </app-page-header>

    <nav class="pf__tabs" aria-label="Painéis" data-tour="analytics-tabs">
      @for (p of panels(); track p.route) {
        <a class="pf__tab" [routerLink]="p.route" [queryParams]="queryParams()" routerLinkActive="pf__tab--active"
           [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">
          <mat-icon aria-hidden="true">{{ p.icon }}</mat-icon>{{ p.label }}
        </a>
      }
    </nav>

    <ng-content select="[panel-filters]" />

    <p class="pf__fresh" data-tour="analytics-freshness">
      <mat-icon aria-hidden="true">schedule</mat-icon>
      @if (meta(); as m) {
        @if (m.dataUntil) {
          <span>Dados até <strong>{{ m.dataUntil | appDate: 'datetime' }}</strong></span>
        } @else {
          <span>Aguardando a primeira atualização dos dados.</span>
        }
      } @else {
        <span>Carregando…</span>
      }
      @if (canRefresh()) {
        <button mat-button type="button" (click)="refresh()" [disabled]="refreshing()">
          <mat-icon aria-hidden="true">refresh</mat-icon>
          Atualizar agora
        </button>
      }
    </p>

    @if (meta(); as m) {
      @if (m.peopleRestricted) {
        <p class="app-alert app-alert--info" role="note">
          <mat-icon aria-hidden="true">person</mat-icon>
          <span>Os dados por pessoa mostram somente quem está no seu escopo ({{ scopeText() }}).</span>
        </p>
      }
      @if (notApplicable().length) {
        <p class="app-alert app-alert--warning" role="note">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span>Filtro sem efeito neste painel: {{ notApplicable().join(', ') }}.</span>
        </p>
      }
    }

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" aria-label="Atualizando o painel" />
    }
    <div class="pf__body" [class.pf__body--loading]="loading()" [attr.aria-busy]="loading()">
      <ng-content />
    </div>

    @if (unavailable().length) {
      <p class="pf__note">Sem fonte de dados no produto: {{ unavailable().join('; ') }}.</p>
    }
  `,
  styleUrl: './panel-frame.scss',
})
export class PanelFrame {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly report = input<ReportName | null>(null);
  readonly filter = input.required<AnalyticsFilter>();
  readonly meta = input<ReportMeta | undefined>();
  readonly loading = input(false);
  readonly refreshed = output<void>();

  private readonly auth = inject(AuthService);
  private readonly api = inject(AnalyticsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly exporting = signal(false);
  protected readonly refreshing = signal(false);

  protected readonly panels = computed(() => {
    this.auth.user();
    return ANALYTICS_PANELS.filter((p) => this.auth.hasAnyPermission(p.permissions));
  });
  protected readonly queryParams = computed(() => filterParams({ ...this.filter(), groupBy: null }));
  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly canRefresh = computed(() => this.auth.hasAnyPermission([Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW]));
  protected readonly scopeText = computed(() =>
    this.auth.hasPermission(Permission.PERFORMANCE_VIEW_TEAM) ? 'você e as equipes que supervisiona' : 'somente você',
  );
  protected readonly notApplicable = computed(() => {
    const m = this.meta();
    if (!m) {
      return [];
    }
    const f = this.filter();
    const informed = (key: string) =>
      key === 'from' || key === 'to'
        ? false
        : key.startsWith('loggedTime') || key.startsWith('portfolioIds')
          ? true
          : ((f as unknown as Record<string, unknown[]>)[key]?.length ?? 0) > 0;
    return [...new Set(m.notApplicableFilters.filter(informed).map((k) => FILTER_LABELS[k] ?? k))];
  });
  protected readonly unavailable = computed(() => (this.meta()?.unavailableMetrics ?? []).map((k) => UNAVAILABLE_METRICS[k] ?? k));

  protected export(format: 'CSV' | 'XLSX'): void {
    const report = this.report();
    if (!report) {
      return;
    }
    this.exporting.set(true);
    this.api.export(report, this.filter(), format).subscribe({
      next: (name) => {
        this.exporting.set(false);
        this.notifications.success(`Relatório exportado: ${name}. A exportação fica registrada no log de acesso.`);
      },
      error: () => this.exporting.set(false),
    });
  }

  protected refresh(): void {
    this.refreshing.set(true);
    this.api.refresh().subscribe({
      next: () => {
        this.refreshing.set(false);
        this.notifications.success('Dados dos painéis atualizados.');
        this.refreshed.emit();
      },
      error: () => this.refreshing.set(false),
    });
  }
}
