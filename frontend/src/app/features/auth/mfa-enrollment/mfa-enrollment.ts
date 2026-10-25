import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { MfaEnrollment as Enrollment } from '../../../core/auth/auth.models';
import { AUTH_ROUTES, AuthService } from '../../../core/auth/auth.service';
import { toApiError } from '../../../core/http/problem';
import { TotpSetup } from '../../../shared/components/totp-setup/totp-setup';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators } from '../../../shared/validation/validators';
import { authErrorMessage, isExpiredStep } from '../auth-errors';
import { FORM_FIELD } from '../../../shared/forms/field';

/** Cadastro obrigatório de MFA durante o login (perfis que exigem MFA). */
@Component({
  selector: 'app-mfa-enrollment',
  imports: [ReactiveFormsModule, ...FORM_FIELD, TotpSetup],
  template: `
    <h1 class="app-auth-title">Configure a verificação em duas etapas</h1>
    <p class="app-auth-text">Seu perfil exige um segundo fator de autenticação para acessar o sistema.</p>
    @if (error(); as e) {
      <div class="alert alert-danger" role="alert">{{ e }}</div>
    }
    @if (enrollment(); as en) {
      <app-totp-setup [otpauthUri]="en.otpauthUri" [secret]="en.secret" />
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="app-form-stack">
        <app-field label="Código do aplicativo" [error]="message()">
          <input appInput formControlName="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" class="app-otp-input" />
        </app-field>
        <button type="submit" class="btn btn-primary mt-2" [disabled]="submitting()">Confirmar e entrar</button>
      </form>
    } @else if (loading()) {
      <div class="progress mb-3" role="progressbar" aria-label="Preparando cadastro">
        <div class="progress-bar progress-bar-striped progress-bar-animated w-100"></div>
      </div>
    }
    <button type="button" class="btn btn-link w-100" (click)="back()">Voltar ao login</button>
  `,
})
export class MfaEnrollmentPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly enrollment = signal<Enrollment | null>(null);
  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly code = new FormControl('', {
    nonNullable: true,
    validators: [AppValidators.notBlank, AppValidators.totp],
  });
  protected readonly form = new FormGroup({ code: this.code });

  ngOnInit(): void {
    this.auth
      .startMfaEnrollment()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (enrollment) => this.enrollment.set(enrollment),
        error: (err: unknown) => this.handleError(err),
      });
  }

  private readonly codeValue = toSignal(this.code.valueChanges, { initialValue: '' });
  protected readonly message = computed(() => {
    this.codeValue();
    return this.code.hasError('pattern') ? 'O código tem 6 dígitos.' : errorMessage(this.code.errors);
  });

  protected submit(): void {
    if (this.code.invalid || this.submitting()) {
      this.code.markAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.auth
      .confirmMfaEnrollment(this.code.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (status) => void this.auth.continueFlow(status),
        error: (err: unknown) => {
          this.code.reset();
          this.handleError(err, 'Código inválido. Confira o horário do seu dispositivo e tente novamente.');
        },
      });
  }

  protected back(): void {
    this.auth.cancelStep();
    void this.router.navigateByUrl(AUTH_ROUTES.login);
  }

  private handleError(err: unknown, unauthorizedMessage?: string): void {
    const apiError = toApiError(err);
    if (isExpiredStep(apiError)) {
      this.auth.cancelStep();
      void this.router.navigate([AUTH_ROUTES.login], { queryParams: { motivo: 'expirada' } });
      return;
    }
    this.error.set(apiError.status === 401 && unauthorizedMessage ? unauthorizedMessage : authErrorMessage(apiError));
  }
}
