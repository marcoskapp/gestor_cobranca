import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { PageRequest, PageResponse, emptyPage } from '../../../../shared/models/page';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { DebtorLookup } from '../../../debt/data/debt.lookups';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { RestrictionsApi } from '../../data/compliance.api';
import { CHANNEL_LABELS, RESTRICTION_SOURCE_LABELS, RESTRICTION_TYPE_LABELS, restrictionScope } from '../../data/compliance.labels';
import { RestrictionFilter, RestrictionView } from '../../data/compliance.models';
import { EvidenceButtons } from '../evidence/evidence-buttons';
import { RestrictionActions } from '../restriction-actions';

/** Filtro da tela; `blocked` = o ator não vê todos os credores e não escolheu um devedor. */
type ListFilter = RestrictionFilter & { blocked: boolean };

@Component({
  selector: 'app-restriction-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
    EvidenceButtons,
  ],
  templateUrl: './restriction-list.html',
})
export class RestrictionList {
  private readonly api = inject(RestrictionsApi);
  private readonly actions = inject(RestrictionActions);
  private readonly auth = inject(AuthService);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly typeLabels = RESTRICTION_TYPE_LABELS;
  protected readonly sourceLabels = RESTRICTION_SOURCE_LABELS;
  protected readonly types = Object.entries(RESTRICTION_TYPE_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly statuses = statusOptions('restriction');
  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  private readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    debtor: this.fb.control<string[]>([]),
    creditor: this.fb.control<string[]>([]),
    type: [''],
    status: ['ACTIVE'],
    channel: [''],
  });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map((): ListFilter => {
        const v = this.filters.getRawValue();
        const debtorId = v.debtor[0] ?? null;
        return {
          debtorId,
          creditorId: v.creditor[0] ?? null,
          type: v.type || null,
          status: v.status || null,
          channel: v.channel || null,
          blocked: !debtorId && !this.allCreditors(),
        };
      }),
    ),
    { initialValue: { blocked: true } as ListFilter },
  );
  protected readonly blocked = computed(() => this.filter().blocked);
  protected readonly query = pagedQuery(this.filter, (f, page) => this.load(f, page));

  protected readonly debtors = nameMap(this.debtorSource, () => this.query.rows().map((r) => r.debtorId));
  protected readonly creditors = nameMap(this.creditorSource, () => this.query.rows().map((r) => r.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor' },
    { key: 'type', header: 'Tipo' },
    { key: 'scope', header: 'Alcance' },
    { key: 'reason', header: 'Motivo' },
    { key: 'period', header: 'Vigência' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected scope(r: RestrictionView): string {
    return restrictionScope(r, (id) => this.creditors.name(id));
  }

  protected create(): void {
    const debtorId = this.filters.controls.debtor.value[0] ?? null;
    this.actions
      .create({ debtorId, debtorName: debtorId ? this.debtors.name(debtorId) : undefined })
      .subscribe(() => this.query.reload());
  }

  protected revoke(r: RestrictionView): void {
    this.actions.revoke(r, this.scope(r)).subscribe(() => this.query.reload());
  }

  private load(f: ListFilter, page: PageRequest): Observable<PageResponse<RestrictionView>> {
    if (f.blocked) {
      return of(emptyPage<RestrictionView>(page.size));
    }
    const { blocked: _, ...filter } = f;
    return this.api.search(filter, page);
  }
}
