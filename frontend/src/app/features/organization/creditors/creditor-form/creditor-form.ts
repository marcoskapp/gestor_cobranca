import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { formatDocument } from '../../../../shared/validation/document';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { CreditorsApi } from '../../data/organization.api';
import { CreditorView } from '../../data/organization.models';

type Field = 'legalName' | 'tradeName' | 'document' | 'externalCode' | 'contactEmail';

@Component({
  selector: 'app-creditor-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, DocumentMask],
  template: `
    <h2 mat-dialog-title>{{ creditor ? 'Editar credor' : 'Novo credor' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field class="app-span-all">
            <mat-label>Razão social</mat-label>
            <input matInput formControlName="legalName" [maxlength]="limits.legalName" />
            @if (form.controls.legalName.invalid) {
              <mat-error>{{ message('legalName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Nome fantasia</mat-label>
            <input matInput formControlName="tradeName" [maxlength]="limits.legalName" />
            @if (form.controls.tradeName.invalid) {
              <mat-error>{{ message('tradeName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>CNPJ ou CPF</mat-label>
            <input matInput formControlName="document" appDocumentMask maxlength="18" />
            @if (form.controls.document.invalid) {
              <mat-error>{{ message('document') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Código externo</mat-label>
            <input matInput formControlName="externalCode" [maxlength]="limits.externalCode" />
            <mat-hint>Identificador do credor em sistemas de origem</mat-hint>
            @if (form.controls.externalCode.invalid) {
              <mat-error>{{ message('externalCode') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>E-mail de contato</mat-label>
            <input matInput type="email" formControlName="contactEmail" [maxlength]="limits.email" />
            @if (form.controls.contactEmail.invalid) {
              <mat-error>{{ message('contactEmail') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ creditor ? 'Salvar' : 'Cadastrar credor' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class CreditorForm {
  protected readonly creditor = inject<CreditorView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CreditorForm, CreditorView>>(MatDialogRef);
  private readonly api = inject(CreditorsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    legalName: [this.creditor?.legalName ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]],
    tradeName: [this.creditor?.tradeName ?? '', [Validators.maxLength(Limits.legalName)]],
    document: [formatDocument(this.creditor?.document), [AppValidators.notBlank, AppValidators.document()]],
    externalCode: [this.creditor?.externalCode ?? '', [Validators.maxLength(Limits.externalCode)]],
    contactEmail: [this.creditor?.contactEmail ?? '', [AppValidators.email, Validators.maxLength(Limits.email)]],
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
    const request = {
      legalName: v.legalName.trim(),
      tradeName: v.tradeName.trim() || null,
      document: v.document,
      externalCode: v.externalCode.trim() || null,
      contactEmail: v.contactEmail.trim() || null,
    };
    this.saving.set(true);
    this.generalErrors.set([]);
    (this.creditor ? this.api.update(this.creditor.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(this.creditor ? 'Credor atualizado.' : 'Credor cadastrado.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
