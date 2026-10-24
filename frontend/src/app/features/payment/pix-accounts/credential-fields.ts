import { Component, input, output, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { CredentialsRequest } from '../data/payment.models';
import { CERTIFICATE_ACCEPT, certificateProblem, readAsBase64 } from '../data/payment.rules';

export type CredentialsForm = ReturnType<typeof credentialsForm>;

/** Segredos digitados pelo usuário: nunca preenchidos com valores do servidor (que não os devolve). */
export function credentialsForm(fb: FormBuilder) {
  return fb.nonNullable.group({
    clientId: ['', [Validators.maxLength(500)]],
    clientSecret: ['', [Validators.maxLength(500)]],
    webhookSecret: ['', [Validators.maxLength(500)]],
    certificatePassword: ['', [Validators.maxLength(500)]],
  });
}

export function toCredentials(form: FormGroup, certificateBase64: string | null): CredentialsRequest {
  const v = form.getRawValue() as Record<string, string>;
  const value = (k: string) => (v[k]?.trim() ? v[k].trim() : null);
  return {
    clientId: value('clientId'),
    clientSecret: value('clientSecret'),
    webhookSecret: value('webhookSecret'),
    certificateBase64,
    certificatePassword: certificateBase64 ? (v['certificatePassword'] ?? null) : null,
  };
}

/**
 * Campos de segredo da conta Pix: client_id/client_secret do PSP, segredo HMAC (modo HMAC) e o
 * certificado de cliente PKCS#12 com a senha. O arquivo é lido só em memória e enviado em Base64;
 * nada é guardado no navegador e nenhum valor volta do servidor (só impressão digital e validade).
 */
@Component({
  selector: 'app-credential-fields',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <div class="app-form-grid" [formGroup]="form()">
      <mat-form-field>
        <mat-label>client_id</mat-label>
        <input matInput formControlName="clientId" type="password" autocomplete="new-password" spellcheck="false" />
        <mat-hint>Fornecido pelo PSP.</mat-hint>
      </mat-form-field>
      <mat-form-field>
        <mat-label>client_secret</mat-label>
        <input matInput formControlName="clientSecret" type="password" autocomplete="new-password" spellcheck="false" />
      </mat-form-field>
      @if (hmac()) {
        <mat-form-field>
          <mat-label>Segredo HMAC do webhook</mat-label>
          <input matInput formControlName="webhookSecret" type="password" autocomplete="new-password" spellcheck="false" />
          <mat-hint>Ao menos 32 caracteres; o mesmo cadastrado no PSP.</mat-hint>
        </mat-form-field>
      }
    </div>
    <div class="cf-file" data-tour="pix-certificate">
      <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
      <button mat-stroked-button type="button" (click)="input.click()">
        <mat-icon aria-hidden="true">key</mat-icon>
        {{ fileName() ? 'Trocar certificado' : 'Escolher certificado (.p12/.pfx)' }}
      </button>
      @if (fileName()) {
        <span><strong>{{ fileName() }}</strong> · {{ fileSize() }}</span>
        <button mat-button type="button" (click)="clear()">Remover</button>
      } @else {
        <span class="app-muted">{{ certificateHint() }}</span>
      }
    </div>
    @if (fileError()) {
      <p class="app-field-error" role="alert">{{ fileError() }}</p>
    }
    @if (fileName()) {
      <div class="app-form-grid" [formGroup]="form()">
        <mat-form-field>
          <mat-label>Senha do certificado</mat-label>
          <input matInput formControlName="certificatePassword" type="password" autocomplete="new-password" />
        </mat-form-field>
      </div>
    }
    <p class="app-muted cf-small">
      <mat-icon aria-hidden="true" class="cf-icon">lock</mat-icon>
      Os segredos são gravados criptografados e nunca são exibidos de novo — só a impressão digital e a validade do certificado.
    </p>
  `,
  styles: `
    .cf-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin: 4px 0 8px;
    }
    .cf-small {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: var(--app-text-xs);
    }
    .cf-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
  `,
})
export class CredentialFields {
  readonly form = input.required<CredentialsForm>();
  readonly hmac = input(false);
  readonly certificateHint = input('Obrigatório para a API Pix (mTLS) · até 16 KB');
  /** Conteúdo Base64 do PKCS#12 (ou nulo ao remover). */
  readonly certificate = output<string | null>();

  protected readonly accept = CERTIFICATE_ACCEPT;
  protected readonly fileName = signal<string | null>(null);
  protected readonly fileSize = signal('');
  protected readonly fileError = signal<string | null>(null);

  protected async pick(list: FileList | null): Promise<void> {
    const f = list?.[0] ?? null;
    if (!f) {
      return;
    }
    const problem = certificateProblem(f);
    this.fileError.set(problem);
    if (problem) {
      this.clear();
      this.fileError.set(problem);
      return;
    }
    try {
      const base64 = await readAsBase64(f);
      this.fileName.set(f.name);
      this.fileSize.set(sizeText(f.size));
      this.certificate.emit(base64);
    } catch {
      this.fileError.set('Não foi possível ler o arquivo do certificado.');
    }
  }

  protected clear(): void {
    this.fileName.set(null);
    this.fileError.set(null);
    this.form().controls.certificatePassword.setValue('');
    this.certificate.emit(null);
  }
}
