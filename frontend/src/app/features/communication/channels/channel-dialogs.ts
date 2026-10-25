import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { ChannelsApi } from '../data/communication.api';
import { CHANNEL_LABELS, CREDENTIAL_LABELS, PROVIDER_CHANNELS, PROVIDER_CREDENTIALS, PROVIDER_LABELS } from '../data/communication.labels';
import { AccountView, ChannelType, ProviderType } from '../data/communication.models';

export interface AccountDialogData {
  account: AccountView | null;
}

/** Cadastro e edição de conta de canal. Credenciais só entram; a tela nunca as exibe. */
@Component({
  selector: 'app-account-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ editing ? 'Editar conta de canal' : 'Nova conta de canal' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="row g-3">
          @if (!editing) {
            <app-field class="col-sm-6" label="Provedor">
              <select appInput formControlName="provider" required>
                @for (p of providers; track p.value) {
                  <option [ngValue]="p.value">{{ p.label }}</option>
                }
              </select>
            </app-field>
            <app-field class="col-sm-6" label="Canal">
              <select appInput formControlName="channel" required>
                @for (c of channels(); track c) {
                  <option [ngValue]="c">{{ channelLabels[c] }}</option>
                }
              </select>
            </app-field>
            <div class="col-12">
              <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (vazio = conta padrão da assessoria)" [single]="true" />
            </div>
          }
          <app-field class="col-12" label="Nome da conta">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field class="col-12" [label]="form.controls.channel.value === 'EMAIL' ? 'E-mail do remetente' : 'Identificador do número (WhatsApp) ou remetente'">
            <input appInput formControlName="senderAddress" maxlength="320" required />
          </app-field>
          <app-field class="col-sm-6" label="Nome exibido (opcional)">
            <input appInput formControlName="senderName" maxlength="120" />
          </app-field>
          @if (form.controls.channel.value === 'EMAIL') {
            <app-field class="col-sm-6" label="Responder para (opcional)">
              <input appInput formControlName="replyTo" maxlength="320" />
            </app-field>
          }
          @if (!editing && keys().length) {
            <div class="col-12">
              <h3 class="app-section-title">Credenciais do provedor</h3>
              <p class="app-muted mb-0">Guardadas criptografadas. Depois de salvas, só os nomes das chaves ficam visíveis.</p>
            </div>
            @for (k of keys(); track k) {
              <app-field class="col-12" [label]="credentialLabels[k] ?? k">
                <input appInput type="password" autocomplete="off" [formControl]="credential(k)" required />
              </app-field>
            }
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
export class AccountDialog {
  protected readonly data = inject<AccountDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AccountView>>(DialogRef);
  private readonly api = inject(ChannelsApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly editing = !!this.data.account;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly credentialLabels = CREDENTIAL_LABELS;
  protected readonly providers = (Object.keys(PROVIDER_LABELS) as ProviderType[]).map((value) => ({ value, label: PROVIDER_LABELS[value] }));
  protected readonly saving = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    provider: this.fb.control<ProviderType>(this.data.account?.provider ?? 'WHATSAPP_CLOUD', Validators.required),
    channel: this.fb.control<ChannelType>(this.data.account?.channel ?? 'WHATSAPP', Validators.required),
    creditor: this.fb.control<string[]>(this.data.account?.creditorId ? [this.data.account.creditorId] : []),
    name: [this.data.account?.name ?? '', [Validators.required, Validators.maxLength(120)]],
    senderAddress: [this.data.account?.senderAddress ?? '', [Validators.required, Validators.maxLength(320)]],
    senderName: [this.data.account?.senderName ?? '', Validators.maxLength(120)],
    replyTo: [this.data.account?.replyTo ?? '', Validators.maxLength(320)],
  });
  /** Um controle por chave conhecida de qualquer provedor (o provedor escolhido define quais aparecem). */
  protected readonly credentials = new FormGroup<Record<string, FormControl<string>>>(
    Object.fromEntries(
      [...new Set(Object.values(PROVIDER_CREDENTIALS).flat())].map((k) => [k, new FormControl('', { nonNullable: true, validators: [Validators.required] })]),
    ),
  );

  private readonly provider = toSignal(this.form.controls.provider.valueChanges, { initialValue: this.form.controls.provider.value });
  protected readonly channels = computed(() => PROVIDER_CHANNELS[this.provider()]);
  protected readonly keys = computed(() => PROVIDER_CREDENTIALS[this.provider()]);

  constructor() {
    this.form.controls.provider.valueChanges.subscribe((p) => {
      if (!PROVIDER_CHANNELS[p].includes(this.form.controls.channel.value)) {
        this.form.controls.channel.setValue(PROVIDER_CHANNELS[p][0]);
      }
    });
  }

  protected credential(key: string): FormControl<string> {
    return this.credentials.controls[key];
  }

  protected save(): void {
    const missing = this.keys().some((k) => !this.credential(k).value.trim());
    if (this.form.invalid || (!this.editing && missing) || this.saving()) {
      this.form.markAllAsTouched();
      this.credentials.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const base = { name: v.name.trim(), senderAddress: v.senderAddress.trim(), senderName: v.senderName.trim() || null, replyTo: v.replyTo.trim() || null };
    const call = this.data.account
      ? this.api.update(this.data.account.id, base)
      : this.api.create({
          ...base,
          channel: v.channel,
          provider: v.provider,
          creditorId: v.creditor[0] ?? null,
          credentials: this.keys().length ? Object.fromEntries(this.keys().map((k) => [k, this.credential(k).value.trim()])) : null,
        });
    this.saving.set(true);
    call.pipe(finalize(() => this.saving.set(false))).subscribe((a) => this.ref.close(a));
  }
}

/** Rotação de credenciais: substitui todas as chaves do provedor (os valores antigos não são exibidos). */
@Component({
  selector: 'app-credentials-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Trocar credenciais</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-dialog-message mb-0">Conta {{ data.account!.name }}. Informe os novos valores; os atuais deixam de valer ao salvar.</p>
        @for (k of keys; track k) {
          <app-field [label]="labels[k] ?? k">
            <input appInput type="password" autocomplete="off" [formControl]="controls[k]" required />
          </app-field>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar credenciais</button>
      </div>
    </form>
  `,
})
export class CredentialsDialog {
  protected readonly data = inject<AccountDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AccountView>>(DialogRef);
  private readonly api = inject(ChannelsApi);
  protected readonly labels = CREDENTIAL_LABELS;
  protected readonly keys = PROVIDER_CREDENTIALS[this.data.account!.provider];
  protected readonly controls: Record<string, FormControl<string>> = Object.fromEntries(
    this.keys.map((k) => [k, new FormControl('', { nonNullable: true, validators: [Validators.required] })]),
  );
  protected readonly saving = signal(false);

  protected save(): void {
    if (this.keys.some((k) => !this.controls[k].value.trim()) || this.saving()) {
      Object.values(this.controls).forEach((c) => c.markAsTouched());
      return;
    }
    this.saving.set(true);
    this.api
      .rotateCredentials(this.data.account!.id, Object.fromEntries(this.keys.map((k) => [k, this.controls[k].value.trim()])))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((a) => this.ref.close(a));
  }
}
