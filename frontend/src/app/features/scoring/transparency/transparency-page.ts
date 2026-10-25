import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { openDetail } from '../../../shared/components/detail-dialog/detail-dialog';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { ScopeNotice } from '../components/scope-notice';
import { ScoringModelsApi } from '../data/scoring.api';
import { DECISION_LABELS, TRANSFORM_LABELS } from '../data/scoring.labels';
import { DecisionView, EvaluationMetrics, FeatureCatalog } from '../data/scoring.models';
import { compareMetrics } from '../data/scoring.rules';

interface ComparisonModel {
  modelId?: string;
  modelVersion?: number;
  kind?: string;
  validation?: EvaluationMetrics;
}

/**
 * Transparência do score (LGPD art. 20): finalidade e limites de uso, a lista fechada de variáveis
 * permitidas e o que nunca é usado, e o registro das decisões automatizadas de modelo (promoções e
 * rejeições do desafiante), com quem decidiu, a justificativa e a comparação que foi vista.
 */
@Component({
  selector: 'app-transparency-page',
  imports: [Icon, RouterLink, AppDatePipe, CellDef, DataTable, EmptyState, PageHeader, Skeleton, ScopeNotice],
  template: `
    <app-page-header title="Decisões e variáveis do score" subtitle="Para que o score serve, com quais dados é calculado e quem decidiu cada troca de modelo." />

    @if (catalog.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o catálogo de variáveis." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="catalog.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!features()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else {
      @let f = features()!;
      <section class="app-card tp-purpose" aria-labelledby="tp-purpose" data-tour="scoring-purpose">
        <h2 class="app-card__title" id="tp-purpose"><app-icon name="shield-check" /> Finalidade e limites</h2>
        <p class="mb-2">{{ f.purpose }}</p>
        <p class="tp-small mb-0">
          Todo caso continua na fila e é atendido: o score muda a ordem, não o direito ao atendimento. A pedido do titular, um gestor
          revisa o score na ficha do caso, e a revisão fica registrada com o score anterior e o novo.
        </p>
      </section>

      <div class="tp-grid">
        <section class="app-card" aria-labelledby="tp-allowed" data-tour="scoring-features">
          <h2 class="app-card__title" id="tp-allowed">Variáveis usadas ({{ f.allowed.length }})</h2>
          <p class="tp-small">Somente dados da dívida e do relacionamento de cobrança. A lista é fixa no sistema: uma variável nova exige mudança de código e revisão.</p>
          <div class="table-responsive">
            <table class="table table-sm align-middle mb-0">
              <thead>
                <tr>
                  <th scope="col">Variável</th>
                  <th scope="col">Na regressão</th>
                </tr>
              </thead>
              <tbody>
                @for (v of f.allowed; track v.name) {
                  <tr>
                    <th scope="row" class="fw-normal">{{ v.label }}<div class="tp-code app-mono">{{ v.name }}</div></th>
                    <td>{{ transforms[v.transform] }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
        <section class="app-card" aria-labelledby="tp-never">
          <h2 class="app-card__title" id="tp-never">Nunca usados</h2>
          <p class="tp-small">Nem diretamente, nem como aproximação.</p>
          <ul class="tp-never">
            @for (n of f.neverUsed; track n) {
              <li><app-icon name="slash-circle" />{{ n }}</li>
            }
          </ul>
        </section>
      </div>
    }

    <h2 class="app-section-title mt-3" id="tp-decisions">Decisões de modelo</h2>
    @if (!allCreditors()) {
      <app-scoring-scope-notice title="O registro das decisões de modelo é consultado por quem acessa todos os credores." />
    } @else {
      <app-data-table
        caption="Decisões de promoção e rejeição de modelos"
        [rows]="decisions()"
        [columns]="columns"
        [loading]="decisionsResource.isLoading()"
        [failed]="decisionsResource.status() === 'error'"
        (retry)="decisionsResource.reload()"
        emptyIcon="card-checklist"
        emptyMessage="Nenhuma decisão registrada."
        emptyHint="O scorecard padrão segue como campeão até um gestor promover um desafiante."
      >
        <ng-template appCell="decidedAt" [appCellOf]="decisions()" let-d>{{ d.decidedAt | appDate }}</ng-template>
        <ng-template appCell="decision" [appCellOf]="decisions()" let-d>
          <span class="d-inline-flex align-items-center gap-1">
            <app-icon [name]="d.decision === 'PROMOTE' ? 'patch-check' : 'x-circle'" />
            {{ labels[d.decision] ?? d.decision }}
          </span>
        </ng-template>
        <ng-template appCell="model" [appCellOf]="decisions()" let-d>
          <a [routerLink]="['/score/modelos', d.modelId]">{{ version(d, 'challenger') }}</a>
          @if (d.previousChampionId) {
            <div class="tp-code">campeão anterior: <a [routerLink]="['/score/modelos', d.previousChampionId]">{{ version(d, 'champion') }}</a></div>
          }
        </ng-template>
        <ng-template appCell="decidedBy" [appCellOf]="decisions()" let-d>{{ users.name(d.decidedBy) }}</ng-template>
        <ng-template appCell="justification" [appCellOf]="decisions()" let-d><span class="tp-justification">{{ d.justification }}</span></ng-template>
        <ng-template appCell="actions" [appCellOf]="decisions()" let-d>
          <button type="button" class="btn btn-sm btn-link" (click)="open(d)" [attr.aria-label]="'Ver a comparação vista na decisão de ' + version(d, 'challenger')">Ver comparação</button>
        </ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .tp-purpose h2 {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .tp-small {
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .tp-grid {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: var(--app-gap);
      margin-top: var(--app-gap);
    }
    .tp-grid > .app-card + .app-card {
      margin-top: 0;
    }
    .tp-code {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .tp-never {
      margin: 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        gap: 8px;
        padding: 4px 0;
        font-size: var(--app-text-sm);
      }
    }
    .tp-justification {
      display: block;
      max-width: 420px;
      white-space: normal;
    }
    @media (max-width: 959px) {
      .tp-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class TransparencyPage {
  private readonly api = inject(ScoringModelsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  protected readonly transforms = TRANSFORM_LABELS;
  protected readonly labels = DECISION_LABELS;
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  protected readonly catalog = rxResource({ stream: () => this.api.features() });
  protected readonly features = valueOr<FeatureCatalog | undefined>(this.catalog, undefined);
  protected readonly decisionsResource = rxResource({
    params: () => (this.allCreditors() ? true : undefined),
    stream: () => this.api.decisions(),
  });
  protected readonly decisions = valueOr<DecisionView[]>(this.decisionsResource, []);
  protected readonly users = nameMap(inject(UserNames), () => this.decisions().map((d) => d.decidedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'decidedAt', header: 'Data' },
    { key: 'decision', header: 'Decisão' },
    { key: 'model', header: 'Modelo' },
    { key: 'decidedBy', header: 'Decidido por' },
    { key: 'justification', header: 'Justificativa' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected version(d: DecisionView, which: 'challenger' | 'champion'): string {
    const v = (d.comparison[which] as ComparisonModel | undefined)?.modelVersion;
    return v ? `v${v}` : which === 'challenger' ? 'Modelo' : 'Modelo anterior';
  }

  protected open(d: DecisionView): void {
    const challenger = d.comparison['challenger'] as ComparisonModel | undefined;
    const champion = d.comparison['championOnSameValidation'] as EvaluationMetrics | undefined;
    const rows = compareMetrics(challenger?.validation, champion);
    openDetail(
      this.dialog,
      {
        title: `${DECISION_LABELS[d.decision] ?? d.decision} · ${this.version(d, 'challenger')}`,
        subtitle: 'Comparação vista por quem decidiu, registrada com a decisão',
        fields: [
          { label: 'Data', value: new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(d.decidedAt)) },
          { label: 'Decidido por', value: this.users.name(d.decidedBy) },
          { label: 'Justificativa', value: d.justification },
          ...rows.map((r) => ({ label: r.label, value: `desafiante ${r.challenger} · campeão ${r.champion}` })),
        ],
        sections: [{ label: 'Comparação completa', json: d.comparison }],
      },
      { size: 'lg' },
    );
  }
}
