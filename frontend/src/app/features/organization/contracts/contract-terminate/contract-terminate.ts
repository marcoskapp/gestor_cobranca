import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { ContractsApi } from '../../data/organization.api';
import { ContractView } from '../../data/organization.models';

@Component({
  selector: 'app-contract-terminate',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatDatepickerModule],
  template: `
    <h2 mat-dialog-title>Encerrar contrato {{ contract.number }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-muted">O encerramento é definitivo e fica registrado na trilha de auditoria.</p>
        <div class="app-form-stack">
          <mat-form-field>
            <mat-label>Data de encerramento</mat-label>
            <input matInput [matDatepicker]="end" formControlName="endDate" placeholder="dd/mm/aaaa" />
            <mat-datepicker-toggle matIconSuffix [for]="end" />
            <mat-datepicker #end />
            @if (form.controls.endDate.invalid) {
              <mat-error>{{ message('endDate') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Motivo</mat-label>
            <textarea matInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
            @if (form.controls.reason.invalid) {
              <mat-error>{{ message('reason') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" class="app-button-danger" [disabled]="saving()">Encerrar contrato</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class ContractTerminate {
  protected readonly contract = inject<ContractView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ContractTerminate, ContractView>>(MatDialogRef);
  private readonly api = inject(ContractsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly form = inject(FormBuilder).group({
    endDate: [new Date() as Date | null, [Validators.required]],
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
      .terminate(this.contract.id, toLocalDate(v.endDate) as string, (v.reason ?? '').trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((updated) => {
        this.notifications.success('Contrato encerrado.');
        this.ref.close(updated);
      });
  }
}
