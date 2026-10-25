import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { EMPTY, catchError, finalize, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ScopeNotice } from '../components/scope-notice';
import { ScoringModelsApi } from '../data/scoring.api';
import { MODEL_KIND_LABELS } from '../data/scoring.labels';
import { ModelView } from '../data/scoring.models';
import { metric } from '../data/scoring.rules';
import { ScorecardDialog, ScorecardDialogData } from './scorecard-dialog';

/**
 * Versões do modelo de score (mais recente primeiro), com o campeão vigente e o desafiante em
 * destaque. Gestores pedem o treino da regressão logística (assíncrono) ou criam um scorecard,
 * que entra como desafiante; a promoção é sempre manual, na ficha do modelo.
 */
@Component({
  selector: 'app-model-list',
  imports: [Icon, NgbTooltip, RouterLink, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip, ScopeNotice],
  template: `
    <app-page-header title="Modelos do score" subtitle="Campeão em uso, desafiante em avaliação e o histórico de versões, com as métricas de validação.">
      @if (canManage()) {
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="createScorecard()" data-tour="scoring-scorecard">
          <app-icon name="list-check" />
          Criar scorecard
        </button>
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="train()" [disabled]="training()" data-tour="scoring-train">
          <app-icon name="graph-up" />
          Pedir treino
        </button>
      }
    </app-page-header>
    @if (!allCreditors()) {
      <app-scoring-scope-notice />
    } @else {
      @if (rows().length) {
        <section class="ml-highlights" aria-label="Campeão e desafiante" data-tour="scoring-champion">
          @if (champion(); as c) {
            <a class="app-card ml-highlight" [routerLink]="['/score/modelos', c.id]">
              <span class="ml-highlight__label"><app-icon name="patch-check" />Campeão em uso</span>
              <strong>v{{ c.modelVersion }} · {{ kinds[c.kind] }}</strong>
              <span class="app-muted">{{ c.promotedAt ? 'Promovido em ' + (c.promotedAt | appDate: 'date') : 'Campeão inicial (scorecard padrão)' }} · AUC {{ auc(c) }}</span>
            </a>
          }
          @if (challenger(); as c) {
            <a class="app-card ml-highlight ml-highlight--challenger" [routerLink]="['/score/modelos', c.id]">
              <span class="ml-highlight__label"><app-icon name="hourglass-split" />Desafiante aguardando decisão</span>
              <strong>v{{ c.modelVersion }} · {{ kinds[c.kind] }}</strong>
              <span class="app-muted">Criado em {{ c.trainedAt | appDate: 'date' }} · AUC de validação {{ auc(c) }}</span>
            </a>
          } @else {
            <div class="app-card ml-highlight">
              <span class="ml-highlight__label"><app-icon name="hourglass-split" />Sem desafiante</span>
              <span class="app-muted">Um treino aprovado ou um scorecard novo entra como desafiante e é calculado em sombra nos retratos.</span>
            </div>
          }
        </section>
      }
      <app-data-table
        caption="Versões do modelo de score"
        [rows]="rows()"
        [columns]="columns"
        [loading]="resource.isLoading()"
        [failed]="resource.status() === 'error'"
        (retry)="resource.reload()"
        emptyIcon="diagram-3"
        emptyMessage="Nenhum modelo ainda."
        emptyHint="O scorecard padrão vira o campeão inicial quando a base legal do score é registrada."
      >
        @if (canConfigure()) {
          <a class="btn btn-outline-primary" appEmptyAction routerLink="/score/parametros">Registrar base legal</a>
        }
        <ng-template appCell="version" [appCellOf]="rows()" let-m><strong>v{{ m.modelVersion }}</strong></ng-template>
        <ng-template appCell="kind" [appCellOf]="rows()" let-m>
          {{ kinds[m.kind] }}
          @if (m.description) {
            <div class="app-muted ml-small">{{ m.description }}</div>
          }
        </ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-m><app-status-chip domain="scoringModel" [status]="m.status" /></ng-template>
        <ng-template appCell="auc" [appCellOf]="rows()" let-m>
          <span class="app-numeric">{{ auc(m) }}</span>
          @if (m.metrics.backtest) {
            <div class="app-muted ml-small">retroteste</div>
          }
        </ng-template>
        <ng-template appCell="trainedAt" [appCellOf]="rows()" let-m>{{ m.trainedAt | appDate }}</ng-template>
        <ng-template appCell="actions" [appCellOf]="rows()" let-m>
          <a class="btn btn-sm btn-link" [routerLink]="['/score/modelos', m.id]" [attr.aria-label]="'Abrir modelo v' + m.modelVersion" ngbTooltip="Abrir">
            <app-icon name="chevron-right" />
          </a>
        </ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .ml-small {
      font-size: var(--app-text-xs);
    }
    .ml-highlights {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .ml-highlight {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin: 0;
      color: inherit;
      text-decoration: none;
    }
    a.ml-highlight:hover strong {
      text-decoration: underline;
    }
    .ml-highlight__label {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .ml-highlight--challenger {
      border-color: var(--bs-info-border-subtle);
    }
  `,
})
export class ModelList {
  private readonly api = inject(ScoringModelsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  protected readonly kinds = MODEL_KIND_LABELS;
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);
  protected readonly canManage = computed(() => this.allCreditors() && this.auth.hasPermission(Permission.SCORING_MANAGE));
  protected readonly canConfigure = this.canManage;
  protected readonly training = signal(false);

  protected readonly resource = rxResource({
    params: () => (this.allCreditors() ? true : undefined),
    stream: () => this.api.list(),
  });
  protected readonly rows = valueOr<ModelView[]>(this.resource, []);
  protected readonly champion = computed(() => this.rows().find((m) => m.status === 'CHAMPION') ?? null);
  protected readonly challenger = computed(() => this.rows().find((m) => m.status === 'CHALLENGER') ?? null);

  protected readonly columns: TableColumn[] = [
    { key: 'version', header: 'Versão' },
    { key: 'kind', header: 'Tipo' },
    { key: 'status', header: 'Situação' },
    { key: 'auc', header: 'AUC de validação', align: 'end' },
    { key: 'trainedAt', header: 'Criado em' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected auc(m: ModelView): string {
    return metric(m.metrics.validation?.auc ?? null);
  }

  protected train(): void {
    this.confirm
      .confirm({
        title: 'Pedir treino do modelo estatístico',
        message:
          'O treino roda em segundo plano com o histórico da assessoria. Só acontece com dados suficientes; se aprovado na validação, o modelo novo entra como desafiante — nunca substitui o campeão sozinho.',
        confirmLabel: 'Pedir treino',
        details: [{ label: 'Quem promove', value: 'Outro gestor, depois de comparar com o campeão (você não pode promover o próprio treino)' }],
      })
      .pipe(
        switchMap(() => {
          this.training.set(true);
          return this.api.train().pipe(
            finalize(() => this.training.set(false)),
            catchError(() => EMPTY),
          );
        }),
      )
      .subscribe((req) => {
        this.notifications.success('Treino pedido. Acompanhe pela execução.');
        void this.router.navigate(['/score/execucoes', req.runId]);
      });
  }

  protected createScorecard(): void {
    this.dialog
      .open<ScorecardDialog, ScorecardDialogData, ModelView>(ScorecardDialog, {
        data: { templates: this.rows().filter((m) => m.kind === 'SCORECARD') },
        size: 'xl',
      })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          this.notifications.success(`Scorecard v${created.modelVersion} criado como desafiante.`);
          this.resource.reload();
          void this.router.navigate(['/score/modelos', created.id]);
        }
      });
  }
}
