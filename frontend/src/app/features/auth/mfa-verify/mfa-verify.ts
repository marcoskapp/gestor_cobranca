import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { AUTH_ROUTES, AuthService } from '../../../core/auth/auth.service';
import { toApiError } from '../../../core/http/problem';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators } from '../../../shared/validation/validators';
import { authErrorMessage, isExpiredStep } from '../auth-errors';

@Component({
  selector: 'app-mfa-verify',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <h1 class="app-auth-title">Verificação em duas etapas</h1>
    <p class="app-auth-text">Informe o código de 6 dígitos exibido no seu aplicativo autenticador.</p>
    @if (error(); as e) {
      <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
    }
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="app-form-stack">
      <mat-form-field>
        <mat-label>Código</mat-label>
        <input
          matInput
          formControlName="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          class="app-otp-input"
        />
        @if (code.invalid) {
          <mat-error>{{ message() }}</mat-error>
        }
      </mat-form-field>
      <button mat-flat-button type="submit" [disabled]="submitting()">Verificar</button>
      <button mat-button type="button" (click)="back()">Voltar ao login</button>
    </form>
  `,
})
export class MfaVerify {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly code = new FormControl('', {
    nonNullable: true,
    validators: [AppValidators.notBlank, AppValidators.totp],
  });
  protected readonly form = new FormGroup({ code: this.code });

  protected message(): string {
    return this.code.hasError('pattern') ? 'O código tem 6 dígitos.' : errorMessage(this.code.errors);
  }

  protected submit(): void {
    if (this.code.invalid || this.submitting()) {
      this.code.markAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.auth
      .verifyMfa(this.code.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (status) => void this.auth.continueFlow(status),
        error: (err: unknown) => {
          const apiError = toApiError(err);
          if (isExpiredStep(apiError)) {
            this.auth.cancelStep();
            void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'expirada' } });
            return;
          }
          this.code.reset();
          this.error.set(apiError.status === 401 ? 'Código inválido. Tente novamente.' : authErrorMessage(apiError));
        },
      });
  }

  protected back(): void {
    this.auth.cancelStep();
    void this.router.navigateByUrl(AUTH_ROUTES.login);
  }
}
