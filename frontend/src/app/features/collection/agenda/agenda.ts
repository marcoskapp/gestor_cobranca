import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, debounceTime, forkJoin, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { NameSource, nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { Option } from '../../../shared/models/lookup';
import { UserLookup } from '../../admin/data/user.lookup';
import { DebtorsApi } from '../../debt/data/debt.api';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { CasesApi, TasksApi } from '../data/collection.api';
import { TASK_TYPE_LABELS } from '../data/collection.labels';
import { AgendaFilter, TaskView } from '../data/collection.models';
import { addDays, formatLocalDateTime, taskLate, zonedNow } from '../data/collection.rules';
import { ScheduleDialog, ScheduleDialogData } from '../station/schedule-dialog';

export type AgendaPeriod = 'today' | 'week' | 'late' | 'custom';

/** Filtro da agenda a partir do período rápido (limite do servidor: 92 dias). */
export function agendaFilter(
  v: { period: AgendaPeriod; from: Date | null; to: Date | null; status: string; user: string[] },
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
      return { userId, from: toLocalDate(v.from) ?? today, to: toLocalDate(v.to) ?? toLocalDate(v.from) ?? today, status: v.status || null };
  }
}

/** Devedor de cada caso da agenda (a tarefa só traz o caso). */
class CaseDebtorNames implements NameSource {
  constructor(
    private readonly cases: CasesApi,
    private readonly debtors: DebtorsApi,
  ) {}

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length
      ? forkJoin(ids.map((id) => this.cases.get(id).pipe(switchMap((c) => this.debtors.get(c.debtorId)), map((d): Option => ({ value: id, label: d.name })))))
      : of([]);
  }
}

/** Agenda de retornos do operador (ou de outro operador, com `CASE_ASSIGN`), no horário de Brasília. */
@Component({
  selector: 'app-agenda',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    StatusChip,
  ],
  template: `
    <app-page-header title="Agenda de retornos" subtitle="Retornos e acompanhamentos combinados, no horário de Brasília. Retorno atrasado continua com você e volta como próxima ação.">
      <a mat-flat-button routerLink="/cobranca/fila">
        <mat-icon aria-hidden="true">support_agent</mat-icon>
        Estação de cobrança
      </a>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar agenda">
      <mat-button-toggle-group formControlName="period" aria-label="Período" class="ag-period">
        <mat-button-toggle value="today">Hoje</mat-button-toggle>
        <mat-button-toggle value="week">7 dias</mat-button-toggle>
        <mat-button-toggle value="late">Atrasados</mat-button-toggle>
        <mat-button-toggle value="custom">Período</mat-button-toggle>
      </mat-button-toggle-group>
      @if (period() === 'custom') {
        <mat-form-field class="app-compact">
          <mat-label>De</mat-label>
          <input matInput [matDatepicker]="fromPicker" formControlName="from" />
          <mat-datepicker-toggle matIconSuffix [for]="fromPicker" />
          <mat-datepicker #fromPicker />
        </mat-form-field>
        <mat-form-field class="app-compact">
          <mat-label>Até</mat-label>
          <input matInput [matDatepicker]="toPicker" formControlName="to" />
          <mat-datepicker-toggle matIconSuffix [for]="toPicker" />
          <mat-datepicker #toPicker />
          <mat-hint>Até 92 dias</mat-hint>
        </mat-form-field>
      }
      @if (period() !== 'late') {
        <mat-form-field class="app-compact">
          <mat-label>Situação</mat-label>
          <mat-select formControlName="status">
            <mat-option value="">Em aberto</mat-option>
            <mat-option value="MISSED">Atrasados</mat-option>
            <mat-option value="DONE">Concluídos</mat-option>
            <mat-option value="CANCELLED">Cancelados</mat-option>
          </mat-select>
        </mat-form-field>
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
      emptyIcon="event_available"
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
        <a [routerLink]="['/cobranca/casos', t.caseId]">{{ debtors.name(t.caseId) }}</a>
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
          <button mat-icon-button type="button" (click)="complete(t)" aria-label="Concluir" matTooltip="Concluir">
            <mat-icon aria-hidden="true">check</mat-icon>
          </button>
          <button mat-icon-button type="button" (click)="reschedule(t)" aria-label="Reagendar" matTooltip="Reagendar">
            <mat-icon aria-hidden="true">update</mat-icon>
          </button>
          <button mat-icon-button type="button" (click)="cancel(t)" aria-label="Cancelar" matTooltip="Cancelar">
            <mat-icon aria-hidden="true">event_busy</mat-icon>
          </button>
        }
        <a mat-icon-button [routerLink]="['/cobranca/casos', t.caseId]" aria-label="Abrir caso" matTooltip="Abrir caso">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .ag-period {
      align-self: center;
    }
    .ag-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
  `,
})
export class AgendaPage {
  private readonly api = inject(TasksApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
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
    from: this.fb.control<Date | null>(fromLocalDate(this.today)),
    to: this.fb.control<Date | null>(fromLocalDate(addDays(this.today, 6))),
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

  protected readonly debtors = nameMap(new CaseDebtorNames(inject(CasesApi), inject(DebtorsApi)), () => this.query.rows().map((t) => t.caseId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Horário' },
    { key: 'type', header: 'Agendamento' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

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
      .open<ScheduleDialog, ScheduleDialogData, TaskView>(ScheduleDialog, { data: { caseId: task.caseId, task }, width: '480px' })
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
          { label: 'Devedor', value: this.debtors.name(task.caseId) },
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
