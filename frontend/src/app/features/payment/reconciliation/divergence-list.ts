import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ReconciliationApi } from '../data/payment.api';
import { DIVERGENCE_TYPE_LABELS, RESOLUTION_LABELS } from '../data/payment.labels';
import { DivergenceFilter, DivergenceType, DivergenceView } from '../data/payment.models';
import { ResolveDialog, ResolveDialogData } from './resolve-dialog';

/** Fila de divergências da conciliação por tipo, com resolução auditada (justificativa obrigatória). */
@Component({
  selector: 'app-divergence-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Divergências de conciliação" subtitle="Créditos que o sistema não conseguiu conciliar sozinho. Cada decisão fica registrada com a justificativa.">
      <a mat-button routerLink="/pagamentos/conciliacao">
        <mat-icon aria-hidden="true">account_balance</mat-icon>
        Extratos
      </a>
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar divergências">
      <mat-button-toggle-group formControlName="type" aria-label="Tipo de divergência" class="dl-types">
        <mat-button-toggle value="">Todas</mat-button-toggle>
        @for (t of types; track t[0]) {
          <mat-button-toggle [value]="t[0]">{{ t[1] }}</mat-button-toggle>
        }
      </mat-button-toggle-group>
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>
    <app-data-table
      caption="Divergências"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="task_alt"
      emptyMessage="Nenhuma divergência com estes filtros."
      emptyHint="Divergências surgem na importação de extratos e nas notificações do Pix."
    >
      <ng-template appCell="occurredOn" [appCellOf]="query.rows()" let-d>
        {{ d.occurredOn ? (d.occurredOn | appDate: 'date') : (d.createdAt | appDate: 'date') }}
        <div class="app-muted dl-small">{{ d.source === 'WEBHOOK' ? 'Notificação Pix' : 'Extrato' }}</div>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-d>
        {{ typeLabels[asType(d.type)] }}
        <div class="app-muted dl-small">{{ d.creditorId ? creditors.name(d.creditorId) : 'Sem credor identificado' }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-d>
        <app-amount [value]="d.amount" />
        @if (d.expectedAmount !== null) {
          <div class="app-muted dl-small">esperado <app-amount [value]="d.expectedAmount" /></div>
        }
      </ng-template>
      <ng-template appCell="description" [appCellOf]="query.rows()" let-d>
        <span class="dl-desc">{{ d.description ?? d.providerReference ?? '—' }}</span>
        @if (d.paymentId) {
          <div><a class="dl-small" [routerLink]="['/pagamentos', d.paymentId]">Pagamento relacionado</a></div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d>
        <app-status-chip domain="divergence" [status]="d.status" />
        @if (d.resolution) {
          <div class="app-muted dl-small" [attr.title]="d.resolutionNote">{{ resolutions[d.resolution] }} · {{ users.name(d.resolvedBy) }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        @if (d.status === 'OPEN') {
          <button mat-stroked-button type="button" (click)="resolve(d)">Resolver</button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dl-types {
      flex-wrap: wrap;
    }
    .dl-small {
      font-size: var(--app-text-xs);
    }
    .dl-desc {
      display: inline-block;
      max-width: 320px;
      white-space: normal;
    }
  `,
})
export class DivergenceList {
  private readonly api = inject(ReconciliationApi);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  protected readonly types = Object.entries(DIVERGENCE_TYPE_LABELS);
  protected readonly typeLabels = DIVERGENCE_TYPE_LABELS;
  protected readonly resolutions = RESOLUTION_LABELS;
  protected readonly statuses = statusOptions('divergence');
  protected readonly asType = (t: string) => t as DivergenceType;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ type: [this.params.get('tipo') ?? ''], status: [this.params.get('situacao') ?? 'OPEN'] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(150),
      map((): DivergenceFilter => {
        const v = this.filters.getRawValue();
        return { type: v.type || null, status: v.status || null };
      }),
    ),
    { initialValue: { type: this.params.get('tipo') || null, status: this.params.get('situacao') ?? 'OPEN' } as DivergenceFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.divergences(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((d) => d.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((d) => d.resolvedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'occurredOn', header: 'Data e fonte' },
    { key: 'type', header: 'Tipo e credor' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'description', header: 'Histórico' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected resolve(d: DivergenceView): void {
    this.dialog
      .open<ResolveDialog, ResolveDialogData, DivergenceView>(ResolveDialog, { data: { divergence: d }, width: '640px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((resolved) => {
        if (resolved) {
          this.notifications.success('Divergência resolvida.');
          this.query.reload();
        }
      });
  }
}
