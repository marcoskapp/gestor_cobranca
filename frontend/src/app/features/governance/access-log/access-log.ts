import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { endOfDayInstant, startOfDayInstant } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { GovernanceApi } from '../data/governance.api';
import { ACCESS_EVENT_LABELS, AccessLogEntry, AccessLogFilter } from '../data/governance.models';
import { AccessDetailDialog } from './access-detail-dialog';

/** Consulta do log de acesso (autenticação, sessões, negações e acessos a dados sensíveis). */
@Component({
  selector: 'app-access-log',
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    AppDatePipe,
  ],
  templateUrl: './access-log.html',
})
export class AccessLog {
  private readonly api = inject(GovernanceApi);
  private readonly dialog = inject(MatDialog);
  protected readonly users = inject(UserLookup);
  protected readonly eventTypes = Object.entries(ACCESS_EVENT_LABELS).map(([value, label]) => ({ value, label }));

  private readonly fb = inject(FormBuilder);
  protected readonly filters = this.fb.group(
    {
      from: this.fb.control<Date | null>(null),
      to: this.fb.control<Date | null>(null),
      actor: this.fb.nonNullable.control<string[]>([]),
      eventType: this.fb.nonNullable.control(''),
      success: this.fb.nonNullable.control<'' | 'true' | 'false'>(''),
      clientIp: this.fb.nonNullable.control(''),
    },
    { validators: AppValidators.dateRange('from', 'to') },
  );

  private readonly filter = signal<AccessLogFilter>({});
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.accessLog(filter, page), 50);

  protected readonly columns: TableColumn[] = [
    { key: 'occurredAt', header: 'Data/hora' },
    { key: 'eventType', header: 'Evento' },
    { key: 'success', header: 'Resultado' },
    { key: 'actor', header: 'Ator' },
    { key: 'clientIp', header: 'IP' },
    { key: 'route', header: 'Rota' },
    { key: 'actions', header: 'Detalhes', actions: true },
  ];

  protected eventLabel(type: string): string {
    return ACCESS_EVENT_LABELS[type] ?? type;
  }

  protected actor(entry: AccessLogEntry): string {
    return entry.actorName ?? entry.principal ?? (entry.actorType === 'ANONYMOUS' ? 'Anônimo' : (entry.actorType ?? '—'));
  }

  protected details(entry: AccessLogEntry): void {
    this.dialog.open(AccessDetailDialog, { data: entry, width: '720px' });
  }

  protected apply(): void {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const v = this.filters.getRawValue();
    this.filter.set({
      from: startOfDayInstant(v.from),
      to: endOfDayInstant(v.to),
      actorId: v.actor[0] ?? null,
      eventType: v.eventType || null,
      success: v.success === '' ? null : v.success === 'true',
      clientIp: v.clientIp.trim() || null,
    });
  }

  protected clear(): void {
    this.filters.reset();
    this.apply();
  }
}
