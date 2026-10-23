import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="app-dialog-message">{{ data.message }}</p>
      <dl class="app-definition-list pd-details">
        @for (d of data.details; track d.label) {
          <dt>{{ d.label }}</dt>
          <dd>{{ d.value }}</dd>
        }
      </dl>
      <mat-form-field class="app-full-width">
        <mat-label>{{ data.label }}</mat-label>
        <input matInput [matDatepicker]="picker" [formControl]="date" [min]="min" required />
        <mat-datepicker-toggle matIconSuffix [for]="picker" />
        <mat-datepicker #picker />
      </mat-form-field>
      @if (data.warning) {
        <p class="app-alert app-alert--warning" role="note"><mat-icon aria-hidden="true">info</mat-icon><span>{{ data.warning }}</span></p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="null">Cancelar</button>
      <button mat-flat-button type="button" (click)="confirm()">{{ data.confirmLabel }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .pd-details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class DateDialog {
  protected readonly data = inject<DateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<DateDialog, string | null>>(MatDialogRef);
  protected readonly min = fromLocalDate(this.data.min);
  protected readonly date = inject(FormBuilder).nonNullable.control<Date | null>(fromLocalDate(this.data.initial));

  protected confirm(): void {
    const value = toLocalDate(this.date.value);
    if (!value || value < this.data.min) {
      this.date.markAsTouched();
      this.date.setErrors({ min: true });
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.override ? 'Editar sobreposição' : 'Nova sobreposição' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" required />
            <mat-hint>Maiúsculas, dígitos e _ (aparece na memória de cálculo)</mat-hint>
          </mat-form-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" hint="Vazio = todas as carteiras do credor." />
          </div>
          <mat-form-field>
            <mat-label>Atraso a partir de (dias)</mat-label>
            <input matInput type="number" min="0" formControlName="minDaysOverdue" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Atraso até (dias)</mat-label>
            <input matInput type="number" min="0" formControlName="maxDaysOverdue" />
          </mat-form-field>
        </div>
        <fieldset class="od-groups">
          <legend>Grupos que esta sobreposição substitui</legend>
          @for (g of groups; track g) {
            <mat-checkbox [checked]="selected().has(g)" (change)="toggle(g, $event.checked)">{{ labels[g] }}</mat-checkbox>
          }
        </fieldset>
        @if (problem()) {
          <p class="app-field-error" role="alert">{{ problem() }}</p>
        }
        <p class="app-muted">A mais específica vence: carteira + faixa, depois só carteira, depois só faixa. A faixa usa o maior atraso entre os títulos.</p>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit">Aplicar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .od-groups {
      display: flex;
      flex-direction: column;
      margin: 8px 0;
      padding: 8px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
    }
  `,
})
export class OverrideDialog {
  protected readonly data = inject<OverrideDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<OverrideDialog, PolicyOverride>>(MatDialogRef);
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
