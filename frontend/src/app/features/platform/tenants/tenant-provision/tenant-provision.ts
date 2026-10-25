import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { PlatformTenantsApi, ProvisionedTenant } from '../../data/platform.api';
import { DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';

type Field = 'slug' | 'legalName' | 'tradeName' | 'cnpj' | 'adminName' | 'adminEmail';

/** Provisiona assessoria, perfis padrão e primeiro administrador (que recebe link de ativação). */
@Component({
  selector: 'app-tenant-provision',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, ...DIALOG_DIRECTIVES, DocumentMask],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Provisionar assessoria</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <div class="app-form-grid row-gap-3">
          <app-field class="app-span-all" label="Identificador de acesso" hint="Usado no login. Letras minúsculas, números e hífen (3 a 50)." [error]="message('slug')">
            <input appInput formControlName="slug" class="app-mono" maxlength="50" autocapitalize="off" spellcheck="false" />
          </app-field>
          <app-field class="app-span-all" label="Razão social" [error]="message('legalName')">
            <input appInput formControlName="legalName" [maxlength]="limits.legalName" />
          </app-field>
          <app-field label="Nome fantasia" [error]="message('tradeName')">
            <input appInput formControlName="tradeName" [maxlength]="limits.legalName" />
          </app-field>
          <app-field label="CNPJ" [error]="message('cnpj')">
            <input appInput formControlName="cnpj" appDocumentMask="CNPJ" maxlength="18" />
          </app-field>
          <h3 class="app-span-all app-card__title">Primeiro administrador</h3>
          <app-field label="Nome" [error]="message('adminName')">
            <input appInput formControlName="adminName" [maxlength]="limits.personName" />
          </app-field>
          <app-field label="E-mail" [error]="message('adminEmail')">
            <input appInput type="email" formControlName="adminEmail" [maxlength]="limits.email" />
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Provisionar</button>
      </div>
    </form>
  `,
})
export class TenantProvision {
  private readonly ref = inject<DialogRef<ProvisionedTenant>>(DialogRef);
  private readonly api = inject(PlatformTenantsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly limits = Limits;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    slug: ['', [AppValidators.notBlank, Validators.pattern(Patterns.tenantSlug)]],
    legalName: ['', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]],
    tradeName: ['', [AppValidators.notBlank, Validators.maxLength(Limits.legalName)]],
    cnpj: ['', [AppValidators.notBlank, AppValidators.document('CNPJ')]],
    adminName: ['', [AppValidators.notBlank, Validators.maxLength(Limits.personName)]],
    adminEmail: ['', [AppValidators.notBlank, AppValidators.email, Validators.maxLength(Limits.email)]],
  });

  protected message(field: Field): string {
    const errors = this.form.controls[field].errors;
    if (field === 'slug' && errors?.['pattern']) {
      return 'Use 3 a 50 caracteres: letras minúsculas, números e hífen (sem hífen nas pontas).';
    }
    return errorMessage(errors);
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
      .provision({
        slug: v.slug.trim(),
        legalName: v.legalName.trim(),
        tradeName: v.tradeName.trim(),
        cnpj: v.cnpj,
        adminName: v.adminName.trim(),
        adminEmail: v.adminEmail.trim(),
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (result) => {
          this.notifications.success('Assessoria provisionada. O administrador recebeu o link de ativação.');
          this.ref.close(result);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
