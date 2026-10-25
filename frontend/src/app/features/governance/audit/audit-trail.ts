import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { endOfDayInstant, fromLocalDate, startOfDayInstant } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { GovernanceApi } from '../data/governance.api';
import { AUDIT_ENTITY_LABELS, AuditFilter, AuditLogEntry, IntegrityReport } from '../data/governance.models';
import { AuditDetailDialog } from './audit-detail-dialog';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Trilha de auditoria append-only com verificação do encadeamento de hash. */
@Component({
  selector: 'app-audit-trail',
  imports: [NgbTooltip, ...FORM_FIELD, Icon,
    ReactiveFormsModule,
    NgbInputDatepicker,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    AppDatePipe,
  ],
  templateUrl: './audit-trail.html',
})
export class AuditTrail {
  private readonly api = inject(GovernanceApi);
  private readonly dialog = inject(DialogService);
  protected readonly users = inject(UserLookup);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly entityTypes = Object.entries(AUDIT_ENTITY_LABELS).map(([value, label]) => ({ value, label }));

  private readonly fb = inject(FormBuilder);
  protected readonly filters = this.fb.group(
    {
      from: this.fb.control<string | null>(null),
      to: this.fb.control<string | null>(null),
      actor: this.fb.nonNullable.control<string[]>([]),
      creditor: this.fb.nonNullable.control<string[]>([]),
      entityType: this.fb.nonNullable.control(''),
      entityId: this.fb.nonNullable.control('', [Validators.pattern(UUID)]),
      action: this.fb.nonNullable.control('', [Validators.maxLength(100)]),
    },
    { validators: AppValidators.dateRange('from', 'to') },
  );

  private readonly filter = signal<AuditFilter>({});
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.audit(filter, page), 50);
  protected readonly checking = signal(false);
  protected readonly integrity = signal<IntegrityReport | null>(null);
  protected readonly rowKey = (entry: AuditLogEntry): unknown => entry.seq;

  protected readonly columns: TableColumn[] = [
    { key: 'seq', header: 'Nº', align: 'end' },
    { key: 'occurredAt', header: 'Data/hora' },
    { key: 'action', header: 'Ação' },
    { key: 'entity', header: 'Entidade' },
    { key: 'actor', header: 'Autor' },
    { key: 'reason', header: 'Motivo' },
    { key: 'sealed', header: 'Selado', align: 'center' },
    { key: 'actions', header: 'Detalhes', actions: true },
  ];

  protected entityLabel(type: string): string {
    return AUDIT_ENTITY_LABELS[type] ?? type;
  }

  protected details(entry: AuditLogEntry): void {
    this.dialog.open(AuditDetailDialog, { data: entry, size: 'lg' });
  }

  protected apply(): void {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const v = this.filters.getRawValue();
    this.filter.set({
      from: startOfDayInstant(fromLocalDate(v.from)),
      to: endOfDayInstant(fromLocalDate(v.to)),
      actorId: v.actor[0] ?? null,
      creditorId: v.creditor[0] ?? null,
      entityType: v.entityType || null,
      entityId: v.entityId.trim() || null,
      action: v.action.trim() || null,
    });
  }

  protected clear(): void {
    this.filters.reset();
    this.apply();
  }

  protected verify(): void {
    this.checking.set(true);
    this.api
      .verifyIntegrity()
      .pipe(finalize(() => this.checking.set(false)))
      .subscribe((report) => this.integrity.set(report));
  }
}
