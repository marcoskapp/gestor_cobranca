import { Component, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, finalize, map } from 'rxjs';
import { isExpiredStep } from '../../features/auth/auth-errors';
import { toApiError } from '../../core/http/problem';
import { NEW_PASSWORD_HINT, PasswordField } from '../../shared/components/password-field/password-field';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppValidators } from '../../shared/validation/validators';
import { buildNewPasswordForm } from '../../features/auth/new-password-form';
import { CreditorApi } from '../data/creditor.api';
import { CreditorLoginResponse } from '../data/creditor.models';
import { CreditorSession } from '../data/creditor-session';
import { creditorAuthMessage } from './creditor-errors';

type Step = 'credentials' | 'mfa' | 'password';

/**
 * Entrada no portal do credor, em etapas: e-mail e senha → código do autenticador (sempre) → nova senha, se a atual
 * venceu. Os tokens de etapa ficam só em memória desta tela.
 */
@Component({
  selector: 'app-creditor-login',
  imports: [ReactiveFormsModule, RouterLink, PasswordField, ...FORM_FIELD],
  template: `
    <section class="app-card cl__card" aria-labelledby="cl-title">
      <h1 class="h4" id="cl-title" #heading tabindex="-1">{{ title() }}</h1>
      @if (session.endedReason() === 'expired' && step() === 'credentials') {
        <div class="alert alert-info" role="status">Sua sessão terminou. Entre de novo para continuar.</div>
      }
      @if (session.endedReason() === 'logout' && step() === 'credentials') {
        <div class="alert alert-success" role="status">Você saiu do portal do credor.</div>
      }
      @if (error(); as e) {
        <div class="alert alert-danger" role="alert">{{ e }}</div>
      }
      @switch (step()) {
        @case ('credentials') {
          <form [formGroup]="credentials" (ngSubmit)="login()" novalidate class="app-form-stack">
            <app-field label="E-mail" error="Informe o e-mail.">
              <input appInput type="email" formControlName="email" autocomplete="username" maxlength="200" required />
            </app-field>
            <app-password-field [control]="credentials.controls.password" label="Senha" autocomplete="current-password" />
            <button class="btn btn-primary" type="submit" [disabled]="busy()">Continuar</button>
          </form>
          <p class="cl__links"><a [routerLink]="['/credor', slug(), 'esqueci-a-senha']">Esqueci a senha</a></p>
          <p class="app-muted mb-0">Primeiro acesso? Abra o link do convite que chegou no seu e-mail.</p>
        }
        @case ('mfa') {
          <p>Digite o código de 6 dígitos do aplicativo autenticador.</p>
          <form [formGroup]="mfa" (ngSubmit)="verify()" novalidate class="app-form-stack">
            <app-field label="Código do autenticador" error="Digite os 6 dígitos.">
              <input appInput formControlName="code" inputmode="numeric" autocomplete="one-time-code" required
                     (input)="onlyDigits(mfa.controls.code)" />
            </app-field>
            <button class="btn btn-primary" type="submit" [disabled]="busy()">Entrar</button>
            <button class="btn btn-link" type="button" (click)="restart()">Voltar</button>
          </form>
          <p class="app-muted mt-3 mb-0">Sem acesso ao aplicativo autenticador? Peça à assessoria para refazer o seu acesso.</p>
        }
        @case ('password') {
          <p>Sua senha venceu. Crie uma nova para continuar.</p>
          <form [formGroup]="newPassword" (ngSubmit)="changePassword()" novalidate class="app-form-stack">
            <app-password-field [control]="newPassword.controls.newPassword" label="Nova senha" autocomplete="new-password"
                                [hint]="hint" />
            <app-password-field [control]="newPassword.controls.confirmation" label="Confirme a nova senha"
                                autocomplete="new-password" />
            @if (newPassword.hasError('mismatch') && newPassword.controls.confirmation.touched) {
              <div class="invalid-feedback d-block" role="alert">As senhas não conferem.</div>
            }
            <button class="btn btn-primary" type="submit" [disabled]="busy()">Salvar e entrar</button>
          </form>
        }
      }
    </section>
  `,
  styles: `
    .cl__card {
      max-width: 440px;
      margin: 0 auto;
    }
    .cl__links {
      margin: 16px 0 0;
    }
  `,
})
export class CreditorLogin {
  protected readonly session = inject(CreditorSession);
  private readonly api = inject(CreditorApi);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly slug = toSignal(inject(ActivatedRoute).parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), {
    initialValue: '',
  });
  protected readonly step = signal<Step>('credentials');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly hint = NEW_PASSWORD_HINT;
  private stepToken: string | null = null;
  private readonly injector = inject(Injector);
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  protected readonly title = computed(() =>
    this.step() === 'credentials' ? 'Entrar' : this.step() === 'mfa' ? 'Código do autenticador' : 'Nova senha',
  );

  protected readonly credentials = this.fb.group({
    email: ['', [AppValidators.notBlank, Validators.email]],
    password: ['', [AppValidators.notBlank]],
  });
  protected readonly mfa = this.fb.group({ code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]] });
  protected readonly newPassword = buildNewPasswordForm(inject(FormBuilder));

  protected login(): void {
    if (this.credentials.invalid) {
      this.credentials.markAllAsTouched();
      return;
    }
    const v = this.credentials.getRawValue();
    this.call(this.api.login(this.slug(), v.email.trim(), v.password));
  }

  protected verify(): void {
    if (this.mfa.invalid || !this.stepToken) {
      this.mfa.markAllAsTouched();
      return;
    }
    this.call(this.api.verifyMfa(this.stepToken, this.mfa.controls.code.value));
  }

  protected changePassword(): void {
    if (this.newPassword.invalid || !this.stepToken) {
      this.newPassword.markAllAsTouched();
      return;
    }
    this.call(this.api.changeExpiredPassword(this.stepToken, this.newPassword.controls.newPassword.value));
  }

  /** Código colado com espaço ou traço ("123 456"): fica só com os dígitos. */
  protected onlyDigits(control: { value: string; setValue: (v: string) => void }): void {
    const digits = control.value.replace(/\D/g, '').slice(0, 6);
    if (digits !== control.value) {
      control.setValue(digits);
    }
  }

  protected restart(): void {
    this.stepToken = null;
    this.mfa.reset({ code: '' });
    this.error.set(null);
    this.step.set('credentials');
    this.focusStep();
  }

  /** A etapa mudou na mesma tela: o foco vai para o título (teclado e leitor de tela sabem onde estão). */
  private focusStep(): void {
    afterNextRender(() => this.heading().nativeElement.focus(), { injector: this.injector });
  }

  private call(request: Observable<CreditorLoginResponse>): void {
    this.error.set(null);
    this.busy.set(true);
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (result) => this.next(result),
      error: (err: unknown) => {
        const apiError = toApiError(err);
        // código errado ou repetido: a etapa continua valendo (o contador de tentativas é o mesmo da senha)
        if (this.step() === 'mfa' && apiError.status === 401 && !isExpiredStep(apiError)) {
          this.mfa.reset({ code: '' });
          this.error.set('Código inválido. Confira se o horário do celular está automático e digite o código que aparece agora. Depois de 5 tentativas o acesso fica bloqueado por alguns minutos.');
          return;
        }
        // etapa vencida, já usada ou conta que não pode mais entrar: recomeça pelo e-mail e senha
        if (this.step() !== 'credentials' && apiError.status === 401) {
          this.restart();
          this.error.set('A etapa expirou. Entre de novo com e-mail e senha.');
          return;
        }
        this.error.set(creditorAuthMessage(apiError));
      },
    });
  }

  private next(result: CreditorLoginResponse): void {
    if (result.status === 'AUTHENTICATED') {
      this.session.start(result);
      void this.router.navigate(['/credor', this.slug(), 'inicio']);
      return;
    }
    this.stepToken = result.stepToken;
    this.step.set(result.status === 'MFA_REQUIRED' ? 'mfa' : 'password');
    this.focusStep();
  }
}
