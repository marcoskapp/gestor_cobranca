import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Observable, finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct } from '../../../shared/models/dates';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { TasksApi } from '../data/collection.api';
import { TASK_TYPE_LABELS } from '../data/collection.labels';
import { TaskType, TaskView } from '../data/collection.models';
import { formatLocalDateTime, isPastLocal, toLocalDateTime, zonedNow } from '../data/collection.rules';

export interface ScheduleDialogData {
  caseId: string;
  /** Reagendamento de uma tarefa existente (somente data e hora). */
  task?: TaskView;
  /** Permite escolher outro responsável (`CASE_ASSIGN`). */
  canChooseAssignee?: boolean;
}

/** Agendar retorno/acompanhamento ou reagendar, no fuso de operação (horário de Brasília). */
@Component({
  selector: 'app-schedule-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, EntityPicker, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.task ? 'Reagendar' : 'Agendar retorno' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger" role="alert">{{ error() }}</div>
        }
        @if (data.task) {
          <p class="app-dialog-message">Horário atual: <strong>{{ current }}</strong></p>
        }
        <div class="row g-3">
          @if (!data.task) {
            <app-field class="col-12" label="Tipo">
              <select appInput formControlName="type" ngbAutofocus>
                @for (t of types; track t.value) {
                  <option [ngValue]="t.value">{{ t.label }}</option>
                }
              </select>
            </app-field>
          }
          <app-field class="col-sm-6" label="Data">
            <div class="input-group">
              <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="date" placeholder="dd/mm/aaaa" [minDate]="today" [attr.ngbAutofocus]="data.task ? '' : null" required />
              <button type="button" class="btn btn-outline-secondary" (click)="picker.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="col-sm-6" label="Hora" hint="Horário de Brasília">
            <input appInput type="time" formControlName="time" required />
          </app-field>
          @if (!data.task) {
            @if (data.canChooseAssignee) {
              <div class="col-12">
                <app-entity-picker [control]="form.controls.assignee" [source]="users" label="Responsável" hint="Vazio = operador do caso (ou a fila)." [single]="true" />
              </div>
            }
            <app-field class="col-12" label="Observação" hint="Sem dados pessoais além do necessário para o retorno.">
              <textarea appInput formControlName="note" rows="2" maxlength="1000"></textarea>
            </app-field>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="saving()">
          @if (saving()) {
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
          }
          {{ data.task ? 'Reagendar' : 'Agendar' }}
        </button>
      </div>
    </form>
  `,
})
export class ScheduleDialog {
  protected readonly data = inject<ScheduleDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TaskView>>(DialogRef);
  private readonly api = inject(TasksApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);

  protected readonly types = Object.entries(TASK_TYPE_LABELS).map(([value, label]) => ({ value: value as TaskType, label }));
  protected readonly today = toDateStruct(zonedNow().date);
  protected readonly current = formatLocalDateTime(this.data.task?.dueAtLocal);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<TaskType>(this.data.task?.type ?? 'CALLBACK'),
    date: this.fb.control<string | null>(this.data.task ? this.data.task.dueAtLocal.slice(0, 10) : null, [AppValidators.notBlank]),
    time: [this.data.task?.dueAtLocal.slice(11, 16) ?? '', [AppValidators.notBlank]],
    assignee: this.fb.control<string[]>([]),
    note: ['', [Validators.maxLength(1000)]],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const dueAt = toLocalDateTime(v.date, v.time);
    if (this.form.invalid || !dueAt) {
      this.form.markAllAsTouched();
      return;
    }
    if (isPastLocal(dueAt, zonedNow())) {
      this.error.set('Escolha data e hora futuras.');
      return;
    }
    const call: Observable<TaskView> = this.data.task
      ? this.api.reschedule(this.data.task.id, dueAt)
      : this.api.schedule(this.data.caseId, { type: v.type, dueAt, assigneeUserId: v.assignee[0] ?? null, note: v.note.trim() || null });
    this.saving.set(true);
    this.error.set(null);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (task) => {
        this.notifications.success(`${this.data.task ? 'Reagendado' : 'Agendado'} para ${formatLocalDateTime(task.dueAtLocal)}.`);
        this.ref.close(task);
      },
    });
  }
}
