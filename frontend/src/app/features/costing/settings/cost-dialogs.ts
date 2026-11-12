import { Component, WritableSignal, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Observable, finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { isoDate } from '../../../shared/models/dates';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { zonedNow } from '../../collection/data/collection.rules';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { CostSettingsApi } from '../data/costing.api';
import { ALLOCATION_HINTS, ALLOCATION_LABELS, CHANNEL_LABELS, CHANNEL_UNITS } from '../data/costing.labels';
import { ALLOCATIONS, Allocation, CostBand, TARIFF_CHANNELS, TariffChannel } from '../data/costing.models';
import { lastClosedMonthStart, monthOptions, normalizeMonth } from '../data/cost-settings.rules';

function errorText(err: unknown): string {
  return userMessage(toApiError(err));
}

/** Envio comum dos diálogos: trava o botão, fecha com o resultado ou mostra o erro do servidor. */
function submit<T>(request: Observable<T>, saving: WritableSignal<boolean>, error: WritableSignal<string | null>, ref: DialogRef<T>): void {
  saving.set(true);
  error.set(null);
  request.pipe(finalize(() => saving.set(false))).subscribe({
    next: (saved) => ref.close(saved),
    error: (err: unknown) => error.set(errorText(err)),
  });
}


// ------------------------------------------------------------------ faixa de custo

export interface BandDialogData {
  /** Nova vigência de uma faixa existente (o nome fica fixo); nulo = faixa nova. */
  band: CostBand | null;
}

/**
 * Faixa de custo de pessoas: nome do cargo e custo-empresa por hora (salário + encargos + benefícios ÷ horas), a partir
 * de uma data. Nova vigência com o mesmo nome substitui a anterior a partir dessa data (o histórico fica).
 */
@Component({
  selector: 'app-band-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.band ? 'Nova vigência da faixa' : 'Nova faixa de custo' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field class="app-span-all" label="Nome da faixa" hint="Cargo ou nível, ex.: Negociador júnior" error="Informe o nome (até 80 caracteres).">
            <input appInput formControlName="name" maxlength="80" required [readonly]="!!data.band" />
          </app-field>
          <app-field label="Custo por hora" hint="Custo-empresa: salário, encargos e benefícios ÷ horas contratadas" error="Informe um valor maior que zero.">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney ngbAutofocus formControlName="hourlyCost" required [min]="0.01" />
            </div>
          </app-field>
          <app-field label="Vale a partir de" error="Informe a data."><div class="input-group">
    <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="validFrom" placeholder="dd/mm/aaaa" autocomplete="off" required />
    <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()"><app-icon name="calendar-event" /></button>
  </div></app-field>
        </div>
        <p class="app-muted small mt-2 mb-0">O valor fica só nesta lista: o painel mostra o custo somado, nunca o de uma pessoa.</p>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class BandDialog {
  protected readonly data = inject<BandDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CostBand>>(DialogRef);
  private readonly api = inject(CostSettingsApi);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    name: [this.data.band?.name ?? '', [AppValidators.notBlank, Validators.maxLength(80)]],
    hourlyCost: this.fb.control<number | null>(null, [Validators.required]),
    validFrom: this.fb.control<string | null>(zonedNow().date, [Validators.required]),
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const validFrom = isoDate(v.validFrom);
    if (this.form.invalid || !validFrom || v.hourlyCost === null || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    submit(this.api.createBand({ name: v.name.trim(), hourlyCost: v.hourlyCost, validFrom }), this.saving, this.error, this.ref);
  }
}

// ------------------------------------------------------------------ pessoa → faixa

export interface AssignBandDialogData {
  /** Pessoa já escolhida (troca a partir da lista); nulo = escolher no seletor de usuários. */
  userId: string | null;
  userName: string | null;
  currentBand: string | null;
  bandNames: string[];
}

export interface AssignBandResult {
  userId: string;
  bandName: string;
}

/** Atribui a faixa (pelo nome) a uma pessoa a partir de uma data. Mostra só o nome da faixa, nunca o valor-hora. */
@Component({
  selector: 'app-assign-band-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.userId ? 'Trocar a faixa de ' + data.userName : 'Atribuir faixa de custo' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          @if (!data.userId) {
            <div class="app-span-all">
              <app-entity-picker [control]="form.controls.user" [source]="users" label="Pessoa" [single]="true" />
              @if (submitted() && !form.controls.user.value.length) {
                <div class="invalid-feedback d-block">Escolha a pessoa.</div>
              }
            </div>
          }
          <app-field label="Faixa" [hint]="data.currentBand ? 'Hoje: ' + data.currentBand : 'Só faixas em vigor'" error="Escolha a faixa.">
            <select appInput formControlName="bandName" required ngbAutofocus>
              <option [ngValue]="''" disabled>Escolha</option>
              @for (name of data.bandNames; track name) {
                <option [ngValue]="name">{{ name }}</option>
              }
            </select>
          </app-field>
          <app-field label="Vale a partir de" error="Informe a data."><div class="input-group">
    <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="validFrom" placeholder="dd/mm/aaaa" autocomplete="off" required />
    <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()"><app-icon name="calendar-event" /></button>
  </div></app-field>
        </div>
        <p class="app-muted small mt-2 mb-0">As horas de trabalho a partir dessa data usam o custo da faixa vigente em cada dia.</p>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class AssignBandDialog {
  protected readonly data = inject<AssignBandDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AssignBandResult>>(DialogRef);
  private readonly api = inject(CostSettingsApi);
  protected readonly users = inject(UserLookup);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    user: this.fb.control<string[]>(this.data.userId ? [this.data.userId] : [], [Validators.required]),
    bandName: [this.data.currentBand && this.data.bandNames.includes(this.data.currentBand) ? this.data.currentBand : '', [Validators.required]],
    validFrom: this.fb.control<string | null>(zonedNow().date, [Validators.required]),
  });

  protected save(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const userId = v.user[0];
    const validFrom = isoDate(v.validFrom);
    if (this.form.invalid || !userId || !validFrom || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .assignBand(userId, { bandName: v.bandName, validFrom })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => this.ref.close({ userId, bandName: v.bandName }),
        error: (err: unknown) => this.error.set(errorText(err)),
      });
  }
}

// ------------------------------------------------------------------ tarifa de canal

export interface TariffDialogData {
  /** Nova vigência de um canal já tarifado (o canal fica fixo). */
  channel: TariffChannel | null;
}

/** Tarifa de canal por unidade cobrada pelo provedor, com até 4 casas (frações de centavo), a partir de uma data. */
@Component({
  selector: 'app-tariff-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.channel ? 'Nova vigência da tarifa' : 'Nova tarifa de canal' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field class="app-span-all" label="Canal" [hint]="units[channel()]">
            <select appInput formControlName="channel" required>
              @for (c of channels; track c) {
                <option [ngValue]="c">{{ labels[c] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Tarifa" hint="Até 4 casas decimais; zero vale (ex.: e-mail desprezível)" error="Informe a tarifa (zero ou mais).">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney ngbAutofocus formControlName="unitCost" required [decimals]="4" [min]="0" />
            </div>
          </app-field>
          <app-field label="Vale a partir de" error="Informe a data."><div class="input-group">
    <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="validFrom" placeholder="dd/mm/aaaa" autocomplete="off" required />
    <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()"><app-icon name="calendar-event" /></button>
  </div></app-field>
        </div>
        <p class="app-muted small mt-2 mb-0">Resposta do devedor dentro da janela de 24 horas do WhatsApp não gera nova cobrança. A telefonia usa a tarifa por minuto da conta de cada plataforma.</p>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class TariffDialog {
  protected readonly data = inject<TariffDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<unknown>>(DialogRef);
  private readonly api = inject(CostSettingsApi);
  protected readonly channels = this.data.channel ? [this.data.channel] : TARIFF_CHANNELS;
  protected readonly labels = CHANNEL_LABELS;
  protected readonly units = CHANNEL_UNITS;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<TariffChannel>(this.data.channel ?? 'WHATSAPP_UTILITY', [Validators.required]),
    unitCost: this.fb.control<number | null>(null, [Validators.required]),
    validFrom: this.fb.control<string | null>(zonedNow().date, [Validators.required]),
  });
  protected readonly channel = toSignal(this.form.controls.channel.valueChanges, { initialValue: this.form.controls.channel.value });

  protected save(): void {
    const v = this.form.getRawValue();
    const validFrom = isoDate(v.validFrom);
    if (this.form.invalid || !validFrom || v.unitCost === null || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    submit(this.api.createTariff({ channel: v.channel, unitCost: v.unitCost, validFrom }), this.saving, this.error, this.ref);
  }
}

// ------------------------------------------------------------------ encerrar vigência

export interface EndValidityDialogData {
  title: string;
  /** O que é encerrado (ex.: "Negociador júnior" ou "WhatsApp — utilidade"). */
  subject: string;
  /** Efeito do encerramento, em uma frase. */
  effect: string;
  /** Primeiro dia permitido (`AAAA-MM-DD`): o início da vigência. */
  minDate: string;
  end: (validTo: string) => Observable<unknown>;
}

/** Encerra uma vigência numa data (nada é apagado: o histórico fica para as apurações já feitas). */
@Component({
  selector: 'app-end-validity-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p><strong>{{ data.subject }}</strong>: {{ data.effect }}</p>
        <app-field label="Último dia de vigência" error="Informe uma data a partir do início da vigência.">
          <div class="input-group">
            <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="validTo" [minDate]="min" placeholder="dd/mm/aaaa" autocomplete="off" required ngbAutofocus />
            <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Encerrar</button>
      </div>
    </form>
  `,
})
export class EndValidityDialog {
  protected readonly data = inject<EndValidityDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<unknown>>(DialogRef);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly min = (() => {
    const [year, month, day] = this.data.minDate.split('-').map(Number);
    return { year, month, day };
  })();
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    validTo: this.fb.control<string | null>(zonedNow().date >= this.data.minDate ? zonedNow().date : this.data.minDate, [Validators.required]),
  });

  protected save(): void {
    const validTo = isoDate(this.form.getRawValue().validTo);
    if (!validTo || validTo < this.data.minDate || this.saving()) {
      this.form.controls.validTo.setErrors({ min: true });
      this.form.markAllAsTouched();
      return;
    }
    submit(this.data.end(validTo), this.saving, this.error, this.ref);
  }
}

// ------------------------------------------------------------------ câmbio da IA

export interface FxRateDialogData {
  /** Mês já informado (corrigir); nulo = o último mês fechado. */
  month: string | null;
}

/** Câmbio do dólar no mês (o custo de IA vem em dólar): um valor por mês; informar de novo substitui. */
@Component({
  selector: 'app-fx-rate-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Câmbio do dólar no mês</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field label="Mês">
            <select appInput formControlName="month" required>
              @for (m of months; track m.value) {
                <option [ngValue]="m.value">{{ m.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Valor de US$ 1" hint="Até 4 casas decimais, ex.: a PTAX média do mês" error="Informe um valor maior que zero.">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney ngbAutofocus formControlName="usdBrl" required [decimals]="4" [min]="0.0001" />
            </div>
          </app-field>
        </div>
        <p class="app-muted small mt-2 mb-0">Mês sem câmbio usa o último informado, e o painel avisa.</p>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class FxRateDialog {
  protected readonly data = inject<FxRateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<unknown>>(DialogRef);
  private readonly api = inject(CostSettingsApi);
  protected readonly months = monthOptions(new Date());
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    month: [this.data.month ? normalizeMonth(this.data.month) : lastClosedMonthStart(new Date()), [Validators.required]],
    usdBrl: this.fb.control<number | null>(null, [Validators.required]),
  });

  protected save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || v.usdBrl === null || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    submit(this.api.saveFxRate({ month: v.month, usdBrl: v.usdBrl }), this.saving, this.error, this.ref);
  }
}

// ------------------------------------------------------------------ custo fixo

export interface OverheadDialogData {
  /**
   * Quem não tem todos os credores só lança custo direto a um credor do próprio escopo (o rateio entre credores é da
   * assessoria inteira e o servidor exige escopo total).
   */
  creditorOnly: boolean;
}

/** Custo fixo ou indireto do mês com a regra de rateio (padrão: por horas trabalhadas — decisão do responsável, Q3). */
@Component({
  selector: 'app-overhead-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Novo custo fixo</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field label="Mês">
            <select appInput formControlName="month" required>
              @for (m of months; track m.value) {
                <option [ngValue]="m.value">{{ m.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Valor" error="Informe um valor maior que zero.">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney formControlName="amount" required [min]="0.01" />
            </div>
          </app-field>
          <app-field class="app-span-all" label="Descrição" hint="Ex.: aluguel, licenças, infraestrutura, gestão" error="Informe a descrição (até 120 caracteres).">
            <input appInput formControlName="description" maxlength="120" required ngbAutofocus />
          </app-field>
          <app-field class="app-span-all" label="Rateio" [hint]="hints[allocation()]">
            <select appInput formControlName="allocation" required>
              @for (a of allocations; track a) {
                <option [ngValue]="a">{{ labels[a] }}</option>
              }
            </select>
          </app-field>
          @if (allocation() === 'CREDITOR') {
            <div class="app-span-all">
              <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
              @if (submitted() && !form.controls.creditor.value.length) {
                <div class="invalid-feedback d-block">Escolha o credor que recebe o custo.</div>
              }
            </div>
          }
        </div>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
    </form>
  `,
})
export class OverheadDialog {
  private readonly data = inject<OverheadDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<unknown>>(DialogRef);
  private readonly api = inject(CostSettingsApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly months = monthOptions(new Date());
  protected readonly allocations: readonly Allocation[] = this.data.creditorOnly ? ['CREDITOR'] : ALLOCATIONS;
  protected readonly labels = ALLOCATION_LABELS;
  protected readonly hints = ALLOCATION_HINTS;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    month: [lastClosedMonthStart(new Date()), [Validators.required]],
    amount: this.fb.control<number | null>(null, [Validators.required]),
    description: ['', [AppValidators.notBlank, Validators.maxLength(120)]],
    allocation: this.fb.control<Allocation>(this.data.creditorOnly ? 'CREDITOR' : 'HOURS', [Validators.required]),
    creditor: this.fb.control<string[]>([], this.data.creditorOnly ? [Validators.required] : []),
  });
  protected readonly allocation = toSignal(this.form.controls.allocation.valueChanges, { initialValue: this.form.controls.allocation.value });

  constructor() {
    // credor obrigatório só no rateio direto a um credor
    this.form.controls.allocation.valueChanges.pipe(takeUntilDestroyed()).subscribe((allocation) => {
      const creditor = this.form.controls.creditor;
      creditor.setValidators(allocation === 'CREDITOR' ? [Validators.required] : []);
      creditor.updateValueAndValidity();
    });
  }

  protected save(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const creditorId = v.allocation === 'CREDITOR' ? (v.creditor[0] ?? null) : null;
    if (this.form.invalid || v.amount === null || (v.allocation === 'CREDITOR' && !creditorId) || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    submit(
      this.api.createOverhead({ month: v.month, description: v.description.trim(), amount: v.amount, allocation: v.allocation, creditorId }),
      this.saving,
      this.error,
      this.ref,
    );
  }
}
