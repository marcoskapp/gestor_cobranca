import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbDateAdapter, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { OVERRIDE_GROUP_LABELS } from '../data/negotiation.labels';
import { OVERRIDE_GROUPS, OverrideGroup, PolicyOverride, PolicyRules } from '../data/negotiation.models';
import { cloneTerms, overriddenGroups } from '../data/negotiation.rules';

export interface DateDialogData {
  title: string;
  message: string;
  label: string;
  confirmLabel: string;
  /** "yyyy-MM-dd" */
  min: string;
  initial: string;
  details: readonly { label: string; value: string }[];
  warning?: string;
}

/** Confirmação com data (publicar a partir de / aposentar em), com o resumo do impacto. */
@Component({
  selector: 'app-date-dialog',
  imports: [Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.title }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">{{ data.message }}</p>
      <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
        @for (d of data.details; track d.label) {
          <dt>{{ d.label }}</dt>
          <dd>{{ d.value }}</dd>
        }
      </dl>
      <app-field class="mb-3" [label]="data.label">
        <div class="input-group">
          <input appInput ngbDatepicker #picker="ngbDatepicker" placeholder="dd/mm/aaaa" [formControl]="date" [minDate]="min" required ngbAutofocus />
          <button type="button" class="btn btn-outline-secondary" (click)="picker.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      @if (data.warning) {
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note"><app-icon name="info-circle" /><span>{{ data.warning }}</span></div>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" [appDialogClose]="null">Cancelar</button>
      <button class="btn btn-primary" type="button" (click)="confirm()">{{ data.confirmLabel }}</button>
    </div>
  `,
})
export class DateDialog {
  protected readonly data = inject<DateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<string | null>>(DialogRef);
  protected readonly min = inject<NgbDateAdapter<string>>(NgbDateAdapter).fromModel(this.data.min)!;
  /** Modelo ISO yyyy-MM-dd (datepicker do ng-bootstrap). */
  protected readonly date = inject(FormBuilder).control<string | null>(this.data.initial);

  protected confirm(): void {
    const value = this.date.value;
    if (!value || value < this.data.min) {
      this.date.markAsTouched();
      this.date.setErrors(value ? { ngbDate: { minDate: this.min } } : { required: true });
      return;
    }
    this.ref.close(value);
  }
}

export interface OverrideDialogData {
  creditorId: string;
  base: PolicyRules;
  /** Sobreposição existente (edição do seletor e dos grupos substituídos). */
  override: PolicyOverride | null;
}

/**
 * Seletor da sobreposição (carteira e/ou faixa de atraso) e grupos substituídos. Grupo marcado
 * pela primeira vez começa como cópia da base; desmarcado volta a herdar da base.
 */
@Component({
  selector: 'app-override-dialog',
  imports: [...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.override ? 'Editar sobreposição' : 'Nova sobreposição' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field label="Código" hint="Maiúsculas, dígitos e _ (aparece na memória de cálculo)">
            <input appInput formControlName="code" maxlength="40" required ngbAutofocus />
          </app-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Vazio = todas as carteiras do credor." />
          </div>
          <app-field label="Atraso a partir de (dias)">
            <input appInput type="number" min="0" formControlName="minDaysOverdue" />
          </app-field>
          <app-field label="Atraso até (dias)">
            <input appInput type="number" min="0" formControlName="maxDaysOverdue" />
          </app-field>
        </div>
        <fieldset class="border rounded px-3 py-2 my-2">
          <legend class="float-none w-auto px-1 mb-1 fs-6">Grupos que esta sobreposição substitui</legend>
          @for (g of groups; track g) {
            <div class="form-check">
              <input #check class="form-check-input" type="checkbox" [id]="'od-group-' + g" [checked]="selected().has(g)" (change)="toggle(g, check.checked)" />
              <label class="form-check-label" [for]="'od-group-' + g">{{ labels[g] }}</label>
            </div>
          }
        </fieldset>
        @if (problem()) {
          <div class="invalid-feedback d-block" role="alert">{{ problem() }}</div>
        }
        <p class="app-muted">A mais específica vence: carteira + faixa, depois só carteira, depois só faixa. A faixa usa o maior atraso entre os títulos.</p>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit">Aplicar</button>
      </div>
    </form>
  `,
})
export class OverrideDialog {
  protected readonly data = inject<OverrideDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PolicyOverride>>(DialogRef);
  protected readonly portfolios = inject(PortfolioLookup).source(() => this.data.creditorId);
  protected readonly groups = OVERRIDE_GROUPS;
  protected readonly labels = OVERRIDE_GROUP_LABELS;
  protected readonly selected = signal<ReadonlySet<OverrideGroup>>(new Set(this.data.override ? overriddenGroups(this.data.override) : []));
  protected readonly problem = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: [this.data.override?.code ?? ''],
    portfolio: this.fb.control<string[]>(this.data.override?.portfolioId ? [this.data.override.portfolioId] : []),
    minDaysOverdue: this.fb.control<number | null>(this.data.override?.minDaysOverdue ?? null),
    maxDaysOverdue: this.fb.control<number | null>(this.data.override?.maxDaysOverdue ?? null),
  });

  protected toggle(group: OverrideGroup, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) {
        next.add(group);
      } else {
        next.delete(group);
      }
      return next;
    });
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const code = v.code.trim().toUpperCase();
    const min = v.minDaysOverdue === null || (v.minDaysOverdue as unknown) === '' ? null : Number(v.minDaysOverdue);
    const max = v.maxDaysOverdue === null || (v.maxDaysOverdue as unknown) === '' ? null : Number(v.maxDaysOverdue);
    const portfolioId = v.portfolio[0] ?? null;
    this.problem.set(
      !/^[A-Z0-9_]{2,40}$/.test(code)
        ? 'Código: 2 a 40 caracteres em maiúsculas, dígitos ou _.'
        : !portfolioId && min === null && max === null
          ? 'Informe a carteira ou a faixa de atraso.'
          : min !== null && max !== null && max < min
            ? 'A faixa de atraso termina antes de começar.'
            : this.selected().size === 0
              ? 'Escolha ao menos um grupo para substituir.'
              : null,
    );
    if (this.problem()) {
      return;
    }
    const previous = this.data.override;
    const pick = <G extends OverrideGroup>(g: G): PolicyRules[G] | null =>
      this.selected().has(g) ? ((previous?.[g] as PolicyRules[G] | null) ?? cloneTerms(this.data.base[g])) : null;
    this.ref.close({
      code,
      portfolioId,
      minDaysOverdue: min,
      maxDaysOverdue: max,
      charges: pick('charges'),
      discountTiers: pick('discountTiers'),
      installments: pick('installments'),
      validity: pick('validity'),
      ladder: pick('ladder'),
      breakRule: pick('breakRule'),
    });
  }
}
