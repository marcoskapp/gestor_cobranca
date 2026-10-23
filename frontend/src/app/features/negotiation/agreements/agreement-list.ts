import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { Amount } from '../../../shared/components/amount/amount';
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
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { AgreementsApi } from '../data/agreement.api';
import { AGREEMENT_ORIGIN_LABELS } from '../data/agreement.labels';
import { AgreementFilter, AgreementView } from '../data/agreement.models';
import { scheduleSummary } from '../data/agreement.rules';

export function toAgreementFilter(v: { creditor: string[]; status: string }): AgreementFilter {
  return { creditorId: v.creditor[0] ?? null, debtorId: null, caseId: null, status: v.status || null };
}

/** Acordos formalizados com progresso do cronograma e situação (em pagamento, quitado, quebrado…). */
@Component({
  selector: 'app-agreement-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    Amount,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
  ],
  template: `
    <app-page-header title="Acordos" subtitle="Acordos formalizados, parcelas pagas e em aberto. Quebra e quitação são atualizadas automaticamente." />

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar acordos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
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
      caption="Acordos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="handshake"
      emptyMessage="Nenhum acordo encontrado."
      emptyHint="Acordos nascem da formalização de uma proposta aceita pelo devedor."
    >
      <ng-template appCell="formalizedAt" [appCellOf]="query.rows()" let-a>
        <a [routerLink]="['/negociacao/acordos', a.id]">{{ a.formalizedAt | appDate: 'date' }}</a>
        <div class="app-muted">{{ origins[a.origin] }}</div>
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-a>
        {{ debtors.name(a.debtorId) }}
        <div class="app-muted">{{ creditors.name(a.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="total" [appCellOf]="query.rows()" let-a>
        <app-amount [value]="a.totalAmount" />
        <div class="app-muted">pago <app-amount [value]="a.paidAmount" /></div>
      </ng-template>
      <ng-template appCell="progress" [appCellOf]="query.rows()" let-a>
        @let s = summary(a);
        <span class="app-numeric">{{ s.paid }} de {{ s.total }} pagas</span>
        @if (s.overdue) {
          <div class="al-late">{{ s.overdue }} vencida(s)</div>
        } @else if (s.next) {
          <div class="app-muted">próxima {{ s.next.dueDate | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-a>
        <app-status-chip domain="agreement" [status]="a.status" />
        @if (a.renegotiationNumber > 0) {
          <div class="app-muted">renegociação {{ a.renegotiationNumber }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-a>
        <a mat-icon-button [routerLink]="['/negociacao/acordos', a.id]" aria-label="Abrir acordo" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .al-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
  `,
})
export class AgreementList {
  private readonly api = inject(AgreementsApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly statuses = statusOptions('agreement');
  protected readonly origins = AGREEMENT_ORIGIN_LABELS;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toAgreementFilter(this.filters.getRawValue())),
    ),
    { initialValue: toAgreementFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((a) => a.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((a) => a.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'formalizedAt', header: 'Formalizado em' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'total', header: 'Total', align: 'end' },
    { key: 'progress', header: 'Parcelas' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected summary(a: AgreementView) {
    return scheduleSummary(a);
  }
}
