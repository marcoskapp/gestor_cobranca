import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
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
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ editing ? 'Editar conta de canal' : 'Nova conta de canal' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content class="app-form-stack">
        @if (!editing) {
          <div class="ch-row">
            <mat-form-field>
              <mat-label>Provedor</mat-label>
              <mat-select formControlName="provider" required>
                @for (p of providers; track p.value) {
                  <mat-option [value]="p.value">{{ p.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Canal</mat-label>
              <mat-select formControlName="channel" required>
                @for (c of channels(); track c) {
                  <mat-option [value]="c">{{ channelLabels[c] }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          </div>
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (vazio = conta padrão da assessoria)" [single]="true" />
        }
        <mat-form-field>
          <mat-label>Nome da conta</mat-label>
          <input matInput formControlName="name" maxlength="120" required />
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ form.controls.channel.value === 'EMAIL' ? 'E-mail do remetente' : 'Identificador do número (WhatsApp) ou remetente' }}</mat-label>
          <input matInput formControlName="senderAddress" maxlength="320" required />
        </mat-form-field>
        <div class="ch-row">
          <mat-form-field>
            <mat-label>Nome exibido (opcional)</mat-label>
            <input matInput formControlName="senderName" maxlength="120" />
          </mat-form-field>
          @if (form.controls.channel.value === 'EMAIL') {
            <mat-form-field>
              <mat-label>Responder para (opcional)</mat-label>
              <input matInput formControlName="replyTo" maxlength="320" />
            </mat-form-field>
          }
        </div>
        @if (!editing && keys().length) {
          <h3 class="app-section-title">Credenciais do provedor</h3>
          <p class="app-muted">Guardadas criptografadas. Depois de salvas, só os nomes das chaves ficam visíveis.</p>
          @for (k of keys(); track k) {
            <mat-form-field>
              <mat-label>{{ credentialLabels[k] ?? k }}</mat-label>
              <input matInput type="password" autocomplete="off" [formControl]="credential(k)" required />
            </mat-form-field>
          }
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ editing ? 'Salvar' : 'Cadastrar conta' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .ch-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 0 12px;
    }
  `,
})
export class AccountDialog {
  protected readonly data = inject<AccountDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AccountDialog, AccountView>>(MatDialogRef);
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
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Trocar credenciais</h2>
    <form (ngSubmit)="save()" novalidate>
      <mat-dialog-content class="app-form-stack">
        <p class="app-dialog-message">Conta {{ data.account!.name }}. Informe os novos valores; os atuais deixam de valer ao salvar.</p>
        @for (k of keys; track k) {
          <mat-form-field>
            <mat-label>{{ labels[k] ?? k }}</mat-label>
            <input matInput type="password" autocomplete="off" [formControl]="controls[k]" required />
          </mat-form-field>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar credenciais</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class CredentialsDialog {
  protected readonly data = inject<AccountDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CredentialsDialog, AccountView>>(MatDialogRef);
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
