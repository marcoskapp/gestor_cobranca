import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { TelephonyAccountsApi, TelephonyRoutingApi } from '../data/telephony.api';
import {
  PROVIDER_LABELS,
  PUBLIC_CREDENTIAL_KEYS,
  READINESS_LABELS,
  ROUTE_SCOPE_LABELS,
  ROUTE_SCOPE_ORDER,
  TELEPHONY_CREDENTIAL_LABELS,
  TELEPHONY_ERROR_LABELS,
} from '../data/telephony.labels';
import { CampaignView, RouteScope, RouteView, TelephonyAccountView, TelephonyProvider, TelephonyProviderView } from '../data/telephony.models';

function errorText(err: unknown): string {
  const e = toApiError(err);
  return (e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e);
}

/** Tarifa digitada com vírgula ou ponto; vazio = sem tarifa. */
export function parseRate(value: string): number | null | undefined {
  const text = value.trim().replace(',', '.');
  if (!text) {
    return null;
  }
  if (!/^\d{1,3}(\.\d{1,4})?$/.test(text)) {
    return undefined;
  }
  const rate = Number(text);
  return rate <= 100 ? rate : undefined;
}

export interface TelephonyAccountDialogData {
  account: TelephonyAccountView | null;
  providers: TelephonyProviderView[];
}

/**
 * Cadastro e edição de conta de telefonia. Credenciais só entram (nunca são exibidas). Provedor
 * com integração indisponível não aparece; com contrato a confirmar, a conta nasce inativa.
 */
@Component({
  selector: 'app-telephony-account-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ editing ? 'Editar conta de telefonia' : 'Nova conta de telefonia' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="row g-3">
          @if (!editing) {
            <app-field class="col-sm-6" label="Plataforma">
              <select appInput formControlName="provider" required>
                @for (p of available; track p.provider) {
                  <option [ngValue]="p.provider">{{ labels[p.provider] }}</option>
                }
              </select>
            </app-field>
            <div class="col-sm-6 d-flex align-items-end">
              @if (selected(); as p) {
                <p class="app-muted small mb-2">{{ readiness[p.readiness] }}{{ p.readiness === 'CONTRACT_PENDING' && !p.dialable ? ': a conta fica inativa até a homologação.' : '' }}</p>
              }
            </div>
            <div class="col-12">
              <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (vazio = conta da assessoria; preenchido = só este credor usa)" [single]="true" />
            </div>
          }
          <app-field class="col-sm-7" label="Nome da conta" error="Informe o nome.">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field class="col-sm-5" label="Número de origem (bina)" hint="E.164, ex.: +551130000000" error="8 a 15 dígitos.">
            <input appInput formControlName="callerId" maxlength="20" required autocomplete="off" />
          </app-field>
          <app-field class="col-sm-6" label="Tarifa por minuto, em reais (opcional)" hint="Só para comparar o custo estimado entre plataformas" error="De 0 a 100, até 4 casas.">
            <!-- not-money: tarifa com até 4 casas decimais (fração de centavo), apenas informativa -->
            <input appInput formControlName="rate" inputmode="decimal" maxlength="9" autocomplete="off" />
          </app-field>
          <div class="col-12 d-grid gap-1">
            <div class="form-check">
              <input class="form-check-input" type="checkbox" id="ta-rec" formControlName="recordingEnabled" />
              <label class="form-check-label" for="ta-rec">Gravar as chamadas</label>
            </div>
            <div class="form-check">
              <input class="form-check-input" type="checkbox" id="ta-notice" formControlName="recordingNotice" />
              <label class="form-check-label" for="ta-notice">Tocar o aviso "esta ligação pode ser gravada" (obrigatório com gravação)</label>
            </div>
          </div>
          @if (!editing && keys().length) {
            <div class="col-12">
              <h3 class="app-section-title">Credenciais da plataforma</h3>
              <p class="app-muted mb-0">Guardadas criptografadas. Depois de salvas, só os nomes das chaves ficam visíveis.</p>
            </div>
            @for (k of keys(); track k) {
              <app-field class="col-12" [label]="credentialLabels[k] ?? k">
                <input appInput [type]="isPublic(k) ? 'text' : 'password'" autocomplete="off" [formControl]="credential(k)" required />
              </app-field>
            }
          }
          @if (error()) {
            <p class="col-12 alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ editing ? 'Salvar' : 'Cadastrar conta' }}</button>
      </div>
    </form>
  `,
})
export class TelephonyAccountDialog {
  protected readonly data = inject<TelephonyAccountDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TelephonyAccountView>>(DialogRef);
  private readonly api = inject(TelephonyAccountsApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly editing = !!this.data.account;
  protected readonly labels = PROVIDER_LABELS;
  protected readonly readiness = READINESS_LABELS;
  protected readonly credentialLabels = TELEPHONY_CREDENTIAL_LABELS;
  protected readonly available = this.data.providers.filter((p) => p.available);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly account = this.data.account;
  protected readonly form = this.fb.group({
    provider: this.fb.control<TelephonyProvider>(this.account?.provider ?? this.available[0]?.provider ?? 'SANDBOX', Validators.required),
    creditor: this.fb.control<string[]>(this.account?.creditorId ? [this.account.creditorId] : []),
    name: [this.account?.name ?? '', [Validators.required, Validators.maxLength(120)]],
    callerId: [this.account?.callerId ?? '', [Validators.required, Validators.pattern(/^\+?\d{8,15}$/)]],
    rate: [this.account?.ratePerMinute === null || this.account?.ratePerMinute === undefined ? '' : String(this.account.ratePerMinute).replace('.', ',')],
    recordingEnabled: [this.account?.recordingEnabled ?? false],
    recordingNotice: [this.account?.recordingNotice ?? false],
  });
  protected readonly credentials = new FormGroup<Record<string, FormControl<string>>>(
    Object.fromEntries(
      [...new Set(this.data.providers.flatMap((p) => p.requiredCredentials))].map((k) => [k, new FormControl('', { nonNullable: true, validators: [Validators.required] })]),
    ),
  );
  private readonly provider = toSignal(this.form.controls.provider.valueChanges, { initialValue: this.form.controls.provider.value });
  protected readonly selected = computed(() => this.data.providers.find((p) => p.provider === this.provider()) ?? null);
  protected readonly keys = computed(() => this.selected()?.requiredCredentials ?? []);

  constructor() {
    this.form.controls.recordingEnabled.valueChanges.subscribe((on) => {
      if (on) {
        this.form.controls.recordingNotice.setValue(true);
      }
    });
  }

  protected credential(key: string): FormControl<string> {
    return this.credentials.controls[key];
  }

  protected isPublic(key: string): boolean {
    return PUBLIC_CREDENTIAL_KEYS.includes(key);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const rate = parseRate(v.rate);
    if (rate === undefined) {
      this.form.controls.rate.setErrors({ rate: true });
    }
    const missing = !this.editing && this.keys().some((k) => !this.credential(k).value.trim());
    if (this.form.invalid || missing || rate === undefined || this.saving()) {
      this.form.markAllAsTouched();
      this.credentials.markAllAsTouched();
      return;
    }
    if (v.recordingEnabled && !v.recordingNotice) {
      this.error.set(TELEPHONY_ERROR_LABELS['telephony_account.recording_notice_required']);
      return;
    }
    const base = { name: v.name.trim(), callerId: v.callerId.trim(), recordingEnabled: v.recordingEnabled, recordingNotice: v.recordingNotice, ratePerMinute: rate };
    const call = this.account
      ? this.api.update(this.account.id, base)
      : this.api.create({
          ...base,
          provider: v.provider,
          creditorId: v.creditor[0] ?? null,
          credentials: Object.fromEntries(this.keys().map((k) => [k, this.credential(k).value.trim()])),
        });
    this.saving.set(true);
    this.error.set(null);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (a) => this.ref.close(a),
      error: (err: unknown) => this.error.set(errorText(err)),
    });
  }
}

export interface TelephonyCredentialsDialogData {
  account: TelephonyAccountView;
  keys: string[];
}

/** Troca de credenciais: substitui todas as chaves da plataforma (os valores antigos não são exibidos). */
@Component({
  selector: 'app-telephony-credentials-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Trocar credenciais</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-dialog-message mb-0">Conta {{ data.account.name }}. Informe os novos valores; os atuais deixam de valer ao salvar.</p>
        @for (k of data.keys; track k) {
          <app-field [label]="labels[k] ?? k">
            <input appInput [type]="isPublic(k) ? 'text' : 'password'" autocomplete="off" [formControl]="controls[k]" required />
          </app-field>
        }
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar credenciais</button>
      </div>
    </form>
  `,
})
export class TelephonyCredentialsDialog {
  /** Agrupa o formulário (envio por ngSubmit). */
  protected readonly form = new FormGroup({});
  protected readonly data = inject<TelephonyCredentialsDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TelephonyAccountView>>(DialogRef);
  private readonly api = inject(TelephonyAccountsApi);
  protected readonly labels = TELEPHONY_CREDENTIAL_LABELS;
  protected readonly controls: Record<string, FormControl<string>> = Object.fromEntries(
    this.data.keys.map((k) => [k, new FormControl('', { nonNullable: true, validators: [Validators.required] })]),
  );
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected isPublic(key: string): boolean {
    return PUBLIC_CREDENTIAL_KEYS.includes(key);
  }

  protected save(): void {
    if (this.data.keys.some((k) => !this.controls[k].value.trim()) || this.saving()) {
      Object.values(this.controls).forEach((c) => c.markAsTouched());
      return;
    }
    this.saving.set(true);
    this.api
      .rotateCredentials(this.data.account.id, Object.fromEntries(this.data.keys.map((k) => [k, this.controls[k].value.trim()])))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({ next: (a) => this.ref.close(a), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}

export interface RouteDialogData {
  route: RouteView | null;
  accounts: TelephonyAccountView[];
  campaigns: CampaignView[];
}

/**
 * Regra de roteamento: escopo (campanha, carteira, credor ou padrão), conta principal e
 * contingência. Salvar num escopo que já tem regra substitui a regra.
 */
@Component({
  selector: 'app-route-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.route ? 'Editar regra de roteamento' : 'Nova regra de roteamento' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <app-field label="Vale para">
          <select appInput formControlName="scopeType" required>
            @for (s of scopes; track s) {
              <option [ngValue]="s">{{ scopeLabels[s] }}</option>
            }
          </select>
        </app-field>
        @switch (scope()) {
          @case ('CREDITOR') {
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
          }
          @case ('PORTFOLIO') {
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" />
          }
          @case ('CAMPAIGN') {
            <app-field label="Campanha de discagem">
              <select appInput formControlName="campaign" required>
                <option [ngValue]="null">Escolha a campanha</option>
                @for (c of data.campaigns; track c.id) {
                  <option [ngValue]="c.id">{{ c.name }} ({{ c.code }})</option>
                }
              </select>
            </app-field>
          }
        }
        <app-field label="Conta principal" hint="Disca as chamadas deste escopo.">
          <select appInput formControlName="primary" required>
            <option [ngValue]="null">Escolha a conta</option>
            @for (a of data.accounts; track a.id) {
              <option [ngValue]="a.id">{{ accountLabel(a) }}</option>
            }
          </select>
        </app-field>
        <app-field label="Contingência (opcional)" hint="Assume se a principal estiver inativa ou fora do ar antes de discar; nunca disca a mesma chamada duas vezes.">
          <select appInput formControlName="fallback">
            <option [ngValue]="null">Sem contingência</option>
            @for (a of data.accounts; track a.id) {
              @if (a.id !== form.controls.primary.value) {
                <option [ngValue]="a.id">{{ accountLabel(a) }}</option>
              }
            }
          </select>
        </app-field>
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar regra</button>
      </div>
    </form>
  `,
})
export class RouteDialog {
  protected readonly data = inject<RouteDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RouteView>>(DialogRef);
  private readonly api = inject(TelephonyRoutingApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly portfolios = inject(PortfolioLookup).source();
  protected readonly scopes = ROUTE_SCOPE_ORDER;
  protected readonly scopeLabels = ROUTE_SCOPE_LABELS;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly route = this.data.route;
  protected readonly form = this.fb.group({
    scopeType: this.fb.control<RouteScope>(this.route?.scopeType ?? 'CREDITOR', Validators.required),
    creditor: this.fb.control<string[]>(this.route?.scopeType === 'CREDITOR' && this.route.scopeId ? [this.route.scopeId] : []),
    portfolio: this.fb.control<string[]>(this.route?.scopeType === 'PORTFOLIO' && this.route.scopeId ? [this.route.scopeId] : []),
    campaign: this.fb.control<string | null>(this.route?.scopeType === 'CAMPAIGN' ? this.route.scopeId : null),
    primary: this.fb.control<string | null>(this.route?.primaryAccountId ?? null, Validators.required),
    fallback: this.fb.control<string | null>(this.route?.fallbackAccountId ?? null),
  });
  protected readonly scope = toSignal(this.form.controls.scopeType.valueChanges, { initialValue: this.form.controls.scopeType.value });

  protected accountLabel(a: TelephonyAccountView): string {
    return `${a.name} — ${PROVIDER_LABELS[a.provider]}${a.active ? '' : ' (inativa)'}`;
  }

  private scopeId(): string | null {
    const v = this.form.getRawValue();
    switch (v.scopeType) {
      case 'CREDITOR':
        return v.creditor[0] ?? null;
      case 'PORTFOLIO':
        return v.portfolio[0] ?? null;
      case 'CAMPAIGN':
        return v.campaign;
      default:
        return null;
    }
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const scopeId = this.scopeId();
    if (this.form.invalid || !v.primary || (v.scopeType !== 'DEFAULT' && !scopeId) || this.saving()) {
      this.form.markAllAsTouched();
      this.error.set(v.scopeType !== 'DEFAULT' && !scopeId ? 'Escolha o ' + ROUTE_SCOPE_LABELS[v.scopeType].toLowerCase() + '.' : null);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .save({ scopeType: v.scopeType, scopeId, primaryAccountId: v.primary, fallbackAccountId: v.fallback === v.primary ? null : v.fallback })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({ next: (r) => this.ref.close(r), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}
