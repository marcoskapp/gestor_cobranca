import { Component, ElementRef, Injector, OnInit, afterNextRender, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { toApiError } from '../../core/http/problem';
import { NEW_PASSWORD_HINT, PasswordField } from '../../shared/components/password-field/password-field';
import { TotpSetup } from '../../shared/components/totp-setup/totp-setup';
import { FORM_FIELD } from '../../shared/forms/field';
import { buildNewPasswordForm } from '../../features/auth/new-password-form';
import { CreditorApi } from '../data/creditor.api';
import { CreditorLinkToken } from '../data/creditor-link-token';
import { CreditorActivationStart } from '../data/creditor.models';
import { CreditorSession } from '../data/creditor-session';
import { creditorAuthMessage, fragmentToken } from './creditor-errors';

/**
 * Ativação do convite do portal do credor (`/credor/:slug/ativar#token=`): a pessoa cria a senha e cadastra o
 * autenticador na mesma tela; só ao confirmar os dois o acesso é liberado e a sessão abre (decisão 1397). O token do
 * link sai da barra de endereços na hora.
 */
@Component({
  selector: 'app-creditor-activation',
  imports: [ReactiveFormsModule, RouterLink, PasswordField, TotpSetup, ...FORM_FIELD],
  template: `
    <section class="app-card ca__card" aria-labelledby="ca-title">
      <h1 class="h4" id="ca-title">Ativar acesso ao portal do credor</h1>
      @if (!token) {
        <div class="alert alert-danger" role="alert">Abra de novo o link do e-mail do convite. Se ele não funcionar, peça um novo convite à assessoria.</div>
      } @else if (loading()) {
        <p class="app-muted" role="status">Conferindo o convite…</p>
      } @else if (invalid()) {
        <div class="alert alert-danger" role="alert">{{ invalid() }}</div>
        <a [routerLink]="['/credor', slug(), 'entrar']">Ir para a entrada</a>
      } @else if (start(); as s) {
        <p>
          Olá, {{ s.name }}. A assessoria <strong>{{ s.tenantName }}</strong> liberou o portal do credor
          <strong>{{ s.creditorName }}</strong> para a conta {{ s.account }}.
        </p>
        <form [formGroup]="form" (ngSubmit)="complete()" novalidate class="app-form-stack">
          <h2 class="h6 mb-0">1. Crie a senha</h2>
          <app-password-field [control]="form.controls.newPassword" label="Senha" autocomplete="new-password" [hint]="hint" />
          <app-password-field [control]="form.controls.confirmation" label="Confirme a senha" autocomplete="new-password" />
          @if (form.hasError('mismatch') && form.controls.confirmation.touched) {
            <div class="invalid-feedback d-block" role="alert">As senhas não conferem.</div>
          }
          <h2 class="h6 mb-0 mt-2">2. Cadastre o autenticador</h2>
          <p class="app-muted mb-0">O código do aplicativo será pedido sempre que você entrar.</p>
          <app-totp-setup [otpauthUri]="s.otpAuthUri" [secret]="s.secret" />
          <app-field label="Código do autenticador" error="Digite os 6 dígitos.">
            <input appInput #codeInput [formControl]="code" inputmode="numeric" autocomplete="one-time-code" required
                   (input)="onlyDigits()" />
          </app-field>
          @if (error(); as e) {
            <div class="alert alert-danger mb-0" role="alert">{{ e }}</div>
          }
          <button class="btn btn-primary" type="submit" [disabled]="busy()">Ativar e entrar</button>
        </form>
      }
    </section>
  `,
  styles: `
    .ca__card {
      max-width: 520px;
      margin: 0 auto;
    }
  `,
})
export class CreditorActivation implements OnInit {
  private readonly api = inject(CreditorApi);
  private readonly session = inject(CreditorSession);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly slug = toSignal(this.route.parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });
  protected readonly token: string | null = inject(CreditorLinkToken).take() ?? fragmentToken(this.route.snapshot.fragment);
  protected readonly hint = NEW_PASSWORD_HINT;
  private readonly injector = inject(Injector);
  private readonly codeInput = viewChild<ElementRef<HTMLInputElement>>('codeInput');
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly start = signal<CreditorActivationStart | null>(null);
  protected readonly invalid = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly form = buildNewPasswordForm(inject(FormBuilder));
  protected readonly code = inject(FormBuilder).nonNullable.control('', [Validators.required, Validators.pattern(/^\d{6}$/)]);

  ngOnInit(): void {
    if (!this.token) {
      this.loading.set(false);
      return;
    }
    // tira o token da barra de endereços e do histórico (fica só na memória desta tela)
    void this.router.navigate([], { relativeTo: this.route, fragment: undefined, replaceUrl: true });
    this.api
      .startActivation(this.token)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (s) => this.start.set(s),
        error: (err: unknown) =>
          this.invalid.set(creditorAuthMessage(toApiError(err), 'Link inválido ou expirado. Peça um novo convite à assessoria.')),
      });
  }

  protected complete(): void {
    if (this.form.invalid || this.code.invalid || !this.token) {
      this.form.markAllAsTouched();
      this.code.markAsTouched();
      return;
    }
    this.error.set(null);
    this.busy.set(true);
    this.api
      .completeActivation(this.token, this.form.controls.newPassword.value, this.code.value)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (result) => {
          this.session.start(result);
          void this.router.navigate(['/credor', this.slug(), 'inicio']);
        },
        error: (err: unknown) => {
          // o código muda a cada 30 s: limpa e volta o foco para ele
          this.code.reset('');
          this.error.set(creditorAuthMessage(toApiError(err)));
          afterNextRender(() => this.codeInput()?.nativeElement.focus(), { injector: this.injector });
        },
      });
  }

  /** Código colado com espaço ou traço ("123 456"): fica só com os dígitos. */
  protected onlyDigits(): void {
    const digits = this.code.value.replace(/\D/g, '').slice(0, 6);
    if (digits !== this.code.value) {
      this.code.setValue(digits);
    }
  }
}
