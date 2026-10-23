import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { LegalBasesApi } from '../data/compliance.api';
import { LEGAL_BASIS_LABELS } from '../data/compliance.labels';
import { LegalBasisView } from '../data/compliance.models';

/** Encerra a vigência da base legal no último dia informado; o registro é preservado. */
@Component({
  selector: 'app-legal-basis-end',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Encerrar base legal</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">
          {{ labels[data.legalBasis] }} · {{ data.contractReference }}. Depois do último dia, o tratamento dos dados deste alcance precisa de
          outra base legal registrada.
        </p>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Último dia de vigência</mat-label>
          <input matInput [matDatepicker]="last" formControlName="lastDay" [min]="minDate" />
          <mat-datepicker-toggle matIconSuffix [for]="last" />
          <mat-datepicker #last />
          @if (form.controls.lastDay.invalid) {
            <mat-error>{{ message('lastDay') }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Motivo</mat-label>
          <textarea matInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
          @if (form.controls.reason.invalid) {
            <mat-error>{{ message('reason') }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" class="app-button-danger" [disabled]="saving()">Encerrar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class LegalBasisEnd {
  protected readonly data = inject<LegalBasisView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<LegalBasisEnd, LegalBasisView>>(MatDialogRef);
  private readonly api = inject(LegalBasesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly labels = LEGAL_BASIS_LABELS;
  protected readonly maxReason = Limits.reason;
  protected readonly minDate = fromLocalDate(this.data.validFrom);
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    lastDay: this.fb.control<Date | null>(new Date(), [Validators.required]),
    reason: ['', [AppValidators.notBlank, Validators.maxLength(Limits.reason)]],
  });

  protected message(field: 'lastDay' | 'reason'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .end(this.data.id, toLocalDate(v.lastDay)!, v.reason.trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Vigência da base legal encerrada.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
