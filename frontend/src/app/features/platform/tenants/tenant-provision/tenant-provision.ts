import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { PlatformTenantsApi, ProvisionedTenant } from '../../data/platform.api';

type Field = 'slug' | 'legalName' | 'tradeName' | 'cnpj' | 'adminName' | 'adminEmail';

/** Provisiona assessoria, perfis padrão e primeiro administrador (que recebe link de ativação). */
@Component({
  selector: 'app-tenant-provision',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, DocumentMask],
  template: `
    <h2 mat-dialog-title>Provisionar assessoria</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field class="app-span-all">
            <mat-label>Identificador de acesso</mat-label>
            <input matInput formControlName="slug" class="app-mono" maxlength="50" autocapitalize="off" spellcheck="false" />
            <mat-hint>Usado no login. Letras minúsculas, números e hífen (3 a 50).</mat-hint>
            @if (form.controls.slug.invalid) {
              <mat-error>{{ message('slug') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Razão social</mat-label>
            <input matInput formControlName="legalName" [maxlength]="limits.legalName" />
            @if (form.controls.legalName.invalid) {
              <mat-error>{{ message('legalName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nome fantasia</mat-label>
            <input matInput formControlName="tradeName" [maxlength]="limits.legalName" />
            @if (form.controls.tradeName.invalid) {
              <mat-error>{{ message('tradeName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>CNPJ</mat-label>
            <input matInput formControlName="cnpj" appDocumentMask="CNPJ" maxlength="18" />
            @if (form.controls.cnpj.invalid) {
              <mat-error>{{ message('cnpj') }}</mat-error>
            }
          </mat-form-field>
          <h3 class="app-span-all app-card__title">Primeiro administrador</h3>
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="adminName" [maxlength]="limits.personName" />
            @if (form.controls.adminName.invalid) {
              <mat-error>{{ message('adminName') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>E-mail</mat-label>
            <input matInput type="email" formControlName="adminEmail" [maxlength]="limits.email" />
            @if (form.controls.adminEmail.invalid) {
              <mat-error>{{ message('adminEmail') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Provisionar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class TenantProvision {
  private readonly ref = inject<MatDialogRef<TenantProvision, ProvisionedTenant>>(MatDialogRef);
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
