import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthApi } from '../../../core/auth/auth.api';
import { TenantMemory } from '../../../core/auth/tenant-memory';
import { toApiError } from '../../../core/http/problem';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { authErrorMessage } from '../auth-errors';

/** Solicitação de redefinição. A resposta é sempre a mesma (não revela se o e-mail existe). */
@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <h1 class="app-auth-title">Esqueci minha senha</h1>
    @if (sent()) {
      <p class="app-alert app-alert--info" role="status">
        Se houver uma conta ativa com esses dados, você receberá um e-mail com o link para redefinir a senha.
        O link é válido por tempo limitado.
      </p>
      <a mat-flat-button routerLink="/login" class="app-full-width">Voltar ao login</a>
    } @else {
      <p class="app-auth-text">Informe a assessoria e o e-mail cadastrado para receber o link de redefinição.</p>
      @if (error(); as e) {
        <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="app-form-stack">
        <mat-form-field>
          <mat-label>Assessoria</mat-label>
          <input matInput formControlName="tenant" autocapitalize="off" spellcheck="false" [maxlength]="limits.tenantSlug" />
          @if (form.controls.tenant.invalid) {
            <mat-error>{{ message('tenant') }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field>
          <mat-label>E-mail</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="username" [maxlength]="limits.email" />
          @if (form.controls.email.invalid) {
            <mat-error>{{ message('email') }}</mat-error>
          }
        </mat-form-field>
        <button mat-flat-button type="submit" [disabled]="submitting()">Enviar link</button>
        <a mat-button routerLink="/login">Voltar ao login</a>
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

  protected message(control: 'tenant' | 'email'): string {
    return errorMessage(this.form.controls[control].errors);
  }

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
