import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { toApiError } from '../../core/http/problem';
import { NEW_PASSWORD_HINT, PasswordField } from '../../shared/components/password-field/password-field';
import { buildNewPasswordForm } from '../../features/auth/new-password-form';
import { CreditorApi } from '../data/creditor.api';
import { CreditorLinkToken } from '../data/creditor-link-token';
import { creditorAuthMessage, fragmentToken } from './creditor-errors';

/** Nova senha pelo link do e-mail (`#token=`); as sessões abertas são encerradas. */
@Component({
  selector: 'app-creditor-reset-password',
  imports: [ReactiveFormsModule, RouterLink, PasswordField],
  template: `
    <section class="app-card cr2__card" aria-labelledby="cr2-title">
      <h1 class="h4" id="cr2-title">Criar nova senha</h1>
      @if (!token) {
        <div class="alert alert-danger" role="alert">Abra de novo o link do e-mail ou peça um novo em "Esqueci a senha".</div>
      } @else if (done()) {
        <div class="alert alert-success" role="status">Senha alterada. Entre com a nova senha e o código do autenticador.</div>
        <a class="btn btn-primary" [routerLink]="['/credor', slug(), 'entrar']">Entrar</a>
      } @else {
        @if (error(); as e) {
          <div class="alert alert-danger" role="alert">{{ e }}</div>
        }
        <form [formGroup]="form" (ngSubmit)="save()" novalidate class="app-form-stack">
          <app-password-field [control]="form.controls.newPassword" label="Nova senha" autocomplete="new-password" [hint]="hint" />
          <app-password-field [control]="form.controls.confirmation" label="Confirme a nova senha" autocomplete="new-password" />
          @if (form.hasError('mismatch') && form.controls.confirmation.touched) {
            <div class="invalid-feedback d-block" role="alert">As senhas não conferem.</div>
          }
          <button class="btn btn-primary" type="submit" [disabled]="busy()">Salvar nova senha</button>
        </form>
      }
      <p class="mt-3 mb-0"><a [routerLink]="['/credor', slug(), 'entrar']">Ir para a entrada</a></p>
    </section>
  `,
  styles: `
    .cr2__card {
      max-width: 440px;
      margin: 0 auto;
    }
  `,
})
export class CreditorResetPassword implements OnInit {
  private readonly api = inject(CreditorApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly slug = toSignal(this.route.parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });
  protected readonly token: string | null = inject(CreditorLinkToken).take() ?? fragmentToken(this.route.snapshot.fragment);
  protected readonly hint = NEW_PASSWORD_HINT;
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = buildNewPasswordForm(inject(FormBuilder));

  ngOnInit(): void {
    if (this.token) {
      void this.router.navigate([], { relativeTo: this.route, fragment: undefined, replaceUrl: true });
    }
  }

  protected save(): void {
    if (this.form.invalid || !this.token) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.busy.set(true);
    this.api
      .resetPassword(this.token, this.form.controls.newPassword.value)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => this.done.set(true),
        error: (err: unknown) => this.error.set(creditorAuthMessage(toApiError(err))),
      });
  }
}
