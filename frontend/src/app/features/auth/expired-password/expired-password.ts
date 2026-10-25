import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { AUTH_ROUTES, AuthService } from '../../../core/auth/auth.service';
import { toApiError } from '../../../core/http/problem';
import { NEW_PASSWORD_HINT, PasswordField } from '../../../shared/components/password-field/password-field';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { authErrorMessage, isExpiredStep } from '../auth-errors';
import { buildNewPasswordForm } from '../new-password-form';

/** Troca de senha obrigatória (expirada ou primeiro acesso), com step token. */
@Component({
  selector: 'app-expired-password',
  imports: [ReactiveFormsModule, PasswordField],
  template: `
    <h1 class="app-auth-title">Defina uma nova senha</h1>
    <p class="app-auth-text">Sua senha expirou ou precisa ser trocada antes de continuar.</p>
    @if (error(); as e) {
      <div class="alert alert-danger" role="alert">{{ e }}</div>
    }
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="app-form-stack">
      <app-password-field
        [control]="form.controls.newPassword"
        label="Nova senha"
        autocomplete="new-password"
        [hint]="hint"
      />
      <app-password-field [control]="form.controls.confirmation" label="Confirme a nova senha" autocomplete="new-password" />
      @if (form.hasError('mismatch') && form.controls.confirmation.touched) {
        <div class="invalid-feedback d-block" role="alert">As senhas não conferem.</div>
      }
      <button type="submit" class="btn btn-primary mt-2" [disabled]="submitting()">Salvar nova senha</button>
      <button type="button" class="btn btn-link" (click)="back()">Voltar ao login</button>
    </form>
  `,
})
export class ExpiredPassword {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly hint = NEW_PASSWORD_HINT;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = buildNewPasswordForm(inject(FormBuilder));

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.auth
      .changeExpiredPassword(this.form.controls.newPassword.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'senha-alterada' } }),
        error: (err: unknown) => {
          const apiError = toApiError(err);
          if (isExpiredStep(apiError)) {
            this.auth.cancelStep();
            void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'expirada' } });
            return;
          }
          applyServerErrors(this.form, apiError);
          this.error.set(authErrorMessage(apiError));
        },
      });
  }

  protected back(): void {
    this.auth.cancelStep();
    void this.router.navigateByUrl(AUTH_ROUTES.login);
  }
}
