import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthApi } from '../../../core/auth/auth.api';
import { AUTH_ROUTES } from '../../../core/auth/auth.service';
import { toApiError } from '../../../core/http/problem';
import { NEW_PASSWORD_HINT, PasswordField } from '../../../shared/components/password-field/password-field';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { authErrorMessage } from '../auth-errors';
import { buildNewPasswordForm } from '../new-password-form';

export type CredentialPurpose = 'activation' | 'reset';

/**
 * Ativação de conta (`/ativar-conta#token=`) e redefinição de senha (`/redefinir-senha#token=`).
 * O token vem no fragmento da URL, que o navegador não envia ao servidor (não aparece nos logs do ALB
 * nem no `Referer`); é lido uma única vez e removido da barra de endereços (não fica no histórico).
 */
@Component({
  selector: 'app-credential-action',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, PasswordField],
  template: `
    <h1 class="app-auth-title">{{ title() }}</h1>
    @if (!actionToken) {
      <p class="app-alert app-alert--error" role="alert">
        Link inválido ou incompleto. Solicite um novo link ao administrador ou pela opção "Esqueci minha senha".
      </p>
      <a mat-flat-button routerLink="/esqueci-a-senha" class="app-full-width">Solicitar novo link</a>
    } @else {
      <p class="app-auth-text">{{ description() }}</p>
      @if (error(); as e) {
        <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
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
          <p class="app-field-error" role="alert">As senhas não conferem.</p>
        }
        <button mat-flat-button type="submit" [disabled]="submitting()">{{ submitLabel() }}</button>
      </form>
    }
    <p class="app-auth-links"><a routerLink="/login">Voltar ao login</a></p>
  `,
})
export class CredentialAction implements OnInit {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly purpose = input.required<CredentialPurpose>();

  /** Lido do fragmento na criação e mantido apenas em memória. */
  protected readonly actionToken: string | null = credentialToken(this.route);
  protected readonly hint = NEW_PASSWORD_HINT;
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = buildNewPasswordForm(inject(FormBuilder));

  protected readonly title = computed(() => (this.purpose() === 'activation' ? 'Ativar conta' : 'Redefinir senha'));
  protected readonly description = computed(() =>
    this.purpose() === 'activation'
      ? 'Defina a senha de acesso para ativar sua conta.'
      : 'Defina uma nova senha. As sessões abertas serão encerradas.',
  );
  protected readonly submitLabel = computed(() => (this.purpose() === 'activation' ? 'Ativar conta' : 'Redefinir senha'));

  ngOnInit(): void {
    // remove o token da barra de endereços e do histórico do navegador
    if (this.actionToken) {
      void this.router.navigate([], { relativeTo: this.route, queryParams: {}, fragment: undefined, replaceUrl: true });
    }
  }

  protected submit(): void {
    if (!this.actionToken || this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.submitting.set(true);
    this.api
      .completeCredentialAction(this.actionToken, this.form.controls.newPassword.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () =>
          void this.router.navigate([AUTH_ROUTES.login], {
            queryParams: { motivo: this.purpose() === 'activation' ? 'conta-ativada' : 'senha-redefinida' },
          }),
        error: (err: unknown) => {
          const apiError = toApiError(err);
          applyServerErrors(this.form, apiError, { newPassword: 'newPassword' });
          this.error.set(
            apiError.status === 401 || apiError.status === 404
              ? 'Link inválido ou expirado. Solicite um novo link.'
              : authErrorMessage(apiError),
          );
        },
      });
  }
}

/**
 * Token do link do e-mail: `#token=<valor>`. Links emitidos antes da mudança (`?token=`, válidos por até
 * 72 h) continuam aceitos; o token sai do endereço em seguida.
 */
export function credentialToken(route: ActivatedRoute): string | null {
  const fragment = route.snapshot.fragment ?? '';
  const fromFragment = new URLSearchParams(fragment).get('token');
  return fromFragment || route.snapshot.queryParamMap.get('token');
}
