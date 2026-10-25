import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { BarChart } from '../../analytics/components/bar-chart';
import { KpiCard } from '../../analytics/components/kpi-card';
import { ScopeNotice } from '../components/scope-notice';
import { ScoringModelsApi } from '../data/scoring.api';
import { MODEL_KIND_LABELS, featureLabel } from '../data/scoring.labels';
import { FeatureCatalog, ModelView } from '../data/scoring.models';
import {
  VERDICT_TEXT,
  binText,
  brier,
  calibrationRows,
  calibrationSummary,
  compareMetrics,
  decimal2,
  integer,
  isLogistic,
  isScorecard,
  liftRows,
  metric,
  modelActions,
  percent,
  probabilityFromOdds,
  reasonsText,
  signedPoints,
  sortedTerms,
} from '../data/scoring.rules';
import { DecisionDialog, DecisionDialogData } from './decision-dialog';

/**
 * Ficha do modelo: métricas de validação fora do tempo (AUC/Gini, KS, Brier, calibração por faixa e
 * lift por decil), comparação com o campeão nas mesmas observações, definição (regras do scorecard
 * ou coeficientes da regressão) e a decisão manual de promover ou rejeitar o desafiante.
 */
@Component({
  selector: 'app-model-detail',
  imports: [Icon, RouterLink, AppDatePipe, EmptyState, PageHeader, Skeleton, StatusChip, BarChart, KpiCard, ScopeNotice],
  templateUrl: './model-detail.html',
  styleUrls: ['../../negotiation/detail-page.scss', './model-detail.scss'],
})
export class ModelDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ScoringModelsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly kinds = MODEL_KIND_LABELS;
  protected readonly verdicts = VERDICT_TEXT;
  protected readonly metric = metric;
  protected readonly brier = brier;
  protected readonly percent = percent;
  protected readonly int = integer;
  protected readonly decimal2 = decimal2;
  protected readonly binText = binText;
  protected readonly signedPoints = signedPoints;
  protected readonly reasons = reasonsText;
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  protected readonly resource = rxResource({
    params: () => (this.allCreditors() ? this.id() : undefined),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly model = valueOr<ModelView | undefined>(this.resource, undefined);
  private readonly list = rxResource({
    params: () => (this.allCreditors() ? true : undefined),
    stream: () => this.api.list().pipe(catchError(() => of<ModelView[]>([]))),
  });
  private readonly models = valueOr<ModelView[]>(this.list, []);
  protected readonly champion = computed(() => this.models().find((m) => m.status === 'CHAMPION' && m.id !== this.model()?.id) ?? null);
  private readonly catalog = rxResource({ stream: () => this.api.features().pipe(catchError(() => of<FeatureCatalog | null>(null))) });
  private readonly features = computed(() => (this.catalog.hasValue() ? (this.catalog.value()?.allowed ?? []) : []));
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const m = this.model();
      return m ? [m.trainedBy, m.promotedBy] : [];
    },
    { batch: USER_NAMES_BATCH },
  );

  protected readonly actions = computed(() => {
    const m = this.model();
    return m
      ? modelActions(m, { userId: this.auth.user()?.id ?? null, canManage: this.auth.hasPermission(Permission.SCORING_MANAGE), allCreditors: this.allCreditors() })
      : null;
  });
  protected readonly validation = computed(() => this.model()?.metrics.validation ?? null);
  protected readonly comparison = computed(() => {
    const m = this.model();
    return m?.metrics.championOnValidation ? compareMetrics(m.metrics.validation, m.metrics.championOnValidation) : [];
  });
  protected readonly calibration = computed(() => calibrationRows(this.validation()?.calibration ?? []));
  protected readonly calibrationText = computed(() => calibrationSummary(this.validation()?.calibration ?? []));
  protected readonly lift = computed(() => liftRows(this.validation()?.liftByDecile ?? []));
  protected readonly liftText = computed(() => {
    const first = this.validation()?.liftByDecile?.[0];
    return first
      ? `Quanto cada decil de score paga em relação à média. Os 10% de maior score pagam ${decimal2(first.lift)}× a média.`
      : 'Sem decis na validação.';
  });
  protected readonly scorecard = computed(() => {
    const d = this.model()?.definition;
    return d && isScorecard(d) ? d : null;
  });
  protected readonly logistic = computed(() => {
    const d = this.model()?.definition;
    return d && isLogistic(d) ? { intercept: d.intercept, terms: sortedTerms(d) } : null;
  });
  protected readonly anchorText = computed(() => {
    const s = this.scorecard();
    return s ? `${s.anchorPoints} pontos = ${percent(probabilityFromOdds(s.anchorOdds))} de chance; a cada ${s.pdo} pontos a chance dobra` : '';
  });
  protected readonly title = computed(() => {
    const m = this.model();
    return m ? `Modelo v${m.modelVersion} · ${MODEL_KIND_LABELS[m.kind]}` : 'Modelo do score';
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.model() ? `v${this.model()!.modelVersion}` : null));
  }

  protected feature(name: string): string {
    return featureLabel(this.features(), name);
  }

  protected userName(id: string | null): string {
    if (!id) {
      return 'Sistema';
    }
    return id === this.auth.user()?.id ? 'Você' : this.users.name(id);
  }

  protected decide(decision: 'PROMOTE' | 'REJECT'): void {
    const m = this.model();
    if (!m) {
      return;
    }
    this.dialog
      .open<DecisionDialog, DecisionDialogData, ModelView>(DecisionDialog, { data: { decision, model: m, champion: this.champion() }, size: 'lg' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success(decision === 'PROMOTE' ? `Modelo v${updated.modelVersion} promovido a campeão.` : `Desafiante v${updated.modelVersion} rejeitado.`);
          this.resource.set(updated);
          this.list.reload();
        }
      });
  }
}
