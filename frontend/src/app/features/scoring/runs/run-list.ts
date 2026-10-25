import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { emptyPage } from '../../../shared/models/page';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ScopeNotice } from '../components/scope-notice';
import { ScoringRunsApi } from '../data/scoring.api';
import { RUN_KIND_LABELS, RUN_TRIGGER_LABELS } from '../data/scoring.labels';
import { RunKindFilter, RunView } from '../data/scoring.models';
import { durationText, runSummary } from '../data/scoring.rules';

/**
 * Execuções do score: escoragem diária em lote (com retrato semanal para treino e monitoramento) e
 * treinos do modelo estatístico. Quem gerencia pode antecipar a escoragem de hoje.
 */
@Component({
  selector: 'app-run-list',
  imports: [Icon, NgbTooltip, ...FORM_FIELD, ReactiveFormsModule, RouterLink, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip, ScopeNotice],
  template: `
    <app-page-header title="Execuções do score" subtitle="Escoragem diária dos casos ativos e treinos do modelo, com o resultado de cada uma.">
      @if (canRun()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="start()" [disabled]="starting()">
          <app-icon name="play" />
          Escorar agora
        </button>
      }
    </app-page-header>
    @if (!allCreditors()) {
      <app-scoring-scope-notice />
    } @else {
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar execuções">
        <app-field label="Tipo">
          <select appInput formControlName="kind">
            <option value="">Todas</option>
            <option value="BATCH">{{ kinds.BATCH }}</option>
            <option value="TRAINING">{{ kinds.TRAINING }}</option>
          </select>
        </app-field>
      </form>
      <app-data-table
        caption="Execuções do score"
        [rows]="query.rows()"
        [columns]="columns"
        [loading]="query.loading()"
        [failed]="query.failed()"
        [page]="query.tablePage()"
        (pageChange)="query.setPage($event)"
        (retry)="query.reload()"
        emptyIcon="arrow-repeat"
        emptyMessage="Nenhuma execução registrada."
        emptyHint="A escoragem roda todo dia de madrugada depois que a base legal é registrada."
      >
        <ng-template appCell="referenceDate" [appCellOf]="query.rows()" let-r>
          {{ r.referenceDate | appDate: 'date' }}
          @if (r.snapshot) {
            <div class="app-muted rl-small">com retrato</div>
          }
        </ng-template>
        <ng-template appCell="kind" [appCellOf]="query.rows()" let-r>
          {{ kinds[r.kind] }}
          <div class="app-muted rl-small">{{ triggers[r.trigger] }}</div>
        </ng-template>
        <ng-template appCell="status" [appCellOf]="query.rows()" let-r><app-status-chip domain="scoringRun" [status]="r.status" /></ng-template>
        <ng-template appCell="result" [appCellOf]="query.rows()" let-r>{{ summary(r) }}</ng-template>
        <ng-template appCell="startedAt" [appCellOf]="query.rows()" let-r>
          {{ r.startedAt | appDate }}
          <div class="app-muted rl-small">{{ duration(r) }}</div>
        </ng-template>
        <ng-template appCell="actions" [appCellOf]="query.rows()" let-r>
          <a class="btn btn-sm btn-link" [routerLink]="['/score/execucoes', r.id]" aria-label="Abrir execução" ngbTooltip="Abrir">
            <app-icon name="chevron-right" />
          </a>
        </ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .rl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class RunList {
  private readonly api = inject(ScoringRunsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  protected readonly kinds = RUN_KIND_LABELS;
  protected readonly triggers = RUN_TRIGGER_LABELS;
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);
  protected readonly canRun = computed(() => this.allCreditors() && this.auth.hasPermission(Permission.SCORING_MANAGE));
  protected readonly starting = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ kind: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      map(() => (this.filters.getRawValue().kind || null) as RunKindFilter),
    ),
    { initialValue: null as RunKindFilter },
  );
  protected readonly query = pagedQuery(this.filter, (kind, page) =>
    this.allCreditors() ? this.api.list(kind, page) : of(emptyPage<RunView>(page.size)),
  );

  protected readonly columns: TableColumn[] = [
    { key: 'referenceDate', header: 'Data de referência' },
    { key: 'kind', header: 'Tipo' },
    { key: 'status', header: 'Situação' },
    { key: 'result', header: 'Resultado' },
    { key: 'startedAt', header: 'Início e duração' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected summary(r: RunView): string {
    return runSummary(r);
  }

  protected duration(r: RunView): string {
    return durationText(r.startedAt, r.finishedAt);
  }

  protected start(): void {
    this.confirm
      .confirm({
        title: 'Escorar os casos agora',
        message:
          'Recalcula o score de todos os casos ativos com o campeão vigente, como a escoragem diária. Se a de hoje já existe, ela é aberta em vez de começar outra.',
        confirmLabel: 'Escorar agora',
      })
      .pipe(
        switchMap(() => {
          this.starting.set(true);
          return this.api.startBatch().pipe(finalize(() => this.starting.set(false)));
        }),
      )
      .subscribe((run) => {
        this.notifications.success(run.status === 'RUNNING' ? 'Escoragem em andamento.' : 'A escoragem de hoje já foi feita.');
        this.query.reload();
        void this.router.navigate(['/score/execucoes', run.id]);
      });
  }
}
