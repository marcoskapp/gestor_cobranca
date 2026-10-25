import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { UserLookup } from '../../admin/data/user.lookup';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { TasksApi } from '../data/collection.api';
import { TASK_TYPE_LABELS } from '../data/collection.labels';
import { AgendaFilter, TaskView } from '../data/collection.models';
import { addDays, formatLocalDateTime, taskLate, zonedNow } from '../data/collection.rules';
import { ScheduleDialog, ScheduleDialogData } from '../station/schedule-dialog';
import { NgbTooltip, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DialogService } from '../../../shared/dialog/dialog.service';

export type AgendaPeriod = 'today' | 'week' | 'late' | 'custom';

/** Filtro da agenda a partir do período rápido (limite do servidor: 92 dias). */
export function agendaFilter(
  v: { period: AgendaPeriod; from: string | null; to: string | null; status: string; user: string[] },
  today: string,
): AgendaFilter {
  const userId = v.user[0] ?? null;
  switch (v.period) {
    case 'today':
      return { userId, from: today, to: today, status: v.status || null };
    case 'week':
      return { userId, from: today, to: addDays(today, 6), status: v.status || null };
    case 'late':
      return { userId, from: addDays(today, -30), to: today, status: 'MISSED' };
    default:
      return { userId, from: v.from || today, to: v.to || v.from || today, status: v.status || null };
  }
}

/** Agenda de retornos do operador (ou de outro operador, com `CASE_ASSIGN`), no horário de Brasília. */
@Component({
  selector: 'app-agenda',
  imports: [ReactiveFormsModule, RouterLink, DataTable, CellDef, EntityPicker, PageHeader, StatusChip, Icon, NgbTooltip, NgbInputDatepicker, ...FORM_FIELD],
  template: `
    <app-page-header title="Agenda de retornos" subtitle="Retornos e acompanhamentos combinados, no horário de Brasília. Retorno atrasado continua com você e volta como próxima ação.">
      <a class="btn btn-primary" routerLink="/cobranca/fila">
        <app-icon name="headset" />
        Estação de cobrança
      </a>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar agenda">
      <div class="btn-group ag-period" role="group" aria-label="Período">
        <input type="radio" class="btn-check" id="agenda-period-today" value="today" formControlName="period" />
        <label class="btn btn-outline-primary" for="agenda-period-today">Hoje</label>
        <input type="radio" class="btn-check" id="agenda-period-week" value="week" formControlName="period" />
        <label class="btn btn-outline-primary" for="agenda-period-week">7 dias</label>
        <input type="radio" class="btn-check" id="agenda-period-late" value="late" formControlName="period" />
        <label class="btn btn-outline-primary" for="agenda-period-late">Atrasados</label>
        <input type="radio" class="btn-check" id="agenda-period-custom" value="custom" formControlName="period" />
        <label class="btn btn-outline-primary" for="agenda-period-custom">Período</label>
      </div>
      @if (period() === 'custom') {
        <app-field label="De">
          <div class="input-group">
            <input appInput ngbDatepicker #fromPicker="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="from" />
            <button type="button" class="btn btn-outline-secondary" (click)="fromPicker.toggle()" aria-label="Abrir calendário">
              <app-icon name="calendar-event" />
            </button>
          </div>
        </app-field>
        <app-field label="Até" hint="Até 92 dias">
          <div class="input-group">
            <input appInput ngbDatepicker #toPicker="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="to" />
            <button type="button" class="btn btn-outline-secondary" (click)="toPicker.toggle()" aria-label="Abrir calendário">
              <app-icon name="calendar-event" />
            </button>
          </div>
        </app-field>
      }
      @if (period() !== 'late') {
        <app-field label="Situação">
          <select appInput formControlName="status">
            <option value="">Em aberto</option>
            <option value="MISSED">Atrasados</option>
            <option value="DONE">Concluídos</option>
            <option value="CANCELLED">Cancelados</option>
          </select>
        </app-field>
      }
      @if (canSeeOthers()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.user" [source]="users" label="Operador" [single]="true" hint="Vazio = a sua agenda." />
        </div>
      }
    </form>

    <app-data-table
      caption="Agenda de retornos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="calendar-check"
      emptyMessage="Nenhum retorno neste período."
      emptyHint="Retornos são agendados ao registrar uma interação com tabulação de retorno ou pelo botão Agendar retorno."
    >
      <ng-template appCell="due" [appCellOf]="query.rows()" let-t>
        <span class="app-numeric" [class.ag-late]="late(t)">{{ formatLocal(t.dueAtLocal) }}</span>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-t>
        {{ types[t.type] }}
        @if (t.note) {
          <div class="app-muted">{{ t.note }}</div>
        }
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-t>
        <a [routerLink]="['/cobranca/casos', t.caseId]">{{ debtorText(t) }}</a>
        <div class="app-muted">{{ creditors.name(t.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-t>
        <app-status-chip domain="task" [status]="late(t) ? 'MISSED' : t.status" />
        @if (t.completedOnTime === false) {
          <div class="app-muted">concluído com atraso</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-t>
        @if (canManage() && (t.status === 'PENDING' || t.status === 'MISSED')) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="complete(t)" aria-label="Concluir" ngbTooltip="Concluir">
            <app-icon name="check-lg" />
          </button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="reschedule(t)" aria-label="Reagendar" ngbTooltip="Reagendar">
            <app-icon name="arrow-clockwise" />
          </button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="cancel(t)" aria-label="Cancelar" ngbTooltip="Cancelar">
            <app-icon name="calendar-x" />
          </button>
        }
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/cobranca/casos', t.caseId]" aria-label="Abrir caso" ngbTooltip="Abrir caso">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .ag-period {
      align-self: flex-end;
    }
    .ag-late {
      font-weight: 500;
      color: var(--bs-danger-text-emphasis);
    }
  `,
})
export class AgendaPage {
  private readonly api = inject(TasksApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);

  protected readonly types = TASK_TYPE_LABELS;
  protected readonly formatLocal = formatLocalDateTime;
  protected readonly canSeeOthers = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TASK_MANAGE));
  private readonly today = zonedNow().date;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    period: this.fb.control<AgendaPeriod>('today'),
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    from: this.fb.control<string | null>(this.today),
    to: this.fb.control<string | null>(addDays(this.today, 6)),
    status: [''],
    user: this.fb.control<string[]>([]),
  });
  protected readonly period = toSignal(this.filters.controls.period.valueChanges, { initialValue: 'today' as AgendaPeriod });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(200),
      map(() => agendaFilter(this.filters.getRawValue(), this.today)),
    ),
    { initialValue: agendaFilter(this.filters.getRawValue(), this.today), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.agenda(f, page));

  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Horário' },
    { key: 'type', header: 'Agendamento' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  /** O nome vem na própria tarefa (só para quem pode ver devedores). */
  protected debtorText(task: TaskView): string {
    return task.debtorName ?? 'Abrir caso';
  }

  protected late(task: TaskView): boolean {
    return taskLate(task, zonedNow());
  }

  protected complete(task: TaskView): void {
    this.api.complete(task.id, null).subscribe(() => {
      this.notifications.success('Retorno concluído.');
      this.query.reload();
    });
  }

  protected reschedule(task: TaskView): void {
    this.dialog
      .open<ScheduleDialog, ScheduleDialogData, TaskView>(ScheduleDialog, { data: { caseId: task.caseId, task } })
      .afterClosed()
      .subscribe((saved) => saved && this.query.reload());
  }

  protected cancel(task: TaskView): void {
    this.confirm
      .confirm({
        title: 'Cancelar agendamento',
        message: 'O agendamento sai da agenda e da próxima ação do caso.',
        confirmLabel: 'Cancelar agendamento',
        destructive: true,
        reason: 'required',
        details: [
          { label: 'Horário', value: formatLocalDateTime(task.dueAtLocal) },
          { label: 'Devedor', value: this.debtorText(task) },
        ],
      })
      .subscribe((reason) =>
        this.api.cancel(task.id, reason).subscribe(() => {
          this.notifications.success('Agendamento cancelado.');
          this.query.reload();
        }),
      );
  }
}
