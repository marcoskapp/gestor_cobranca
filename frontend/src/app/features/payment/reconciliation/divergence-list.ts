import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, RouterLink, Amount, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Divergências de conciliação" subtitle="Créditos que o sistema não conseguiu conciliar sozinho. Cada decisão fica registrada com a justificativa.">
      <a class="btn btn-link d-inline-flex align-items-center gap-2" routerLink="/pagamentos/conciliacao">
        <app-icon name="bank" />
        Extratos
      </a>
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar divergências">
      <div class="d-flex flex-wrap gap-2 app-control-offset" role="group" aria-label="Tipo de divergência">
        <input type="radio" class="btn-check" formControlName="type" value="" id="dl-type-all" />
        <label class="btn btn-outline-primary" for="dl-type-all">Todas</label>
        @for (t of types; track t[0]) {
          <input type="radio" class="btn-check" formControlName="type" [value]="t[0]" [id]="'dl-type-' + t[0]" />
          <label class="btn btn-outline-primary" [for]="'dl-type-' + t[0]">{{ t[1] }}</label>
        }
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
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
      emptyIcon="check2-circle"
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
          <button class="btn btn-outline-primary" type="button" (click)="resolve(d)">Resolver</button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
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
  private readonly dialog = inject(DialogService);
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
      .open<ResolveDialog, ResolveDialogData, DivergenceView>(ResolveDialog, { data: { divergence: d }, size: 'lg' })
      .afterClosed()
      .subscribe((resolved) => {
        if (resolved) {
          this.notifications.success('Divergência resolvida.');
          this.query.reload();
        }
      });
  }
}
