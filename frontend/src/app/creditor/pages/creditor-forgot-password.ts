import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppValidators } from '../../shared/validation/validators';
import { toApiError } from '../../core/http/problem';
import { CreditorApi } from '../data/creditor.api';
import { creditorAuthMessage } from './creditor-errors';

/** Pedido do link de nova senha: a resposta é sempre a mesma (não revela se o e-mail tem acesso). */
@Component({
  selector: 'app-creditor-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, ...FORM_FIELD],
  template: `
    <section class="app-card cf__card" aria-labelledby="cf-title">
      <h1 class="h4" id="cf-title">Esqueci a senha</h1>
      @if (sent()) {
        <div class="alert alert-success" role="status">
          Se este e-mail tiver acesso ao portal do credor, você vai receber um link para criar uma nova senha. O link vale
          por 1 hora. Seu autenticador continua o mesmo.
        </div>
      } @else {
        @if (error(); as e) {
          <div class="alert alert-danger" role="alert">{{ e }}</div>
        }
        <p>Informe o e-mail do seu acesso. Se perdeu o autenticador, peça à assessoria para refazer o acesso.</p>
        <form [formGroup]="form" (ngSubmit)="send()" novalidate class="app-form-stack">
          <app-field label="E-mail" error="Informe o e-mail.">
            <input appInput type="email" formControlName="email" autocomplete="username" maxlength="200" required />
          </app-field>
          <button class="btn btn-primary" type="submit" [disabled]="busy()">Enviar link</button>
        </form>
      }
      <p class="mt-3 mb-0"><a [routerLink]="['/credor', slug(), 'entrar']">Voltar para a entrada</a></p>
    </section>
  `,
  styles: `
    .cf__card {
      max-width: 440px;
      margin: 0 auto;
    }
  `,
})
export class CreditorForgotPassword {
  private readonly api = inject(CreditorApi);
  protected readonly slug = toSignal(inject(ActivatedRoute).parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), {
    initialValue: '',
  });
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({ email: ['', [AppValidators.notBlank, Validators.email]] });

  protected send(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.busy.set(true);
    this.api
      .forgotPassword(this.slug(), this.form.controls.email.value.trim())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => this.sent.set(true),
        error: (err: unknown) => {
          const apiError = toApiError(err);
          // sem conexão ou limite por IP: o pedido não foi processado — dizer isso não revela se o e-mail tem acesso
          if (apiError.status === 0 || apiError.status === 429) {
            this.error.set(creditorAuthMessage(apiError));
            return;
          }
          this.sent.set(true); // qualquer outra resposta: a mesma mensagem (sem enumeração)
        },
      });
  }
}
