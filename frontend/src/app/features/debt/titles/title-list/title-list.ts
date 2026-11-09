import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
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
import { syncFormWithUrl, urlParam } from '../../../../shared/data/url-state';
import { toDateStruct } from '../../../../shared/models/dates';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { CreditorLookup, CreditorNames, PortfolioLookup, PortfolioNames } from '../../../organization/data/organization.lookups';
import { OverdueBadge } from '../../components/overdue-badge';
import { TitlesApi } from '../../data/debt.api';
import { OVERDUE_BUCKETS, titleLabel } from '../../data/debt.labels';
import { DebtorNames } from '../../data/debt.lookups';
import { TitleFilter } from '../../data/debt.models';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { NgbTooltip, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';

/** Converte o formulário de filtros no `TitleSearch` do backend. */
export function toTitleFilter(v: {
  creditor: string[];
  portfolio: string[];
  debtorId: string;
  /** Lote de importação (do lote para os títulos importados); opcional para os chamadores antigos. */
  importBatchId?: string;
  status: string;
  bucket: string;
  /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
  dueFrom: string | null;
  dueTo: string | null;
}): TitleFilter {
  const bucket = OVERDUE_BUCKETS.find((b) => b.value === v.bucket);
  return {
    creditorId: v.creditor[0] ?? null,
    portfolioId: v.portfolio[0] ?? null,
    debtorId: v.debtorId || null,
    status: v.status || null,
    minOverdueDays: bucket?.min ?? null,
    maxOverdueDays: bucket?.max ?? null,
    dueFrom: v.dueFrom || null,
    dueTo: v.dueTo || null,
    importBatchId: v.importBatchId || null,
  };
}

/** Consulta de títulos por credor, carteira, situação, faixa de atraso e vencimento. */
@Component({
  selector: 'app-title-list',
  imports: [ReactiveFormsModule, RouterLink, DataTable, CellDef, EntityPicker, PageHeader, StatusChip, Amount, AppDatePipe, OverdueBadge, Icon, NgbTooltip, ...FORM_FIELD, NgbInputDatepicker],
  templateUrl: './title-list.html',
  styles: `
    .title-list__flag {
      margin-left: 6px;
    }
    .title-list__debtor {
      align-self: center;
      gap: 6px;
      --bs-btn-close-width: 0.625em;
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

  /** Filtros na URL (`?credor=&carteira=&devedor=&lote=&situacao=&atraso=&venc-de=&venc-ate=`): vindos de outras telas e preservados ao voltar. */
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>([]),
      portfolio: this.fb.control<string[]>([]),
      debtorId: [''],
      importBatchId: [''],
      status: [''],
      bucket: [''],
      dueFrom: this.fb.control<string | null>(null),
      dueTo: this.fb.control<string | null>(null),
    }),
    {
      creditor: urlParam.ids('credor'),
      portfolio: urlParam.ids('carteira'),
      debtorId: urlParam.id('devedor'),
      importBatchId: urlParam.id('lote'),
      status: urlParam.choice('situacao', statusOptions('title').map((s) => s.value)),
      bucket: urlParam.choice('atraso', OVERDUE_BUCKETS.map((b) => b.value)),
      dueFrom: urlParam.date('venc-de'),
      dueTo: urlParam.date('venc-ate'),
    },
  );
  private readonly dueValues = toSignal(this.filters.valueChanges.pipe(startWith(null)), { initialValue: null });
  /** O intervalo não se inverte: "de" ≤ "até" (limites dos calendários). */
  protected readonly dueFromLimit = computed(() => {
    this.dueValues();
    return toDateStruct(this.filters.controls.dueFrom.value || '1900-01-01');
  });
  protected readonly dueToLimit = computed(() => {
    this.dueValues();
    return toDateStruct(this.filters.controls.dueTo.value || '2100-12-31');
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
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page), {
    url: true,
    sortFields: { dueDate: 'dueDate', overdue: 'daysOverdue', openBalance: 'openBalance' },
  });

  protected readonly debtors = nameMap(inject(DebtorNames), () =>
    this.canViewDebtors() ? [...this.query.rows().map((t) => t.debtorId), this.filters.controls.debtorId.value] : [],
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.query.rows().map((t) => t.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'title', header: 'Título', mobile: 'title' },
    { key: 'debtor', header: 'Devedor', mobile: 'meta' },
    { key: 'creditor', header: 'Credor', mobile: 'hidden' },
    { key: 'portfolio', header: 'Carteira', mobile: 'hidden' },
    { key: 'dueDate', header: 'Vencimento', sortable: true, mobile: 'hidden' },
    { key: 'overdue', header: 'Atraso', sortable: true, mobile: 'meta' },
    { key: 'openBalance', header: 'Saldo em aberto', align: 'end', sortable: true, mobile: 'meta' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
  ];

  protected clearDebtor(): void {
    this.filters.controls.debtorId.setValue('');
  }

  protected clearBatch(): void {
    this.filters.controls.importBatchId.setValue('');
  }

  protected clear(): void {
    this.filters.reset();
  }
}
