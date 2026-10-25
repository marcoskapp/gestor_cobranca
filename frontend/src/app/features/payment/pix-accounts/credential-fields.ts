import { Component, input, output, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule],
  template: `
    <div class="app-form-grid" [formGroup]="form()">
      <app-field label="client_id" hint="Fornecido pelo PSP.">
        <input appInput formControlName="clientId" type="password" autocomplete="new-password" spellcheck="false" />
      </app-field>
      <app-field label="client_secret">
        <input appInput formControlName="clientSecret" type="password" autocomplete="new-password" spellcheck="false" />
      </app-field>
      @if (hmac()) {
        <app-field label="Segredo HMAC do webhook" hint="Ao menos 32 caracteres; o mesmo cadastrado no PSP.">
          <input appInput formControlName="webhookSecret" type="password" autocomplete="new-password" spellcheck="false" />
        </app-field>
      }
    </div>
    <div class="cf-file" data-tour="pix-certificate">
      <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
      <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="input.click()">
        <app-icon name="key" />
        {{ fileName() ? 'Trocar certificado' : 'Escolher certificado (.p12/.pfx)' }}
      </button>
      @if (fileName()) {
        <span><strong>{{ fileName() }}</strong> · {{ fileSize() }}</span>
        <button class="btn btn-link" type="button" (click)="clear()">Remover</button>
      } @else {
        <span class="app-muted">{{ certificateHint() }}</span>
      }
    </div>
    @if (fileError()) {
      <div class="invalid-feedback d-block" role="alert">{{ fileError() }}</div>
    }
    @if (fileName()) {
      <div class="app-form-grid" [formGroup]="form()">
        <app-field label="Senha do certificado">
          <input appInput formControlName="certificatePassword" type="password" autocomplete="new-password" />
        </app-field>
      </div>
    }
    <p class="app-muted cf-small">
      <app-icon class="cf-icon" name="lock" />
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
