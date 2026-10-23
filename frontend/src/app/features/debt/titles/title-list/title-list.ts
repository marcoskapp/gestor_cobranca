import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { Amount } from '../../../../shared/components/amount/amount';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { sameValue } from '../../../../shared/data/same-value';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { toLocalDate } from '../../../../shared/models/dates';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { CreditorLookup, CreditorNames, PortfolioLookup, PortfolioNames } from '../../../organization/data/organization.lookups';
import { OverdueBadge } from '../../components/overdue-badge';
import { TitlesApi } from '../../data/debt.api';
import { OVERDUE_BUCKETS, titleLabel } from '../../data/debt.labels';
import { DebtorNames } from '../../data/debt.lookups';
import { TitleFilter } from '../../data/debt.models';

/** Converte o formulário de filtros no `TitleSearch` do backend. */
export function toTitleFilter(v: {
  creditor: string[];
  portfolio: string[];
  debtorId: string;
  status: string;
  bucket: string;
  dueFrom: Date | null;
  dueTo: Date | null;
}): TitleFilter {
  const bucket = OVERDUE_BUCKETS.find((b) => b.value === v.bucket);
  return {
    creditorId: v.creditor[0] ?? null,
    portfolioId: v.portfolio[0] ?? null,
    debtorId: v.debtorId || null,
    status: v.status || null,
    minOverdueDays: bucket?.min ?? null,
    maxOverdueDays: bucket?.max ?? null,
    dueFrom: toLocalDate(v.dueFrom),
    dueTo: toLocalDate(v.dueTo),
  };
}

/** Consulta de títulos por credor, carteira, situação, faixa de atraso e vencimento. */
@Component({
  selector: 'app-title-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    StatusChip,
    Amount,
    AppDatePipe,
    OverdueBadge,
  ],
  templateUrl: './title-list.html',
  styles: `
    .title-list__flag {
      margin-left: 6px;
    }
    .title-list__debtor {
      align-self: center;
      padding-right: 0;
    }
    .title-list__debtor button {
      --mat-icon-button-state-layer-size: 24px;
      width: 24px;
      height: 24px;
      padding: 0;
    }
  `,
})
export class TitleList {
  private readonly api = inject(TitlesApi);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);
  private readonly portfolioLookup = inject(PortfolioLookup);

  protected readonly statuses = statusOptions('title');
  protected readonly buckets = OVERDUE_BUCKETS;
  protected readonly titleLabel = titleLabel;
  protected readonly canViewDebtors = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));

  /** Filtros vindos de outras telas (`?credor=&carteira=&devedor=&situacao=&atraso=`), lidos antes da primeira consulta. */
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>(this.param('credor') ? [this.param('credor')] : []),
    portfolio: this.fb.control<string[]>(this.param('carteira') ? [this.param('carteira')] : []),
    debtorId: [this.param('devedor')],
    status: [statusOptions('title').some((s) => s.value === this.param('situacao')) ? this.param('situacao') : ''],
    bucket: [OVERDUE_BUCKETS.some((b) => b.value === this.param('atraso')) ? this.param('atraso') : ''],
    dueFrom: this.fb.control<Date | null>(null),
    dueTo: this.fb.control<Date | null>(null),
  });
  protected readonly portfolioSource = this.portfolioLookup.source(() => this.filters.controls.creditor.value[0] ?? null);

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map(() => toTitleFilter(this.filters.getRawValue())),
    ),
    { initialValue: toTitleFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page));

  protected readonly debtors = nameMap(inject(DebtorNames), () =>
    this.canViewDebtors() ? [...this.query.rows().map((t) => t.debtorId), this.filters.controls.debtorId.value] : [],
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.query.rows().map((t) => t.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'title', header: 'Título' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'creditor', header: 'Credor' },
    { key: 'portfolio', header: 'Carteira' },
    { key: 'dueDate', header: 'Vencimento' },
    { key: 'overdue', header: 'Atraso' },
    { key: 'openBalance', header: 'Saldo em aberto', align: 'end' },
    { key: 'status', header: 'Situação' },
  ];

  protected clearDebtor(): void {
    this.filters.controls.debtorId.setValue('');
  }

  private param(name: string): string {
    return this.params.get(name) ?? '';
  }

  protected clear(): void {
    this.filters.reset();
  }
}
