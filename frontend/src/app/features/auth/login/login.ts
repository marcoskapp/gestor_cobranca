import { Component, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError } from '../../../core/http/problem';
import { PasswordField } from '../../../shared/components/password-field/password-field';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { authErrorMessage } from '../auth-errors';

const REASONS: Record<string, string> = {
  logout: 'Você saiu do sistema.',
  expirada: 'Sua sessão expirou. Entre novamente para continuar.',
  'senha-alterada': 'Senha alterada. Entre com a nova senha.',
  'conta-ativada': 'Conta ativada. Entre com a senha definida.',
  'senha-redefinida': 'Senha redefinida. Entre com a nova senha.',
};

@Component({
  selector: 'app-login',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    PasswordField,
  ],
  templateUrl: './login.html',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  /** Query params (withComponentInputBinding). */
  readonly returnUrl = input<string>();
  readonly motivo = input<string>();

  protected readonly limits = Limits;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = computed(() => REASONS[this.motivo() ?? ''] ?? null);

  protected readonly form = this.fb.group({
    tenant: [this.auth.rememberedTenant(), [AppValidators.notBlank, Validators.maxLength(Limits.tenantSlug)]],
    email: ['', [AppValidators.notBlank, AppValidators.email, Validators.maxLength(Limits.email)]],
    password: ['', [AppValidators.notBlank, Validators.maxLength(Limits.password)]],
  });

  protected message(control: 'tenant' | 'email'): string {
    return errorMessage(this.form.controls[control].errors);
  }

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.auth.setReturnUrl(this.returnUrl());
    this.auth
      .login(this.form.getRawValue())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (status) => void this.auth.continueFlow(status),
        error: (err: unknown) => {
          this.form.controls.password.reset();
          this.error.set(authErrorMessage(toApiError(err)));
        },
      });
  }
}
