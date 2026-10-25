import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { MfaEnrollment } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { TotpSetup } from '../../../shared/components/totp-setup/totp-setup';
import { errorMessage } from '../../../shared/validation/error-messages';
import { AppValidators } from '../../../shared/validation/validators';
import { AccountApi } from '../account.api';
import { FORM_FIELD } from '../../../shared/forms/field';
import { Icon } from '../../../shared/components/icon/icon';

/** Ativação voluntária de MFA pelo próprio usuário. */
@Component({
  selector: 'app-enable-mfa',
  imports: [Icon, ...FORM_FIELD,
    ReactiveFormsModule,
    RouterLink,
    PageHeader,
    TotpSetup,
  ],
  template: `
    <app-page-header title="Verificação em duas etapas" subtitle="Proteja sua conta com um código gerado no celular." />
    <section class="app-card enable-mfa">
      @if (alreadyEnabled()) {
        <div class="alert alert-success d-flex align-items-start gap-2" role="status"><app-icon name="check-circle" /><span>A verificação em duas etapas está ativa na sua conta.</span></div>
        <a class="btn btn-outline-primary" routerLink="/conta/perfil">Voltar ao perfil</a>
      } @else if (enrollment(); as en) {
        @if (error(); as e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <app-totp-setup [otpauthUri]="en.otpauthUri" [secret]="en.secret" />
        <form [formGroup]="form" (ngSubmit)="confirm()" novalidate class="vstack gap-3">
          <app-field label="Código do aplicativo" [error]="message()">
            <input appInput formControlName="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" class="app-otp-input" />
          </app-field>
          <div class="app-form-actions">
            <a class="btn btn-link" routerLink="/conta/perfil">Cancelar</a>
            <button class="btn btn-primary" type="submit" [disabled]="submitting()">Ativar</button>
          </div>
        </form>
      } @else {
        <p>
          Você precisará de um aplicativo autenticador no celular. A cada acesso, além da senha, será solicitado o código
          de 6 dígitos exibido no aplicativo.
        </p>
        <button class="btn btn-primary" type="button" (click)="start()" [disabled]="starting()">Começar</button>
        @if (starting()) {
          <div class="progress" role="progressbar" aria-label="Preparando cadastro">
            <div class="progress-bar progress-bar-striped progress-bar-animated w-100"></div>
          </div>
        }
      }
    </section>
  `,
  styles: `
    .enable-mfa {
      max-width: 640px;
    }
  `,
})
export class EnableMfa {
  private readonly api = inject(AccountApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly alreadyEnabled = computed(() => this.auth.user()?.mfaEnabled ?? false);
  protected readonly enrollment = signal<MfaEnrollment | null>(null);
  protected readonly starting = signal(false);
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

  protected start(): void {
    this.starting.set(true);
    this.api
      .startMfaEnrollment()
      .pipe(finalize(() => this.starting.set(false)))
      .subscribe((enrollment) => this.enrollment.set(enrollment));
  }

  protected confirm(): void {
    if (this.code.invalid || this.submitting()) {
      this.code.markAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.api
      .confirmMfaEnrollment(this.code.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.enrollment.set(null);
          this.notifications.success('Verificação em duas etapas ativada.');
          this.auth.reloadProfile().subscribe();
        },
        error: (err: unknown) => {
          this.code.reset();
          this.error.set(userMessage(toApiError(err)));
        },
      });
  }
}
