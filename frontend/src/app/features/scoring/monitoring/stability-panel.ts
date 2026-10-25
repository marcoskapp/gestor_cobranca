import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { BarChart } from '../../analytics/components/bar-chart';
import { ColumnChart } from '../../analytics/components/column-chart';
import { KpiCard } from '../../analytics/components/kpi-card';
import { ScoringModelsApi, ScoringMonitoringApi, ScoringSettingsApi } from '../data/scoring.api';
import { FeatureCatalog, MonitoringView, ScoringSettingsView } from '../data/scoring.models';
import {
  PSI_LEVEL_TEXT,
  alertText,
  calibrationRows,
  calibrationSummary,
  dec,
  featurePsiRows,
  integer,
  latestCohort,
  metric,
  percent,
  psiLevel,
  realizedAucRows,
  scorePsiRows,
} from '../data/scoring.rules';

/** Retratos considerados nos gráficos (≈ 6 meses de retratos semanais). */
export const MONITORING_REPORTS = 26;

/**
 * Estabilidade e desempenho do campeão: PSI do score e de cada variável contra a referência do
 * treino, alertas de deriva, calibração (prevista × observada por faixa) e AUC realizada das
 * safras já apuradas, com o desafiante em sombra.
 */
@Component({
  selector: 'app-stability-panel',
  imports: [Icon, RouterLink, AppDatePipe, CellDef, DataTable, EmptyState, Skeleton, StatusChip, BarChart, ColumnChart, KpiCard],
  template: `
    @if (reports.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o monitoramento." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="reports.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (reports.isLoading() && !rows().length) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (!latest()) {
      <app-empty-state
        icon="activity"
        title="Nenhum relatório de monitoramento ainda."
        message="O primeiro relatório sai depois do primeiro retrato semanal da escoragem; o desempenho real aparece quando as safras completam a janela de desfecho."
      >
        <a class="btn btn-outline-primary" routerLink="/score/execucoes">Ver execuções</a>
      </app-empty-state>
    } @else {
      @let l = latest()!;
      <section class="app-card" aria-labelledby="st-latest" data-tour="scoring-drift">
        <div class="st-head">
          <h2 class="app-card__title mb-0" id="st-latest">Último retrato · {{ l.referenceDate | appDate: 'date' }}</h2>
          <app-status-chip domain="scoringMonitoring" [status]="l.status" />
        </div>
        <div class="st-kpis">
          <app-kpi-card label="População" detail="Casos escorados no retrato">{{ int(l.population) }}</app-kpi-card>
          <app-kpi-card label="PSI do score" [detail]="scorePsiDetail()">{{ metric(l.scorePsi) }}</app-kpi-card>
          <app-kpi-card label="Alertas" [detail]="'Atenção ≥ ' + metric(warning()) + ' · alerta ≥ ' + metric(alert())">{{ int(l.alerts.length) }}</app-kpi-card>
          @if (cohort(); as c) {
            <app-kpi-card label="AUC realizada" [detail]="'Safra de ' + (c.referenceDate | appDate: 'date') + ' · ' + int(c.cases) + ' casos'">{{ metric(c.auc) }}</app-kpi-card>
            <app-kpi-card label="Prevista × observada" [detail]="'Taxa observada ' + percent(c.observedRate)">{{ percent(c.meanPredicted) }}</app-kpi-card>
          }
        </div>
        @if (l.alerts.length) {
          <ul class="st-alerts" aria-label="Alertas do último retrato">
            @for (a of l.alerts; track $index) {
              <li [class.st-alerts__alert]="a.level === 'ALERT'">
                <app-icon [name]="a.level === 'ALERT' ? 'exclamation-circle' : 'exclamation-triangle'" />
                <span><strong>{{ a.level === 'ALERT' ? 'Alerta' : 'Atenção' }}:</strong> {{ alertMessage(a) }}</span>
              </li>
            }
          </ul>
          <p class="st-small">Mudança forte da população: compare com o desafiante e avalie um novo treino ou scorecard.</p>
        } @else if (l.scorePsi === null) {
          <p class="st-small">Primeira população do campeão: ela vira a referência de estabilidade dos próximos retratos.</p>
        }
      </section>

      <div class="st-grid">
        <section class="app-card">
          <app-column-chart
            title="PSI do score por retrato"
            [summary]="psiSummary()"
            [series]="{ name: 'PSI do score', color: '--app-chart-1' }"
            [rows]="psiRows()"
            [money]="false"
            [axisFractionDigits]="2"
          />
        </section>
        <section class="app-card">
          <app-column-chart
            title="AUC realizada por safra"
            [summary]="aucSummary()"
            [series]="{ name: 'AUC realizada do campeão', color: '--app-chart-1' }"
            [rows]="aucRows()"
            [money]="false"
            [axisFractionDigits]="2"
          />
        </section>
      </div>
      <div class="st-grid">
        <section class="app-card">
          <app-bar-chart
            title="PSI por variável no último retrato"
            summary="Quanto a distribuição de cada variável mudou em relação ao treino; acima do limite de alerta, reavalie o modelo."
            categoryHeader="Variável"
            noteHeader="Situação"
            [series]="[{ name: 'PSI', color: '--app-chart-1' }]"
            [rows]="featureRows()"
          />
        </section>
        <section class="app-card">
          <app-bar-chart
            [title]="cohort() ? 'Calibração da safra de ' + (cohort()!.referenceDate | appDate: 'date') : 'Calibração por faixa'"
            [summary]="calibrationText()"
            categoryHeader="Faixa"
            noteHeader="Casos"
            [series]="[{ name: 'Chance prevista', color: '--app-chart-1' }, { name: 'Taxa observada', color: '--app-chart-2' }]"
            [rows]="calibration()"
          />
        </section>
      </div>

      <app-data-table
        [caption]="'Relatórios de monitoramento (últimos ' + max + ')'"
        [rows]="rows()"
        [columns]="columns"
        [loading]="reports.isLoading()"
        emptyIcon="activity"
        emptyMessage="Nenhum relatório."
      >
        <ng-template appCell="referenceDate" [appCellOf]="rows()" let-r>{{ r.referenceDate | appDate: 'date' }}</ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-r><app-status-chip domain="scoringMonitoring" [status]="r.status" /></ng-template>
        <ng-template appCell="population" [appCellOf]="rows()" let-r><span class="app-numeric">{{ int(r.population) }}</span></ng-template>
        <ng-template appCell="scorePsi" [appCellOf]="rows()" let-r><span class="app-numeric">{{ metric(r.scorePsi) }}</span></ng-template>
        <ng-template appCell="alerts" [appCellOf]="rows()" let-r>{{ r.alerts.length ? int(r.alerts.length) + ' alerta(s)' : 'Nenhum' }}</ng-template>
        <ng-template appCell="run" [appCellOf]="rows()" let-r><a [routerLink]="['/score/execucoes', r.runId]">Execução</a></ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .st-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 12px;
    }
    .st-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 12px;
    }
    .st-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
      gap: var(--app-gap);
      margin: var(--app-gap) 0;
    }
    .st-grid > .app-card + .app-card {
      margin-top: 0;
    }
    .st-alerts {
      margin: 12px 0 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
      li {
        display: flex;
        gap: 8px;
        padding: 4px 0;
        color: var(--bs-warning-text-emphasis);
      }
    }
    .st-alerts__alert {
      color: var(--bs-danger-text-emphasis) !important;
    }
    .st-small {
      margin: 8px 0 0;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
  `,
})
export class StabilityPanel {
  private readonly api = inject(ScoringMonitoringApi);
  private readonly settingsApi = inject(ScoringSettingsApi);
  private readonly modelsApi = inject(ScoringModelsApi);
  protected readonly int = integer;
  protected readonly metric = metric;
  protected readonly percent = percent;
  protected readonly max = MONITORING_REPORTS;

  protected readonly reports = rxResource({ stream: () => this.api.list({ page: 0, size: MONITORING_REPORTS }) });
  private readonly settingsResource = rxResource({ stream: () => this.settingsApi.get().pipe(catchError(() => of(null))) });
  private readonly catalogResource = rxResource({ stream: () => this.modelsApi.features().pipe(catchError(() => of<FeatureCatalog | null>(null))) });
  private readonly settings = valueOr<ScoringSettingsView | null | undefined>(this.settingsResource, null);
  private readonly features = computed(() => (this.catalogResource.hasValue() ? (this.catalogResource.value()?.allowed ?? []) : []));

  protected readonly rows = computed<MonitoringView[]>(() => (this.reports.hasValue() ? (this.reports.value()?.content ?? []) : []));
  protected readonly latest = computed(() => this.rows()[0] ?? null);
  protected readonly warning = computed(() => dec(this.settings()?.psiWarning) ?? 0.1);
  protected readonly alert = computed(() => dec(this.settings()?.psiAlert) ?? 0.25);
  protected readonly cohort = computed(() => latestCohort(this.rows()));

  protected readonly psiRows = computed(() => scorePsiRows(this.rows(), this.warning(), this.alert()));
  protected readonly aucRows = computed(() => realizedAucRows(this.latest()?.realized ?? []));
  protected readonly featureRows = computed(() => featurePsiRows(this.latest()?.featurePsi ?? {}, this.features(), this.warning(), this.alert()));
  protected readonly calibration = computed(() => calibrationRows(this.cohort()?.bands ?? []));
  protected readonly calibrationText = computed(() => calibrationSummary(this.cohort()?.bands ?? []));

  protected readonly scorePsiDetail = computed(() => {
    const v = dec(this.latest()?.scorePsi);
    return v === null ? 'Sem referência ainda' : `Situação: ${PSI_LEVEL_TEXT[psiLevel(v, this.warning(), this.alert())]}`;
  });
  protected readonly psiSummary = computed(() => {
    const rows = this.psiRows();
    if (!rows.length) {
      return 'Ainda sem PSI: a primeira população do campeão é a referência.';
    }
    const last = rows[rows.length - 1];
    return `Estabilidade da distribuição do score contra a referência do treino. Último retrato: ${last.display[0]} (${last.note}). Atenção a partir de ${metric(this.warning())} e alerta a partir de ${metric(this.alert())}.`;
  });
  protected readonly aucSummary = computed(() => {
    const rows = this.aucRows();
    return rows.length
      ? `Capacidade de ordenar quem paga nas safras apuradas (0,5 = sorteio; 1 = perfeito). Última safra: ${rows[rows.length - 1].display[0]}.`
      : 'Nenhuma safra apurada nos últimos 180 dias: o desfecho é medido depois da janela de pagamento.';
  });

  protected readonly columns: TableColumn[] = [
    { key: 'referenceDate', header: 'Retrato' },
    { key: 'status', header: 'Situação' },
    { key: 'population', header: 'População', align: 'end' },
    { key: 'scorePsi', header: 'PSI do score', align: 'end' },
    { key: 'alerts', header: 'Alertas' },
    { key: 'run', header: 'Origem' },
  ];

  protected alertMessage(a: MonitoringView['alerts'][number]): string {
    return alertText(a, this.features());
  }
}
