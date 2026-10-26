import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { catchError, debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { AgreementReviewBadge, PaymentReviewStatusApi } from '../../payment/data/review-status';
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
    Icon,
    NgbTooltip,
    ...FORM_FIELD,
    ReactiveFormsModule,
    RouterLink,
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
      caption="Acordos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="person-hearts"
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
        @if (badge(a.id); as b) {
          <div class="al-review">
            @if (b.pendingCount) {
              <span class="badge rounded-pill border fw-normal d-inline-flex align-items-center gap-1 bg-warning-subtle text-warning-emphasis border-warning-subtle">
                <app-icon name="hourglass-split" />Pagamento em revisão
                <span class="visually-hidden">: {{ b.pendingCount }} informado(s), <app-amount [value]="b.pendingTotal" />, aguardando aprovação</span>
              </span>
            }
            @if (b.rejectedCount) {
              <span class="badge rounded-pill border fw-normal d-inline-flex align-items-center gap-1 bg-danger-subtle text-danger-emphasis border-danger-subtle">
                <app-icon name="x-circle" />Pagamento recusado
                <span class="visually-hidden">: {{ b.rejectedCount }} nos últimos 30 dias</span>
              </span>
            }
          </div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-a>
        <a class="btn btn-sm btn-link" [routerLink]="['/negociacao/acordos', a.id]" aria-label="Abrir acordo" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .al-late {
      font-weight: 600;
      color: var(--bs-danger-text-emphasis);
    }
    .al-review {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      margin-top: 0.25rem;
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

  private readonly auth = inject(AuthService);
  private readonly reviewApi = inject(PaymentReviewStatusApi);
  /** Selo "pagamento em revisão/recusado": uma consulta por página (sem N+1). */
  private readonly rowIds = computed(() => this.query.rows().map((a) => a.id), {
    equal: (x, y) => x.length === y.length && x.every((id, i) => id === y[i]),
  });
  private readonly badges = rxResource({
    params: () => (this.auth.hasPermission(Permission.AGREEMENT_VIEW) && this.rowIds().length ? this.rowIds() : undefined),
    stream: ({ params }) =>
      this.reviewApi.agreements(params).pipe(
        map((list) => new Map(list.map((b) => [b.agreementId, b] as const))),
        catchError(() => of(new Map<string, AgreementReviewBadge>())),
      ),
  });

  protected badge(id: string): AgreementReviewBadge | null {
    return (this.badges.hasValue() ? this.badges.value().get(id) : undefined) ?? null;
  }

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
