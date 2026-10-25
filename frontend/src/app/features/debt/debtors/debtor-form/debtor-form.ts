import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { toDateStruct, toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { DebtorsApi } from '../../data/debt.api';
import { DebtorView } from '../../data/debt.models';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';

type Field = 'document' | 'name' | 'birthDate' | 'notes';

/** Cadastro manual (com documento) e edição (sem documento: ele é imutável) do devedor. */
@Component({
  selector: 'app-debtor-form',
  imports: [ReactiveFormsModule, DocumentMask, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ debtor ? 'Editar devedor' : 'Novo devedor' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          @if (!debtor) {
            <app-field label="CPF ou CNPJ" [error]="form.controls.document.invalid ? (message('document')) : undefined">
              <input appInput formControlName="document" appDocumentMask maxlength="18" />
            </app-field>
          }
          <app-field [class.app-span-all]="!!debtor" label="Data de nascimento" [error]="form.controls.birthDate.invalid ? (message('birthDate')) : undefined">
            <div class="input-group">
              <input appInput ngbDatepicker #birth="ngbDatepicker" formControlName="birthDate" placeholder="dd/mm/aaaa" [maxDate]="today" />
              <button type="button" class="btn btn-outline-secondary" (click)="birth.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="app-span-all" label="Nome ou razão social" [error]="form.controls.name.invalid ? (message('name')) : undefined">
            <input appInput formControlName="name" [maxlength]="limits.legalName" autocomplete="off" />
          </app-field>
          <app-field class="app-span-all" label="Observações" hint="Não registre dados de saúde, religião ou outros dados sensíveis." [error]="form.controls.notes.invalid ? (message('notes')) : undefined">
            <textarea appInput formControlName="notes" rows="3" [maxlength]="maxNotes"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ debtor ? 'Salvar' : 'Cadastrar devedor' }}</button>
      </div>
    </form>
  `,
})
export class DebtorForm {
  protected readonly debtor = inject<DebtorView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DebtorView>>(DialogRef);
  private readonly api = inject(DebtorsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly maxNotes = 2000;
  protected readonly today = toDateStruct(toLocalDate(new Date())!);
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder);
  protected readonly form = this.fb.group({
    document: this.fb.nonNullable.control('', this.debtor ? [] : [AppValidators.notBlank, AppValidators.document()]),
    name: this.fb.nonNullable.control(this.debtor?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]),
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    birthDate: this.fb.control<string | null>(this.debtor?.birthDate ?? null),
    notes: this.fb.nonNullable.control(this.debtor?.notes ?? '', [Validators.maxLength(2000)]),
  });

  protected message(field: Field): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const common = { name: v.name.trim(), birthDate: v.birthDate || null, notes: v.notes.trim() || null };
    this.saving.set(true);
    this.generalErrors.set([]);
    (this.debtor ? this.api.update(this.debtor.id, common) : this.api.register({ ...common, document: v.document }))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(this.debtor ? 'Devedor atualizado.' : 'Devedor cadastrado.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
