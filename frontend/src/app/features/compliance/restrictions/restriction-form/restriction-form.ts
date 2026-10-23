import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { endOfDayInstant, startOfDayInstant } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators } from '../../../../shared/validation/validators';
import { DebtorLookup } from '../../../debt/data/debt.lookups';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { RestrictionsApi } from '../../data/compliance.api';
import { CHANNEL_LABELS, MANUAL_RESTRICTION_SOURCES, RESTRICTION_SOURCE_LABELS, RESTRICTION_TYPE_LABELS } from '../../data/compliance.labels';
import { ContactChannel, CreateRestrictionRequest, RestrictionType, RestrictionView } from '../../data/compliance.models';

export interface RestrictionFormData {
  /** Devedor já conhecido (ficha); nulo = escolher na busca. */
  debtorId: string | null;
  debtorName?: string;
}

const REASON_MAX = 1000;

/**
 * Nova restrição de contato. O valor de contato específico vai só no corpo da requisição e volta
 * mascarado; sem credor = todos os credores (exige acesso a todos), sem canal = todos os canais.
 */
@Component({
  selector: 'app-restriction-form',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    EntityPicker,
  ],
  template: `
    <h2 mat-dialog-title>Nova restrição de contato</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        @if (data.debtorId) {
          <p class="app-dialog-message">Devedor: <strong>{{ data.debtorName }}</strong></p>
        } @else {
          <app-entity-picker
            [control]="form.controls.debtor"
            [source]="debtorSource"
            label="Devedor"
            hint="Nome ou CPF/CNPJ completo."
            [single]="true"
          />
          @if (form.controls.debtor.touched && form.controls.debtor.invalid) {
            <p class="app-field-error" role="alert">Escolha o devedor.</p>
          }
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Tipo</mat-label>
            <mat-select formControlName="type">
              @for (t of types; track t.value) {
                <mat-option [value]="t.value">{{ t.label }}</mat-option>
              }
            </mat-select>
            @if (form.controls.type.invalid) {
              <mat-error>{{ message('type') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Origem do pedido</mat-label>
            <mat-select formControlName="source">
              @for (s of sources; track s) {
                <mat-option [value]="s">{{ sourceLabels[s] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <div class="app-span-all">
            <app-entity-picker
              [control]="form.controls.creditor"
              [source]="creditorSource"
              label="Credor"
              hint="Vazio = vale para todos os credores (exige acesso a todos)."
              [single]="true"
            />
          </div>
          <mat-form-field>
            <mat-label>Canal</mat-label>
            <mat-select formControlName="channel">
              <mat-option value="">Todos os canais</mat-option>
              @for (c of channels; track c.value) {
                <mat-option [value]="c.value">{{ c.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-slide-toggle formControlName="specific" class="restriction-form__toggle">Somente um telefone ou e-mail</mat-slide-toggle>
          @if (specific()) {
            <mat-form-field>
              <mat-label>Tipo do contato</mat-label>
              <mat-select formControlName="contactType">
                <mat-option value="PHONE">Telefone</mat-option>
                <mat-option value="EMAIL">E-mail</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ form.controls.contactType.value === 'EMAIL' ? 'E-mail' : 'Telefone com DDD' }}</mat-label>
              <input matInput formControlName="contactValue" autocomplete="off" maxlength="254" />
              @if (form.controls.contactValue.invalid) {
                <mat-error>{{ message('contactValue') }}</mat-error>
              }
            </mat-form-field>
          }
          <mat-form-field>
            <mat-label>Início</mat-label>
            <input matInput [matDatepicker]="start" formControlName="startsAt" placeholder="Hoje" />
            <mat-datepicker-toggle matIconSuffix [for]="start" />
            <mat-datepicker #start />
            <mat-hint>Vazio = a partir de agora.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Fim (restrição temporária)</mat-label>
            <input matInput [matDatepicker]="end" formControlName="endsAt" [min]="today" />
            <mat-datepicker-toggle matIconSuffix [for]="end" />
            <mat-datepicker #end />
            <mat-hint>Vazio = sem prazo.</mat-hint>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Motivo</mat-label>
            <textarea matInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
            <mat-hint>Descreva o pedido sem copiar dados pessoais. Anexe o comprovante depois de salvar.</mat-hint>
            @if (form.controls.reason.invalid) {
              <mat-error>{{ message('reason') }}</mat-error>
            }
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Registrar restrição</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .restriction-form__toggle {
      align-self: center;
    }
  `,
})
export class RestrictionForm {
  protected readonly data = inject<RestrictionFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<RestrictionForm, RestrictionView>>(MatDialogRef);
  private readonly api = inject(RestrictionsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly types = Object.entries(RESTRICTION_TYPE_LABELS).map(([value, label]) => ({ value: value as RestrictionType, label }));
  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value: value as ContactChannel, label }));
  protected readonly sources = MANUAL_RESTRICTION_SOURCES;
  protected readonly sourceLabels = RESTRICTION_SOURCE_LABELS;
  protected readonly maxReason = REASON_MAX;
  protected readonly today = new Date();
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    debtor: this.fb.control<string[]>(this.data.debtorId ? [this.data.debtorId] : [], [AppValidators.notBlank]),
    type: this.fb.control<RestrictionType | ''>('DEBTOR_REQUEST', [AppValidators.notBlank]),
    source: this.fb.control<(typeof MANUAL_RESTRICTION_SOURCES)[number]>('DEBTOR'),
    creditor: this.fb.control<string[]>([]),
    channel: this.fb.control<ContactChannel | ''>(''),
    specific: [false],
    contactType: this.fb.control<'PHONE' | 'EMAIL'>('PHONE'),
    contactValue: [''],
    startsAt: this.fb.control<Date | null>(null),
    endsAt: this.fb.control<Date | null>(null),
    reason: ['', [AppValidators.notBlank, Validators.maxLength(REASON_MAX)]],
  });
  protected readonly specific = toSignal(this.form.controls.specific.valueChanges, { initialValue: false });

  constructor() {
    this.form.controls.specific.valueChanges.subscribe((on) => {
      const value = this.form.controls.contactValue;
      value.setValidators(on ? [AppValidators.notBlank, Validators.maxLength(254)] : []);
      value.updateValueAndValidity();
    });
    // O tipo do contato acompanha o canal escolhido (WhatsApp/SMS/telefone → telefone; e-mail → e-mail).
    this.form.controls.channel.valueChanges.subscribe((channel) => {
      if (channel === 'EMAIL') {
        this.form.controls.contactType.setValue('EMAIL');
      } else if (channel && channel !== 'PORTAL') {
        this.form.controls.contactType.setValue('PHONE');
      }
    });
  }

  protected message(field: 'type' | 'contactValue' | 'reason'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: CreateRestrictionRequest = {
      debtorId: v.debtor[0],
      creditorId: v.creditor[0] ?? null,
      channel: v.channel || null,
      type: v.type as RestrictionType,
      contactType: v.specific ? v.contactType : null,
      contactValue: v.specific ? v.contactValue.trim() : null,
      reason: v.reason.trim(),
      source: v.source,
      // Hoje (ou antes) = a partir de agora; datas futuras começam à meia-noite do dia escolhido.
      startsAt: v.startsAt && v.startsAt > this.today ? startOfDayInstant(v.startsAt) : null,
      endsAt: endOfDayInstant(v.endsAt),
    };
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .create(request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Restrição registrada. Contatos afetados são bloqueados imediatamente.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
