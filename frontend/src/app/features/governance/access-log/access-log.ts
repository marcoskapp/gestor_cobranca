import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { endOfDayInstant, fromLocalDate, startOfDayInstant } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { GovernanceApi } from '../data/governance.api';
import { ACCESS_EVENT_LABELS, AccessLogEntry, AccessLogFilter } from '../data/governance.models';
import { AccessDetailDialog } from './access-detail-dialog';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DialogService } from '../../../shared/dialog/dialog.service';

/** Consulta do log de acesso (autenticação, sessões, negações e acessos a dados sensíveis). */
@Component({
  selector: 'app-access-log',
  imports: [NgbTooltip, ...FORM_FIELD, Icon,
    ReactiveFormsModule,
    NgbInputDatepicker,
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
  private readonly dialog = inject(DialogService);
  protected readonly users = inject(UserLookup);
  private readonly auth = inject(AuthService);
  /** O filtro por usuário consulta o cadastro de usuários: só aparece para quem pode consultá-lo. */
  protected readonly canFilterByActor = computed(() => this.auth.hasPermission(Permission.USER_VIEW));
  protected readonly eventTypes = Object.entries(ACCESS_EVENT_LABELS).map(([value, label]) => ({ value, label }));

  private readonly fb = inject(FormBuilder);
  /**
   * Filtros na URL (`?de=&ate=&usuario=&evento=&resultado=`), preservados ao voltar; ao abrir, os da URL já vêm
   * aplicados. O IP fica fora da URL (dado pessoal).
   */
  protected readonly filters = syncFormWithUrl(
    this.fb.group(
      {
        from: this.fb.control<string | null>(null),
        to: this.fb.control<string | null>(null),
        actor: this.fb.nonNullable.control<string[]>([]),
        eventType: this.fb.nonNullable.control(''),
        success: this.fb.nonNullable.control<'' | 'true' | 'false'>(''),
        clientIp: this.fb.nonNullable.control(''),
      },
      { validators: AppValidators.dateRange('from', 'to') },
    ),
    {
      from: urlParam.date('de'),
      to: urlParam.date('ate'),
      actor: urlParam.ids('usuario'),
      eventType: urlParam.choice('evento', this.eventTypes.map((e) => e.value)),
      success: urlParam.choice('resultado', ['true', 'false'] as const),
    },
  );

  private readonly filter = signal<AccessLogFilter>(this.filters.valid ? this.currentFilter() : {});
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.accessLog(filter, page), { initialSize: 50, url: true });

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
    this.dialog.open(AccessDetailDialog, { data: entry, size: 'lg' });
  }

  protected apply(): void {
    if (this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    this.filter.set(this.currentFilter());
  }

  private currentFilter(): AccessLogFilter {
    const v = this.filters.getRawValue();
    return {
      from: startOfDayInstant(fromLocalDate(v.from)),
      to: endOfDayInstant(fromLocalDate(v.to)),
      actorId: v.actor[0] ?? null,
      eventType: v.eventType || null,
      success: v.success === '' ? null : v.success === 'true',
      clientIp: v.clientIp.trim() || null,
    };
  }

  protected clear(): void {
    this.filters.reset();
    this.apply();
  }
}
