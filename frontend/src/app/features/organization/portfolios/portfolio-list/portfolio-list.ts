import { Component, OnInit, computed, inject, input } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { PORTFOLIO_TRANSITIONS, statusOptions } from '../../../../shared/status/status';
import { PortfoliosApi } from '../../data/organization.api';
import { CreditorLookup } from '../../data/organization.lookups';
import { PortfolioView } from '../../data/organization.models';
import { PortfolioForm, PortfolioFormData } from '../portfolio-form/portfolio-form';
import { PortfolioStatusDialog } from '../portfolio-status/portfolio-status';
import { DialogService } from '../../../../shared/dialog/dialog.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

interface PortfolioFilter {
  creditorId: string | null;
  status: string | null;
  q: string | null;
}

@Component({
  selector: 'app-portfolio-list',
  imports: [NgbTooltip, ...FORM_FIELD, Icon,
    ReactiveFormsModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
  ],
  templateUrl: './portfolio-list.html',
})
export class PortfolioList implements OnInit {
  /** `?credor=` vindo da tela do credor. */
  readonly credor = input<string>();

  private readonly api = inject(PortfoliosApi);
  private readonly dialog = inject(DialogService);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PORTFOLIO_MANAGE));
  protected readonly statuses = statusOptions('portfolio');

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    status: [''],
    q: [''],
  });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map((): PortfolioFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, status: v.status || null, q: v.q.trim() || null };
      }),
    ),
    { initialValue: { creditorId: null, status: null, q: null } as PortfolioFilter },
  );
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page));

  private readonly creditorLabels = valueOr(
    rxResource({
      params: () => [...new Set(this.query.rows().map((p) => p.creditorId))],
      stream: ({ params }) => this.creditorSource.resolve(params),
    }),
    [],
  );
  private readonly creditorNames = computed(() => new Map(this.creditorLabels().map((o) => [o.value, o.label])));

  protected readonly columns: TableColumn[] = [
    { key: 'code', header: 'Código' },
    { key: 'name', header: 'Carteira' },
    { key: 'creditor', header: 'Credor' },
    { key: 'receivedOn', header: 'Recebida em' },
    { key: 'validUntil', header: 'Válida até' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  ngOnInit(): void {
    const initial = this.credor();
    if (initial) {
      this.filters.controls.creditor.setValue([initial]);
    }
  }

  protected creditorName(id: string): string {
    return this.creditorNames().get(id) ?? '…';
  }

  protected canChangeStatus(p: PortfolioView): boolean {
    return (PORTFOLIO_TRANSITIONS[p.status] ?? []).length > 0;
  }

  protected create(): void {
    this.openForm({ portfolio: null, creditorId: this.filters.controls.creditor.value[0] ?? null });
  }

  protected edit(portfolio: PortfolioView): void {
    this.openForm({ portfolio });
  }

  protected changeStatus(portfolio: PortfolioView): void {
    this.dialog
      .open<PortfolioStatusDialog, PortfolioView, PortfolioView>(PortfolioStatusDialog, { data: portfolio })
      .afterClosed()
      .subscribe((updated) => updated && this.query.reload());
  }

  private openForm(data: PortfolioFormData): void {
    this.dialog
      .open<PortfolioForm, PortfolioFormData, PortfolioView>(PortfolioForm, { data, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => saved && this.query.reload());
  }
}
