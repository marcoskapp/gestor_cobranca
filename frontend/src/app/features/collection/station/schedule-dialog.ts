import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Observable, finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { fromLocalDate } from '../../../shared/models/dates';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.task ? 'Reagendar' : 'Agendar retorno' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert">{{ error() }}</p>
        }
        @if (data.task) {
          <p class="app-dialog-message">Horário atual: <strong>{{ current }}</strong></p>
        }
        <div class="app-form-grid">
          @if (!data.task) {
            <mat-form-field>
              <mat-label>Tipo</mat-label>
              <mat-select formControlName="type">
                @for (t of types; track t.value) {
                  <mat-option [value]="t.value">{{ t.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          <mat-form-field>
            <mat-label>Data</mat-label>
            <input matInput [matDatepicker]="picker" formControlName="date" [min]="today" required />
            <mat-datepicker-toggle matIconSuffix [for]="picker" />
            <mat-datepicker #picker />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Hora</mat-label>
            <input matInput type="time" formControlName="time" required />
            <mat-hint>Horário de Brasília</mat-hint>
          </mat-form-field>
          @if (!data.task) {
            @if (data.canChooseAssignee) {
              <div class="app-span-all">
                <app-entity-picker [control]="form.controls.assignee" [source]="users" label="Responsável" hint="Vazio = operador do caso (ou a fila)." [single]="true" />
              </div>
            }
            <mat-form-field class="app-span-all">
              <mat-label>Observação</mat-label>
              <textarea matInput formControlName="note" rows="2" maxlength="1000"></textarea>
              <mat-hint>Sem dados pessoais além do necessário para o retorno.</mat-hint>
            </mat-form-field>
          }
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ data.task ? 'Reagendar' : 'Agendar' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class ScheduleDialog {
  protected readonly data = inject<ScheduleDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ScheduleDialog, TaskView>>(MatDialogRef);
  private readonly api = inject(TasksApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);

  protected readonly types = Object.entries(TASK_TYPE_LABELS).map(([value, label]) => ({ value: value as TaskType, label }));
  protected readonly today = fromLocalDate(zonedNow().date);
  protected readonly current = formatLocalDateTime(this.data.task?.dueAtLocal);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<TaskType>(this.data.task?.type ?? 'CALLBACK'),
    date: this.fb.control<Date | null>(this.data.task ? fromLocalDate(this.data.task.dueAtLocal.slice(0, 10)) : null, [AppValidators.notBlank]),
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
