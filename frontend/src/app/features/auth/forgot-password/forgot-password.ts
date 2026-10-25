import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthApi } from '../../../core/auth/auth.api';
import { TenantMemory } from '../../../core/auth/tenant-memory';
import { toApiError } from '../../../core/http/problem';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { authErrorMessage } from '../auth-errors';
import { FORM_FIELD } from '../../../shared/forms/field';

/** Solicitação de redefinição. A resposta é sempre a mesma (não revela se o e-mail existe). */
@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, ...FORM_FIELD],
  template: `
    <h1 class="app-auth-title">Esqueci minha senha</h1>
    @if (sent()) {
      <div class="alert alert-info" role="status">
        Se houver uma conta ativa com esses dados, você receberá um e-mail com o link para redefinir a senha.
        O link é válido por tempo limitado.
      </div>
      <a class="btn btn-primary w-100" routerLink="/login">Voltar ao login</a>
    } @else {
      <p class="app-auth-text">Informe a assessoria e o e-mail cadastrado para receber o link de redefinição.</p>
      @if (error(); as e) {
        <div class="alert alert-danger" role="alert">{{ e }}</div>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="app-form-stack">
        <app-field label="Assessoria">
          <input appInput formControlName="tenant" autocapitalize="off" spellcheck="false" [maxlength]="limits.tenantSlug" />
        </app-field>
        <app-field label="E-mail">
          <input appInput type="email" formControlName="email" autocomplete="username" [maxlength]="limits.email" />
        </app-field>
        <button type="submit" class="btn btn-primary mt-2" [disabled]="submitting()">Enviar link</button>
        <a class="btn btn-link" routerLink="/login">Voltar ao login</a>
      </form>
    }
  `,
})
export class ForgotPassword {
  private readonly api = inject(AuthApi);
  protected readonly limits = Limits;
  protected readonly submitting = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    tenant: [inject(TenantMemory).read(), [AppValidators.notBlank, Validators.maxLength(Limits.tenantSlug)]],
    email: ['', [AppValidators.notBlank, AppValidators.email, Validators.maxLength(Limits.email)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const { tenant, email } = this.form.getRawValue();
    this.error.set(null);
    this.submitting.set(true);
    this.api
      .forgotPassword(tenant.trim().toLowerCase(), email.trim())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => this.sent.set(true),
        error: (err: unknown) => this.error.set(authErrorMessage(toApiError(err))),
      });
  }
}
