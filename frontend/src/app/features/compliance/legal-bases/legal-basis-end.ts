import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { toDateStruct, toLocalDate } from '../../../shared/models/dates';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { LegalBasesApi } from '../data/compliance.api';
import { LEGAL_BASIS_LABELS } from '../data/compliance.labels';
import { LegalBasisView } from '../data/compliance.models';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

/** Encerra a vigência da base legal no último dia informado; o registro é preservado. */
@Component({
  selector: 'app-legal-basis-end',
  imports: [ReactiveFormsModule, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Encerrar base legal</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          {{ labels[data.legalBasis] }} · {{ data.contractReference }}. Depois do último dia, o tratamento dos dados deste alcance precisa de
          outra base legal registrada.
        </p>
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <app-field class="app-full-width mb-3" label="Último dia de vigência" [error]="form.controls.lastDay.invalid ? (message('lastDay')) : undefined">
          <div class="input-group">
            <input appInput ngbDatepicker #last="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="lastDay" [minDate]="minDate" />
            <button type="button" class="btn btn-outline-secondary" (click)="last.toggle()" aria-label="Abrir calendário">
              <app-icon name="calendar-event" />
            </button>
          </div>
        </app-field>
        <app-field class="app-full-width mb-3" label="Motivo" [error]="form.controls.reason.invalid ? (message('reason')) : undefined">
          <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-danger" [disabled]="saving()">Encerrar</button>
      </div>
    </form>
  `,
})
export class LegalBasisEnd {
  protected readonly data = inject<LegalBasisView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<LegalBasisView>>(DialogRef);
  private readonly api = inject(LegalBasesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly labels = LEGAL_BASIS_LABELS;
  protected readonly maxReason = Limits.reason;
  protected readonly minDate = toDateStruct(this.data.validFrom);
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    lastDay: this.fb.control<string | null>(toLocalDate(new Date()), [Validators.required]),
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
      .end(this.data.id, v.lastDay!, v.reason.trim())
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
