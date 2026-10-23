import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { fromLocalDate, toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { DebtorsApi } from '../../data/debt.api';
import { DebtorView } from '../../data/debt.models';

type Field = 'document' | 'name' | 'birthDate' | 'notes';

/** Cadastro manual (com documento) e edição (sem documento: ele é imutável) do devedor. */
@Component({
  selector: 'app-debtor-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatDatepickerModule, DocumentMask],
  template: `
    <h2 mat-dialog-title>{{ debtor ? 'Editar devedor' : 'Novo devedor' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          @if (!debtor) {
            <mat-form-field>
              <mat-label>CPF ou CNPJ</mat-label>
              <input matInput formControlName="document" appDocumentMask maxlength="18" />
              @if (form.controls.document.invalid) {
                <mat-error>{{ message('document') }}</mat-error>
              }
            </mat-form-field>
          }
          <mat-form-field [class.app-span-all]="!!debtor">
            <mat-label>Data de nascimento</mat-label>
            <input matInput [matDatepicker]="birth" formControlName="birthDate" placeholder="dd/mm/aaaa" [max]="today" />
            <mat-datepicker-toggle matIconSuffix [for]="birth" />
            <mat-datepicker #birth startView="multi-year" />
            @if (form.controls.birthDate.invalid) {
              <mat-error>{{ message('birthDate') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Nome ou razão social</mat-label>
            <input matInput formControlName="name" [maxlength]="limits.legalName" autocomplete="off" />
            @if (form.controls.name.invalid) {
              <mat-error>{{ message('name') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Observações</mat-label>
            <textarea matInput formControlName="notes" rows="3" [maxlength]="maxNotes"></textarea>
            <mat-hint>Não registre dados de saúde, religião ou outros dados sensíveis.</mat-hint>
            @if (form.controls.notes.invalid) {
              <mat-error>{{ message('notes') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ debtor ? 'Salvar' : 'Cadastrar devedor' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class DebtorForm {
  protected readonly debtor = inject<DebtorView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DebtorForm, DebtorView>>(MatDialogRef);
  private readonly api = inject(DebtorsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly maxNotes = 2000;
  protected readonly today = new Date();
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder);
  protected readonly form = this.fb.group({
    document: this.fb.nonNullable.control('', this.debtor ? [] : [AppValidators.notBlank, AppValidators.document()]),
    name: this.fb.nonNullable.control(this.debtor?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]),
    birthDate: this.fb.control<Date | null>(fromLocalDate(this.debtor?.birthDate)),
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
    const common = { name: v.name.trim(), birthDate: toLocalDate(v.birthDate), notes: v.notes.trim() || null };
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
