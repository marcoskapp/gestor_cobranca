import { Component, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { dueText } from '../../compliance/data/compliance.labels';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { DisputesApi } from '../data/collection.api';
import { DISPUTE_ORIGIN_LABELS, DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { DisputeFilter, DisputeView } from '../data/collection.models';
import { daysBetween, zonedNow } from '../data/collection.rules';
import { DisputeDetail, DisputeDetailData } from './dispute-detail';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DialogService } from '../../../shared/dialog/dialog.service';

export function toDisputeFilter(v: { creditor: string[]; status: string; overdue: boolean }): DisputeFilter {
  return {
    creditorId: v.creditor[0] ?? null,
    status: v.status === 'PENDING' ? 'OPEN,UNDER_REVIEW' : v.status || null,
    overdue: v.overdue ? true : null,
  };
}

/** Fila de contestações (F21) com prazo de resposta em destaque, evidências e resolução. */
@Component({
  selector: 'app-dispute-list',
  imports: [ReactiveFormsModule, RouterLink, CellDef, DataTable, EntityPicker, PageHeader, StatusChip, AppDatePipe, Icon, NgbTooltip, ...FORM_FIELD],
  template: `
    <app-page-header title="Contestações" subtitle="Dívidas contestadas pelo devedor, credor ou jurídico. Contestação bloqueante suspende cobrança e contato até a resolução." />

    <section class="dl-kpis" aria-label="Situação das contestações" data-tour="dispute-kpis">
      @let k = counters.hasValue() ? counters.value() : null;
      <button type="button" class="dl-kpi" (click)="show('PENDING', true)">
        <span class="dl-kpi__label">Prazo vencido</span>
        <span class="dl-kpi__value" [class.dl-late]="(k?.overdue ?? 0) > 0">{{ k?.overdue ?? '—' }}</span>
      </button>
      <button type="button" class="dl-kpi" (click)="show('OPEN', false)">
        <span class="dl-kpi__label">Abertas</span>
        <span class="dl-kpi__value">{{ k?.open ?? '—' }}</span>
      </button>
      <button type="button" class="dl-kpi" (click)="show('UNDER_REVIEW', false)">
        <span class="dl-kpi__label">Em análise</span>
        <span class="dl-kpi__value">{{ k?.review ?? '—' }}</span>
      </button>
    </section>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar contestações">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="PENDING">Pendentes (abertas e em análise)</option>
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      <div class="form-check form-switch dl-toggle">
        <input class="form-check-input" type="checkbox" role="switch" id="dispute-list-overdue" formControlName="overdue" />
        <label class="form-check-label" for="dispute-list-overdue">Somente com prazo vencido</label>
      </div>
    </form>

    <app-data-table
      caption="Contestações"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="hammer"
      emptyMessage="Nenhuma contestação encontrada."
    >
      <ng-template appCell="due" [appCellOf]="query.rows()" let-d>
        @if (pending(d)) {
          <span [class.dl-late]="d.overdue || days(d) < 0" [class.dl-soon]="!d.overdue && days(d) >= 0 && days(d) <= 2">
            @if (d.overdue || days(d) < 0) {
              <app-icon class="dl-icon" name="alarm" />
            }
            {{ due(d) }}
          </span>
        }
        <div class="app-muted">{{ d.responseDueDate | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-d>
        <a href="" (click)="$event.preventDefault(); openDetail(d)">{{ types[d.type] }}</a>
        <div class="app-muted">{{ origins[d.origin] }} · {{ d.openedAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-d>
        <a [routerLink]="['/cobranca/casos', d.caseId]">{{ debtors.name(d.debtorId) }}</a>
        <div class="app-muted">{{ creditors.name(d.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d>
        <app-status-chip domain="dispute" [status]="d.status" />
        @if (d.blocksCollection && pending(d)) {
          <div class="app-muted">cobrança suspensa</div>
        }
      </ng-template>
      <ng-template appCell="evidence" [appCellOf]="query.rows()" let-d><span class="app-numeric">{{ d.evidenceCount }}</span></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="openDetail(d)" aria-label="Abrir contestação" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </button>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dl-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .dl-kpi {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px 16px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
      &:hover {
        border-color: var(--bs-primary);
      }
    }
    .dl-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .dl-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    .dl-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
    .dl-soon {
      color: var(--app-warning-fg);
    }
    .dl-toggle {
      align-self: center;
    }
    .dl-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      vertical-align: -3px;
    }
  `,
})
export class DisputeList {
  private readonly api = inject(DisputesApi);
  private readonly dialog = inject(DialogService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly types = DISPUTE_TYPE_LABELS;
  protected readonly origins = DISPUTE_ORIGIN_LABELS;
  protected readonly statuses = statusOptions('dispute');
  private readonly today = zonedNow().date;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: ['PENDING'], overdue: [false] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toDisputeFilter(this.filters.getRawValue())),
    ),
    { initialValue: toDisputeFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((d) => d.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((d) => d.creditorId));

  /** Indicadores (totais do servidor; uma consulta de 1 item por indicador). */
  protected readonly counters = rxResource({
    params: () => this.filter().creditorId ?? '',
    stream: ({ params }) => {
      const total = (f: DisputeFilter) =>
        this.api.search({ ...f, creditorId: params || null }, { page: 0, size: 1 }).pipe(
          map((p) => p.totalElements),
          catchError(() => of(null)),
        );
      return forkJoin({ overdue: total({ status: 'OPEN,UNDER_REVIEW', overdue: true }), open: total({ status: 'OPEN' }), review: total({ status: 'UNDER_REVIEW' }) });
    },
  });

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Prazo' },
    { key: 'type', header: 'Motivo' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
    { key: 'evidence', header: 'Evidências', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected pending(d: DisputeView): boolean {
    return d.status === 'OPEN' || d.status === 'UNDER_REVIEW';
  }

  protected days(d: DisputeView): number {
    return daysBetween(this.today, d.responseDueDate);
  }

  protected due(d: DisputeView): string {
    return dueText(this.days(d), d.overdue);
  }

  protected show(status: string, overdue: boolean): void {
    this.filters.patchValue({ status, overdue });
  }

  protected openDetail(d: DisputeView): void {
    this.dialog
      .open<DisputeDetail, DisputeDetailData, boolean>(DisputeDetail, { data: { dispute: d, debtorName: this.debtors.name(d.debtorId) }, size: 'lg' })
      .afterClosed()
      .subscribe((changed) => {
        if (changed) {
          this.query.reload();
          this.counters.reload();
        }
      });
  }
}
