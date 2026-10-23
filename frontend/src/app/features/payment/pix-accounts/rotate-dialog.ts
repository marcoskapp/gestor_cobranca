import { Component, inject, signal } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
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
  imports: [MatDialogModule, MatButtonModule, MatIconModule, AppDatePipe, CredentialFields],
  template: `
    <h2 mat-dialog-title>Rotacionar credenciais · {{ data.account.name }}</h2>
    <mat-dialog-content>
      <p class="app-alert app-alert--warning" role="note">
        <mat-icon aria-hidden="true">sync_lock</mat-icon>
        <span>A rotação <strong>substitui todos os segredos</strong>: informe de novo o client_id, o client_secret{{ data.account.webhookAuth === 'HMAC' ? ' e o segredo HMAC' : '' }}. Sem um novo arquivo, o certificado atual é mantido.</span>
      </p>
      @if (data.account.certificateSha256) {
        <dl class="app-definition-list rt-current">
          <dt>Certificado atual</dt>
          <dd class="app-mono rt-break">{{ fingerprint(data.account.certificateSha256) }}</dd>
          <dt>Válido até</dt>
          <dd>{{ data.account.certificateExpiresAt | appDate }}</dd>
        </dl>
      }
      @if (error()) {
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }
      <app-credential-fields [form]="form" [hmac]="data.account.webhookAuth === 'HMAC'" certificateHint="Opcional: envie só para trocar o certificado" (certificate)="certificate.set($event)" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancelar</button>
      <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">Rotacionar</button>
    </mat-dialog-actions>
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
  protected readonly data = inject<RotateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<RotateDialog, PixAccountView>>(MatDialogRef);
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
