import { Component, inject, signal } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { PixAccountsApi } from '../data/payment.api';
import { PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { PixAccountView } from '../data/payment.models';
import { groupedFingerprint } from '../data/payment.rules';
import { CredentialFields, credentialsForm, toCredentials } from './credential-fields';

export interface RotateDialogData {
  account: PixAccountView;
}

/**
 * Rotação das credenciais da conta Pix: substitui o conjunto inteiro de segredos (client_id,
 * client_secret e segredo HMAC); o certificado só é trocado quando um novo arquivo é enviado.
 * O token OAuth2 em cache é descartado pelo servidor.
 */
@Component({
  selector: 'app-rotate-dialog',
  imports: [Icon, ...DIALOG_DIRECTIVES, AppDatePipe, CredentialFields],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Rotacionar credenciais · {{ data.account.name }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
        <app-icon name="shield-lock" />
        <span>A rotação <strong>substitui todos os segredos</strong>: informe de novo o client_id, o client_secret{{ data.account.webhookAuth === 'HMAC' ? ' e o segredo HMAC' : '' }}. Sem um novo arquivo, o certificado atual é mantido.</span>
      </div>
      @if (data.account.certificateSha256) {
        <dl class="app-definition-list rt-current">
          <dt>Certificado atual</dt>
          <dd class="app-mono rt-break">{{ fingerprint(data.account.certificateSha256) }}</dd>
          <dt>Válido até</dt>
          <dd>{{ data.account.certificateExpiresAt | appDate }}</dd>
        </dl>
      }
      @if (error()) {
        <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      <app-credential-fields [form]="form" [hmac]="data.account.webhookAuth === 'HMAC'" certificateHint="Opcional: envie só para trocar o certificado" (certificate)="certificate.set($event)" />
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
      <button class="btn btn-primary" type="button" (click)="save()" [disabled]="saving()">Rotacionar</button>
    </div>
  `,
  styles: `
    .rt-current {
      margin: 0 0 12px;
    }
    .rt-break {
      word-break: break-all;
    }
  `,
})
export class RotateDialog {
  protected readonly data = inject<RotateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PixAccountView>>(DialogRef);
  private readonly api = inject(PixAccountsApi);
  protected readonly form = credentialsForm(inject(FormBuilder));
  protected readonly certificate = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fingerprint = groupedFingerprint;

  protected save(): void {
    if (this.saving()) {
      return;
    }
    const request = toCredentials(this.form, this.certificate());
    if (this.data.account.provider === 'BACEN_API' && (!request.clientId || !request.clientSecret)) {
      this.error.set(PAYMENT_ERROR_LABELS['pix_account.oauth_required']);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .rotateCredentials(this.data.account.id, request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (account) => {
          this.form.reset();
          this.certificate.set(null);
          this.ref.close(account);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
