import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { NEW_PASSWORD_HINT, PasswordField } from '../../../shared/components/password-field/password-field';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { AccountApi } from '../account.api';
import { Icon } from '../../../shared/components/icon/icon';

/** Troca de senha com confirmação da atual. O servidor encerra as demais sessões. */
@Component({
  selector: 'app-change-password',
  imports: [Icon, ReactiveFormsModule, PasswordField, PageHeader],
  template: `
    <app-page-header title="Trocar senha" subtitle="Ao trocar a senha, suas outras sessões abertas serão encerradas." />
    <form class="app-card change-password" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      @if (error(); as e) {
        <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
      }
      <app-password-field [control]="form.controls.currentPassword" label="Senha atual" autocomplete="current-password" />
      <app-password-field [control]="form.controls.newPassword" label="Nova senha" autocomplete="new-password" [hint]="hint" />
      <app-password-field [control]="form.controls.confirmation" label="Confirme a nova senha" autocomplete="new-password" />
      @if (form.hasError('mismatch') && form.controls.confirmation.touched) {
        <div class="invalid-feedback d-block" role="alert">As senhas não conferem.</div>
      }
      <div class="app-form-actions">
        <button class="btn btn-primary" type="submit" [disabled]="submitting()">Trocar senha</button>
      </div>
    </form>
  `,
  styles: `
    .change-password {
      max-width: 480px;
    }
  `,
})
export class ChangePassword {
  private readonly api = inject(AccountApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly hint = NEW_PASSWORD_HINT;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      currentPassword: ['', [AppValidators.notBlank, Validators.maxLength(Limits.password)]],
      newPassword: [
        '',
        [AppValidators.notBlank, AppValidators.password(() => ({ name: this.auth.user()?.name, email: this.auth.user()?.email }))],
      ],
      confirmation: ['', [AppValidators.notBlank]],
    },
    { validators: AppValidators.matching('newPassword', 'confirmation') },
  );

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.error.set(null);
    this.submitting.set(true);
    this.api
      .changePassword(v.currentPassword, v.newPassword)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.form.reset();
          this.notifications.success('Senha alterada. As demais sessões foram encerradas.');
        },
        error: (err: unknown) => {
          const apiError = toApiError(err);
          applyServerErrors(this.form, apiError);
          this.error.set(userMessage(apiError));
        },
      });
  }
}
