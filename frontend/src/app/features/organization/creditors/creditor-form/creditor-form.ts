import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

type Field = 'legalName' | 'tradeName' | 'document' | 'externalCode' | 'contactEmail';

@Component({
  selector: 'app-creditor-form',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, ...DIALOG_DIRECTIVES, DocumentMask],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ creditor ? 'Editar credor' : 'Novo credor' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <div class="app-form-grid row-gap-3">
          <app-field class="app-span-all" label="Razão social" [error]="message('legalName')">
            <input appInput formControlName="legalName" [maxlength]="limits.legalName" />
          </app-field>
          <app-field class="app-span-all" label="Nome fantasia" [error]="message('tradeName')">
            <input appInput formControlName="tradeName" [maxlength]="limits.legalName" />
          </app-field>
          <app-field label="CNPJ ou CPF" [error]="message('document')">
            <input appInput formControlName="document" appDocumentMask maxlength="18" />
          </app-field>
          <app-field label="Código externo" hint="Identificador do credor em sistemas de origem" [error]="message('externalCode')">
            <input appInput formControlName="externalCode" [maxlength]="limits.externalCode" />
          </app-field>
          <app-field class="app-span-all" label="E-mail de contato" [error]="message('contactEmail')">
            <input appInput type="email" formControlName="contactEmail" [maxlength]="limits.email" />
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ creditor ? 'Salvar' : 'Cadastrar credor' }}</button>
      </div>
    </form>
  `,
})
export class CreditorForm {
  protected readonly creditor = inject<CreditorView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CreditorView>>(DialogRef);
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
