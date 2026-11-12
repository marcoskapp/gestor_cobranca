import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators } from '../../../../shared/validation/validators';
import { DebtorContactsApi } from '../../data/debt.api';
import { CONTACT_LABEL_LABELS, CONTACT_PERSON_ROLE_LABELS, CONTACT_TYPE_LABELS, options } from '../../data/debt.labels';
import { ContactLabel, ContactPersonRole, ContactType, ContactView } from '../../data/debt.models';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';

export interface ContactFormData {
  debtorId: string;
  /** Nulo = novo contato. O valor de um contato existente é imutável (outro valor é outro contato). */
  contact: ContactView | null;
}

/** Telefone brasileiro com DDD (10 ou 11 dígitos), com ou sem +55 — o servidor normaliza para E.164. */
export const phoneValidator: ValidatorFn = (control) => {
  const value = String(control.value ?? '').trim();
  if (!value) {
    return null;
  }
  const digits = value.replace(/\D/g, '').replace(/^55(?=\d{10,11}$)/, '');
  return /^[1-9]{2}9?\d{8}$/.test(digits) ? null : { phone: true };
};

type Field = 'type' | 'value' | 'label' | 'priority' | 'source' | 'personName';

@Component({
  selector: 'app-contact-form',
  imports: [ReactiveFormsModule, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.contact ? 'Editar contato' : 'Novo contato' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        @if (data.contact; as c) {
          <p class="app-dialog-message">
            {{ typeLabels[c.type] }} <span class="app-numeric">{{ c.maskedValue }}</span>. Para outro valor, cadastre um novo contato e
            marque este como inválido.
          </p>
        }
        <div class="app-form-grid gap-3">
          @if (!data.contact) {
            <app-field label="Tipo">
              <select appInput formControlName="type">
                @for (t of types; track t.value) {
                  <option [ngValue]="t.value">{{ t.label }}</option>
                }
              </select>
            </app-field>
            <app-field label="Informado por">
              <select appInput formControlName="source">
                <option value="MANUAL">Cadastro manual</option>
                <option value="DEBTOR">O próprio devedor</option>
              </select>
            </app-field>
            <app-field class="app-span-all" [label]="(type() === 'PHONE' ? 'Telefone com DDD' : type() === 'EMAIL' ? 'E-mail' : 'Endereço completo')" [error]="form.controls.value.invalid ? (message('value')) : undefined">
              @if (type() === 'ADDRESS') {
                <textarea appInput formControlName="value" rows="2" maxlength="500"></textarea>
              } @else {
                <input
                  appInput
                  formControlName="value"
                  [type]="type() === 'EMAIL' ? 'email' : 'tel'"
                  [attr.inputmode]="type() === 'PHONE' ? 'tel' : 'email'"
                  autocomplete="off"
                  maxlength="254"
                />
              }
            </app-field>
          }
          <app-field label="Classificação">
            <select appInput formControlName="label">
              @for (l of labels; track l.value) {
                <option [ngValue]="l.value">{{ l.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Prioridade de uso" [hint]="'Menor número é usado primeiro' + (data.contact ? '' : '; vazio = depois dos existentes') + '.'" [error]="form.controls.priority.invalid ? (message('priority')) : undefined">
            <input appInput type="number" formControlName="priority" min="0" max="1000" />
          </app-field>
          @if (type() === 'PHONE') {
            <div class="form-check form-switch app-span-all">
              <input class="form-check-input" type="checkbox" role="switch" id="contact-form-whatsapp" formControlName="whatsapp" />
              <label class="form-check-label" for="contact-form-whatsapp">Número com WhatsApp</label>
            </div>
          }
          <!-- pessoa de contato (B2): com quem se fala neste telefone ou e-mail -->
          <app-field
            label="Pessoa de contato"
            [hint]="data.contact?.maskedPersonName ? 'Atual: ' + data.contact.maskedPersonName + '. Deixe vazio para manter; para remover, use Sem pessoa.' : 'Ex.: Ana, do financeiro. Opcional.'"
            [error]="form.controls.personName.invalid ? (message('personName')) : undefined"
          >
            <input appInput formControlName="personName" maxlength="120" autocomplete="off" />
          </app-field>
          <app-field label="Papel" hint="Função da pessoa na empresa (ou relação com o devedor).">
            <select appInput formControlName="personRole">
              <option [ngValue]="null">Não informado</option>
              @for (r of personRoles; track r.value) {
                <option [ngValue]="r.value">{{ r.label }}</option>
              }
            </select>
          </app-field>
          <div class="form-check form-switch app-span-all">
            <input class="form-check-input" type="checkbox" role="switch" id="contact-form-decision" formControlName="decisionMaker" />
            <label class="form-check-label" for="contact-form-decision">Esta pessoa decide pelo devedor</label>
          </div>
          @if (data.contact?.maskedPersonName) {
            <div class="form-check app-span-all">
              <input class="form-check-input" type="checkbox" id="contact-form-clear-person" formControlName="clearPerson" />
              <label class="form-check-label" for="contact-form-clear-person">Sem pessoa (remover o nome atual)</label>
            </div>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ data.contact ? 'Salvar' : 'Cadastrar contato' }}</button>
      </div>
    </form>
  `,
})
export class ContactForm {
  protected readonly data = inject<ContactFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ContactView>>(DialogRef);
  private readonly api = inject(DebtorContactsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly types = options(CONTACT_TYPE_LABELS);
  protected readonly labels = options(CONTACT_LABEL_LABELS);
  protected readonly typeLabels = CONTACT_TYPE_LABELS;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    type: this.fb.control<ContactType>(this.data.contact?.type ?? 'PHONE'),
    value: [''],
    label: this.fb.control<ContactLabel>(this.data.contact?.label ?? 'MOBILE'),
    whatsapp: [this.data.contact?.whatsapp ?? false],
    priority: this.fb.control<number | null>(this.data.contact?.priority ?? null, [
      ...(this.data.contact ? [Validators.required] : []),
      Validators.min(0),
      Validators.max(1000),
    ]),
    source: this.fb.control<'MANUAL' | 'DEBTOR'>('MANUAL'),
    personName: ['', [Validators.maxLength(120)]],
    personRole: this.fb.control<ContactPersonRole | null>(this.data.contact?.personRole ?? null),
    decisionMaker: [this.data.contact?.decisionMaker ?? false],
    clearPerson: [false],
  });
  protected readonly personRoles = options(CONTACT_PERSON_ROLE_LABELS);
  protected readonly type = toSignal(this.form.controls.type.valueChanges, { initialValue: this.form.controls.type.value });

  constructor() {
    this.applyValueValidators(this.form.controls.type.value);
    this.form.controls.type.valueChanges.subscribe((type) => this.applyValueValidators(type));
  }

  protected message(field: Field): string {
    const errors = this.form.controls[field].errors;
    if (errors?.['phone']) {
      return 'Informe o telefone com DDD (10 ou 11 dígitos).';
    }
    return errorMessage(errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const whatsapp = v.type === 'PHONE' && v.whatsapp;
    const personName = v.personName.trim();
    const request = this.data.contact
      ? this.api.update(this.data.debtorId, this.data.contact.id, {
          label: v.label,
          whatsapp,
          priority: v.priority ?? 0,
          // a tela só conhece o nome mascarado: vazio mantém o atual; "Sem pessoa" remove
          personName: v.clearPerson ? '' : personName || null,
          personRole: v.personRole,
          decisionMaker: v.decisionMaker,
        })
      : this.api.add(this.data.debtorId, {
          type: v.type,
          value: v.value.trim(),
          label: v.label,
          whatsapp,
          priority: v.priority,
          source: v.source,
          personName: personName || null,
          personRole: v.personRole,
          decisionMaker: v.decisionMaker,
        });
    this.saving.set(true);
    this.generalErrors.set([]);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(this.data.contact ? 'Contato atualizado.' : 'Contato cadastrado.');
        this.ref.close(saved);
      },
      error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
    });
  }

  private applyValueValidators(type: ContactType): void {
    const control = this.form.controls.value;
    if (this.data.contact) {
      control.clearValidators();
    } else {
      const byType = type === 'PHONE' ? [phoneValidator] : type === 'EMAIL' ? [AppValidators.email, Validators.maxLength(254)] : [Validators.maxLength(500)];
      control.setValidators([AppValidators.notBlank, ...byType]);
    }
    control.updateValueAndValidity({ emitEvent: false });
  }
}
