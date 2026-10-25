import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { ContractsApi } from '../../data/organization.api';
import { ContractView } from '../../data/organization.models';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

@Component({
  selector: 'app-contract-terminate',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, NgbInputDatepicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Encerrar contrato {{ contract.number }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-muted">O encerramento é definitivo e fica registrado na trilha de auditoria.</p>
        <div class="vstack gap-3">
          <app-field label="Data de encerramento" [error]="message('endDate')">
            <div class="input-group">
              <input appInput formControlName="endDate" ngbAutofocus ngbDatepicker #end="ngbDatepicker" placeholder="dd/mm/aaaa" autocomplete="off" />
              <button class="btn btn-outline-secondary" type="button" (click)="end.toggle()" aria-label="Abrir calendário: Data de encerramento">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Motivo" [error]="message('reason')">
            <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-danger" [disabled]="saving()">Encerrar contrato</button>
      </div>
    </form>
  `,
})
export class ContractTerminate {
  protected readonly contract = inject<ContractView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ContractView>>(DialogRef);
  private readonly api = inject(ContractsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly form = inject(FormBuilder).group({
    // modelo ISO (yyyy-MM-dd) do ngbDatepicker
    endDate: [toLocalDate(new Date()), [Validators.required]],
    reason: ['', [AppValidators.notBlank, Validators.maxLength(Limits.reason)]],
  });

  protected message(field: 'endDate' | 'reason'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .terminate(this.contract.id, v.endDate as string, (v.reason ?? '').trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((updated) => {
        this.notifications.success('Contrato encerrado.');
        this.ref.close(updated);
      });
  }
}
