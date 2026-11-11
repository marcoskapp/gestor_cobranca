import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize, startWith } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toLocalDate } from '../../../shared/models/dates';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { DataProcessorsApi } from '../data/compliance.api';
import { DataProcessorDetails, DataProcessorView, ProcessorRoleCode, RecipientInUseView, TransferSafeguardCode } from '../data/compliance.models';
import { COMMON_COUNTRIES, PROCESSOR_ROLE_LABELS, PURPOSE_SUGGESTIONS, TRANSFER_SAFEGUARD_LABELS, countryName } from '../data/data-sharing';

/** Cadastro novo (a partir de um prestador em uso ou manual) ou edição de um existente. */
export interface ProcessorFormData {
  processor?: DataProcessorView;
  /** Prestador configurado ainda sem cadastro: chave, nome e finalidade já preenchidos. */
  suggestion?: RecipientInUseView;
}

const OTHER_COUNTRY = 'OUTRO';

/** Identificador de um operador cadastrado à mão (ex.: hospedagem): `custom.<nome>`, sem acentos. */
export function customKey(name: string): string {
  const slug = name
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 60);
  return `custom.${slug || 'operador'}`;
}

type Field = 'name' | 'purpose' | 'dataCategories' | 'countryCode' | 'otherCountry' | 'transferSafeguard' | 'contractReference' | 'validFrom';

/**
 * Formulário do operador/suboperador: papel, finalidade, categorias de dados, país (com a garantia do art. 33 fora do
 * Brasil), contrato e vigência. O identificador do provedor vem do sistema e não é digitado.
 */
@Component({
  selector: 'app-processor-form',
  imports: [ReactiveFormsModule, NgbInputDatepicker, Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ editing ? 'Editar operador' : 'Cadastrar operador' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          @if (data.suggestion; as s) {
            Prestador em uso em <strong>{{ s.source }}</strong>. Confira o nome da empresa contratada e o contrato.
          } @else {
            Quem trata dados dos devedores em nome da assessoria. O nome e a finalidade aparecem na resposta ao titular.
          }
        </p>
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          <app-field class="app-span-all" label="Empresa" hint="Razão social ou nome comercial do prestador." [error]="form.controls.name.invalid ? message('name') : undefined">
            <input appInput formControlName="name" maxlength="150" />
          </app-field>
          <app-field label="Papel" hint="Suboperador: contratado pela assessoria enquanto ela é operadora do credor.">
            <select appInput formControlName="role">
              @for (r of roles; track r.value) {
                <option [ngValue]="r.value">{{ r.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Contrato ou DPA" hint="Referência curta (sem anexos)." [error]="form.controls.contractReference.invalid ? message('contractReference') : undefined">
            <input appInput formControlName="contractReference" maxlength="120" placeholder="Ex.: DPA 2026/03" />
          </app-field>
          <app-field class="app-span-all" label="Finalidade" hint="Em linguagem simples: vai para a resposta ao titular." [error]="form.controls.purpose.invalid ? message('purpose') : undefined">
            <input appInput formControlName="purpose" maxlength="300" />
          </app-field>
          <app-field class="app-span-all" label="Dados tratados" hint="Categorias, sem exemplos reais (ex.: nome, telefone e mensagens)." [error]="form.controls.dataCategories.invalid ? message('dataCategories') : undefined">
            <textarea appInput formControlName="dataCategories" rows="2" maxlength="300"></textarea>
          </app-field>
          <app-field label="País do tratamento" [error]="form.controls.countryCode.invalid ? message('countryCode') : undefined">
            <select appInput formControlName="countryCode">
              @for (c of countries; track c.value) {
                <option [ngValue]="c.value">{{ c.label }}</option>
              }
              <option [ngValue]="other">Outro país</option>
            </select>
          </app-field>
          @if (otherCountry()) {
            <app-field label="Código do país" hint="Duas letras (ISO), ex.: CL." [error]="form.controls.otherCountry.invalid ? message('otherCountry') : undefined">
              <input appInput formControlName="otherCountry" maxlength="2" autocomplete="off" />
            </app-field>
          }
          @if (international()) {
            <app-field class="app-span-all" label="Garantia da transferência internacional" hint="LGPD art. 33: obrigatória fora do Brasil." [error]="form.controls.transferSafeguard.invalid ? message('transferSafeguard') : undefined">
              <select appInput formControlName="transferSafeguard">
                <option [ngValue]="null" disabled>Selecione</option>
                @for (g of safeguards; track g.value) {
                  <option [ngValue]="g.value">{{ g.label }}</option>
                }
              </select>
            </app-field>
          }
          <app-field label="Início do contrato" [error]="form.controls.validFrom.invalid ? message('validFrom') : undefined">
            <div class="input-group">
              <input appInput ngbDatepicker #from="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="validFrom" />
              <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário do início"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
          <app-field label="Fim do contrato" hint="Vazio = sem prazo.">
            <div class="input-group">
              <input appInput ngbDatepicker #until="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="validUntil" />
              <button type="button" class="btn btn-outline-secondary" (click)="until.toggle()" aria-label="Abrir calendário do fim"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
          <div class="form-check form-switch app-span-all">
            <input class="form-check-input" type="checkbox" role="switch" id="processor-active" formControlName="active" />
            <label class="form-check-label" for="processor-active">Ativo (contrato em vigor)</label>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ editing ? 'Salvar alterações' : 'Cadastrar operador' }}</button>
      </div>
    </form>
  `,
})
export class ProcessorForm {
  protected readonly data = inject<ProcessorFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DataProcessorView>>(DialogRef);
  private readonly api = inject(DataProcessorsApi);
  private readonly notifications = inject(NotificationService);

  protected readonly editing = !!this.data.processor;
  protected readonly other = OTHER_COUNTRY;
  protected readonly roles = Object.entries(PROCESSOR_ROLE_LABELS).map(([value, label]) => ({ value: value as ProcessorRoleCode, label }));
  protected readonly safeguards = Object.entries(TRANSFER_SAFEGUARD_LABELS).map(([value, label]) => ({ value: value as TransferSafeguardCode, label }));
  protected readonly countries = COMMON_COUNTRIES.map((value) => ({ value, label: countryName(value) }));
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.initialForm();

  private readonly country = toSignal(this.form.controls.countryCode.valueChanges.pipe(startWith(this.form.controls.countryCode.value)), {
    requireSync: true,
  });
  private readonly otherCode = toSignal(this.form.controls.otherCountry.valueChanges.pipe(startWith(this.form.controls.otherCountry.value)), {
    requireSync: true,
  });
  protected readonly otherCountry = computed(() => this.country() === OTHER_COUNTRY);
  protected readonly international = computed(() => {
    const code = this.otherCountry() ? this.otherCode().trim().toUpperCase() : this.country();
    return code !== 'BR';
  });

  private initialForm() {
    const p = this.data.processor;
    const s = this.data.suggestion;
    const hint = s ? PURPOSE_SUGGESTIONS[s.purpose] : undefined;
    const known = !p || COMMON_COUNTRIES.includes(p.countryCode);
    return this.fb.group({
      name: [p?.name ?? s?.name ?? '', [AppValidators.notBlank, Validators.maxLength(150)]],
      role: this.fb.control<ProcessorRoleCode>(p?.role ?? 'SUB_OPERATOR'),
      purpose: [p?.purpose ?? hint?.purpose ?? '', [AppValidators.notBlank, Validators.maxLength(300)]],
      dataCategories: [p?.dataCategories ?? hint?.categories ?? '', [AppValidators.notBlank, Validators.maxLength(300)]],
      countryCode: [known ? (p?.countryCode ?? 'BR') : OTHER_COUNTRY],
      otherCountry: [known ? '' : (p?.countryCode ?? ''), [Validators.pattern(/^[A-Za-z]{2}$/)]],
      transferSafeguard: this.fb.control<TransferSafeguardCode | null>(p?.transferSafeguard ?? null),
      contractReference: [p?.contractReference ?? '', [Validators.maxLength(120)]],
      validFrom: this.fb.control<string | null>(p?.validFrom ?? toLocalDate(new Date()), [Validators.required]),
      validUntil: this.fb.control<string | null>(p?.validUntil ?? null),
      active: [p?.active ?? true],
    });
  }

  protected message(field: Field): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const countryCode = (this.otherCountry() ? v.otherCountry : v.countryCode).trim().toUpperCase();
    if (this.otherCountry() && !/^[A-Z]{2}$/.test(countryCode)) {
      this.form.controls.otherCountry.setErrors({ required: true });
    }
    if (this.international() && !v.transferSafeguard) {
      this.form.controls.transferSafeguard.setErrors({ required: true });
    }
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const details: DataProcessorDetails = {
      name: v.name.trim(),
      role: v.role,
      purpose: v.purpose.trim(),
      dataCategories: v.dataCategories.trim(),
      countryCode,
      transferSafeguard: countryCode === 'BR' ? null : v.transferSafeguard,
      contractReference: v.contractReference.trim() || null,
      validFrom: v.validFrom!,
      validUntil: v.validUntil || null,
      active: v.active,
    };
    const p = this.data.processor;
    const request = p ? this.api.update(p.id, details) : this.api.register(this.data.suggestion?.providerKey ?? customKey(details.name), details);
    this.saving.set(true);
    this.generalErrors.set([]);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(p ? 'Operador atualizado.' : 'Operador cadastrado.');
        this.ref.close(saved);
      },
      error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
    });
  }
}
