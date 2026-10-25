import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { PixAccountsApi } from '../data/payment.api';
import { PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { PixAccountView, PixProvider, WebhookAuth } from '../data/payment.models';
import { isFingerprint, normalizeFingerprint } from '../data/payment.rules';
import { CredentialFields, credentialsForm, toCredentials } from './credential-fields';

export interface PixAccountDialogData {
  /** Edição dos dados não secretos; ausente = nova conta (com credenciais). */
  account?: PixAccountView;
}

/** Lista de impressões digitais (uma por linha) → normalizadas; nulo se alguma for inválida. */
export function parseFingerprints(text: string): string[] | null {
  const list = text
    .split(/[\n,;]+/)
    .map((f) => f.trim())
    .filter(Boolean);
  return list.every(isFingerprint) ? [...new Set(list.map(normalizeFingerprint))] : null;
}

const HTTPS = /^https:\/\/[^\s/?#@]+(\/[^\s?#]*)?$/i;

/**
 * Cadastro e edição da conta Pix (PSP) de um credor ou da assessoria (sem credor: exige acesso a
 * todos os credores). Na criação vão também os segredos e o certificado; na edição só os dados
 * não secretos — segredos trocam-se pela rotação.
 */
@Component({
  selector: 'app-pix-account-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker, CredentialFields],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" [attr.ngbAutofocus]="editing ? null : ''">{{ editing ? 'Editar conta Pix' : 'Nova conta Pix' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        @if (!editing) {
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" [hint]="allCreditors() ? 'Vazio = conta da assessoria, usada por credores sem conta própria.' : 'Contas da assessoria exigem acesso a todos os credores.'" />
          @if (!allCreditors() && submitted() && !form.controls.creditor.value.length) {
            <div class="invalid-feedback d-block" role="alert">Escolha o credor da conta.</div>
          }
        }
        <div class="app-form-grid">
          <app-field label="Nome" hint="Ex.: Banco X — recebimentos Horizonte">
            <input appInput formControlName="name" maxlength="120" required ngbAutofocus />
          </app-field>
          @if (!editing) {
            <app-field label="Provedor">
              <select appInput formControlName="provider">
                <option value="BACEN_API">API Pix do BACEN (qualquer PSP)</option>
                <option value="SANDBOX">Simulador (só ambiente de teste)</option>
              </select>
            </app-field>
          }
          <app-field label="Chave Pix" hint="Chave da conta que recebe (CNPJ, e-mail, aleatória…).">
            <input appInput formControlName="pixKey" maxlength="77" required spellcheck="false" autocomplete="off" />
          </app-field>
          <app-field label="URL da API" error="Use HTTPS, sem usuário, senha ou parâmetros.">
            <input appInput formControlName="apiBaseUrl" maxlength="500" required spellcheck="false" placeholder="https://" />
          </app-field>
          <app-field label="URL do token (OAuth2)" error="Use HTTPS, sem usuário, senha ou parâmetros.">
            <input appInput formControlName="tokenUrl" maxlength="500" required spellcheck="false" placeholder="https://" />
          </app-field>
        </div>

        <h3 class="app-section-title">Webhook</h3>
        <div class="d-flex flex-wrap gap-2 mb-2" role="group" aria-label="Autenticação do webhook">
          <input type="radio" class="btn-check" formControlName="webhookAuth" value="MTLS" id="pa-webhookAuth-mtls" />
          <label class="btn btn-outline-primary" for="pa-webhookAuth-mtls">mTLS (padrão do Pix)</label>
          <input type="radio" class="btn-check" formControlName="webhookAuth" value="HMAC" id="pa-webhookAuth-hmac" />
          <label class="btn btn-outline-primary" for="pa-webhookAuth-hmac">Assinatura HMAC</label>
        </div>
        @if (auth() === 'MTLS') {
          <app-field class="mb-3" label="Impressões digitais SHA-256 do certificado do PSP" hint='Uma por linha (64 hexadecimais; ":" aceito). Até 10. Só chamadas do PSP com esses certificados são aceitas.' error="Informe ao menos uma impressão digital SHA-256 válida.">
            <textarea appInput formControlName="fingerprints" rows="2" spellcheck="false" class="app-mono"></textarea>
          </app-field>
        } @else {
          <p class="app-muted pa-small">O PSP assina cada notificação com o segredo HMAC informado nas credenciais.</p>
        }

        @if (!editing) {
          <h3 class="app-section-title">Credenciais</h3>
          <app-credential-fields [form]="credentials" [hmac]="auth() === 'HMAC'" (certificate)="certificate.set($event)" />
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ editing ? 'Salvar' : 'Cadastrar conta' }}</button>
      </div>
    </form>
  `,
  styles: `
    .pa-small {
      font-size: var(--app-text-sm);
    }
  `,
})
export class PixAccountDialog {
  protected readonly data = inject<PixAccountDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PixAccountView>>(DialogRef);
  private readonly api = inject(PixAccountsApi);
  private readonly authService = inject(AuthService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly editing = !!this.data.account;
  protected readonly allCreditors = computed(() => this.authService.user()?.allCreditors ?? false);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly certificate = signal<string | null>(null);

  private readonly fb = inject(FormBuilder);
  private readonly url = [AppValidators.notBlank, Validators.maxLength(500), Validators.pattern(HTTPS)];
  protected readonly form = this.fb.nonNullable.group({
    creditor: this.fb.nonNullable.control<string[]>([]),
    name: [this.data.account?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
    provider: this.fb.nonNullable.control<PixProvider>(this.data.account?.provider ?? 'BACEN_API'),
    pixKey: [this.data.account?.pixKey ?? '', [AppValidators.notBlank, Validators.maxLength(77)]],
    apiBaseUrl: [this.data.account?.apiBaseUrl ?? '', this.url],
    tokenUrl: [this.data.account?.tokenUrl ?? '', this.url],
    webhookAuth: this.fb.nonNullable.control<WebhookAuth>(this.data.account?.webhookAuth ?? 'MTLS'),
    fingerprints: [(this.data.account?.webhookFingerprints ?? []).join('\n')],
  });
  protected readonly credentials = credentialsForm(this.fb);
  protected readonly auth = toSignal(this.form.controls.webhookAuth.valueChanges, { initialValue: this.form.controls.webhookAuth.value });

  constructor() {
    this.form.controls.fingerprints.addValidators((c) => {
      if (this.form?.controls.webhookAuth.value !== 'MTLS') {
        return null;
      }
      const list = parseFingerprints(String(c.value ?? ''));
      return !list || !list.length || list.length > 10 ? { fingerprints: true } : null;
    });
    this.form.controls.webhookAuth.valueChanges.subscribe(() => this.form.controls.fingerprints.updateValueAndValidity());
    // o simulador não chama a rede: aceita qualquer endereço
    this.form.controls.provider.valueChanges.subscribe((p) => {
      for (const c of [this.form.controls.apiBaseUrl, this.form.controls.tokenUrl]) {
        c.setValidators(p === 'SANDBOX' ? [AppValidators.notBlank, Validators.maxLength(500)] : this.url);
        c.updateValueAndValidity();
      }
    });
  }

  protected save(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    if (this.form.invalid || this.saving() || (!this.editing && !this.allCreditors() && !v.creditor.length)) {
      this.form.markAllAsTouched();
      return;
    }
    const fingerprints = v.webhookAuth === 'MTLS' ? (parseFingerprints(v.fingerprints) ?? []) : [];
    const base = { name: v.name.trim(), pixKey: v.pixKey.trim(), apiBaseUrl: v.apiBaseUrl.trim(), tokenUrl: v.tokenUrl.trim(), webhookAuth: v.webhookAuth, webhookFingerprints: fingerprints };
    const call = this.editing
      ? this.api.update(this.data.account!.id, base)
      : this.api.create({ ...base, creditorId: v.creditor[0] ?? null, provider: v.provider, credentials: toCredentials(this.credentials, this.certificate()) });
    this.saving.set(true);
    this.errors.set([]);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (account) => {
        this.credentials.reset();
        this.certificate.set(null);
        this.ref.close(account);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.errors.set([(e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e)]);
      },
    });
  }
}
