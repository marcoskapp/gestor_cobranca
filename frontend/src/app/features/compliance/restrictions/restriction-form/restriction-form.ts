import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { endOfDayInstant, fromLocalDate, startOfDayInstant, toDateStruct, toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators } from '../../../../shared/validation/validators';
import { DebtorLookup } from '../../../debt/data/debt.lookups';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { RestrictionsApi } from '../../data/compliance.api';
import { CHANNEL_LABELS, MANUAL_RESTRICTION_SOURCES, RESTRICTION_SOURCE_LABELS, RESTRICTION_TYPE_LABELS } from '../../data/compliance.labels';
import { ContactChannel, CreateRestrictionRequest, RestrictionType, RestrictionView } from '../../data/compliance.models';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';

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
  imports: [ReactiveFormsModule, EntityPicker, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Nova restrição de contato</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
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
        <div class="app-form-grid gap-3">
          <app-field label="Tipo" [error]="form.controls.type.invalid ? (message('type')) : undefined">
            <select appInput formControlName="type">
              @for (t of types; track t.value) {
                <option [ngValue]="t.value">{{ t.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Origem do pedido">
            <select appInput formControlName="source">
              @for (s of sources; track s) {
                <option [ngValue]="s">{{ sourceLabels[s] }}</option>
              }
            </select>
          </app-field>
          <div class="app-span-all">
            <app-entity-picker
              [control]="form.controls.creditor"
              [source]="creditorSource"
              label="Credor"
              hint="Vazio = vale para todos os credores (exige acesso a todos)."
              [single]="true"
            />
          </div>
          <app-field label="Canal">
            <select appInput formControlName="channel">
              <option value="">Todos os canais</option>
              @for (c of channels; track c.value) {
                <option [ngValue]="c.value">{{ c.label }}</option>
              }
            </select>
          </app-field>
          <div class="form-check form-switch restriction-form__toggle">
            <input class="form-check-input" type="checkbox" role="switch" id="restriction-form-specific" formControlName="specific" />
            <label class="form-check-label" for="restriction-form-specific">Somente um telefone ou e-mail</label>
          </div>
          @if (specific()) {
            <app-field label="Tipo do contato">
              <select appInput formControlName="contactType">
                <option value="PHONE">Telefone</option>
                <option value="EMAIL">E-mail</option>
              </select>
            </app-field>
            <app-field [label]="(form.controls.contactType.value === 'EMAIL' ? 'E-mail' : 'Telefone com DDD')" [error]="form.controls.contactValue.invalid ? (message('contactValue')) : undefined">
              <input appInput formControlName="contactValue" autocomplete="off" maxlength="254" />
            </app-field>
          }
          <app-field label="Início" hint="Vazio = a partir de agora.">
            <div class="input-group">
              <input appInput ngbDatepicker #start="ngbDatepicker" formControlName="startsAt" placeholder="dd/mm/aaaa" />
              <button type="button" class="btn btn-outline-secondary" (click)="start.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Fim (restrição temporária)" hint="Vazio = sem prazo.">
            <div class="input-group">
              <input appInput ngbDatepicker #end="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="endsAt" [minDate]="today" />
              <button type="button" class="btn btn-outline-secondary" (click)="end.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="app-span-all" label="Motivo" hint="Descreva o pedido sem copiar dados pessoais. Anexe o comprovante depois de salvar." [error]="form.controls.reason.invalid ? (message('reason')) : undefined">
            <textarea appInput formControlName="reason" rows="3" [maxlength]="maxReason"></textarea>
          </app-field>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar restrição</button>
      </div>
    </form>
  `,
  styles: `
    .restriction-form__toggle {
      align-self: center;
    }
  `,
})
export class RestrictionForm {
  protected readonly data = inject<RestrictionFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RestrictionView>>(DialogRef);
  private readonly api = inject(RestrictionsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly types = Object.entries(RESTRICTION_TYPE_LABELS).map(([value, label]) => ({ value: value as RestrictionType, label }));
  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value: value as ContactChannel, label }));
  protected readonly sources = MANUAL_RESTRICTION_SOURCES;
  protected readonly sourceLabels = RESTRICTION_SOURCE_LABELS;
  protected readonly maxReason = REASON_MAX;
  private readonly now = new Date();
  protected readonly today = toDateStruct(toLocalDate(this.now)!);
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
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    startsAt: this.fb.control<string | null>(null),
    endsAt: this.fb.control<string | null>(null),
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
      startsAt: v.startsAt && fromLocalDate(v.startsAt)! > this.now ? startOfDayInstant(fromLocalDate(v.startsAt)) : null,
      endsAt: endOfDayInstant(fromLocalDate(v.endsAt)),
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
