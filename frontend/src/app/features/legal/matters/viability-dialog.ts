import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { Amount } from '../../../shared/components/amount/amount';
import { LegalMattersApi } from '../data/legal.api';
import { CHECKLIST_STATUS_LABELS, FLAG_LABELS, ROUTE_LABELS } from '../data/legal.labels';
import { AssessmentInput, AssessmentView, ChecklistEntry, LegalRoute, ViabilityResultView } from '../data/legal.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

export interface ViabilityDialogData {
  matterId: string;
  previous: AssessmentView | null;
}

/** Conclusão favorável exige vias disponíveis, checklist completo e, com custo maior que o benefício, parecer. */
export function viabilityProblems(viable: boolean, routes: readonly string[], checklist: readonly ChecklistEntry[], opinion: string, result: ViabilityResultView | null): string[] {
  const problems: string[] = [];
  if (!result) {
    problems.push('Calcule a viabilidade antes de registrar a análise.');
    return problems;
  }
  if (viable) {
    if (!routes.length) {
      problems.push('Escolha ao menos uma via recomendada.');
    }
    if (routes.some((r) => !result.availableRoutes.includes(r as LegalRoute))) {
      problems.push('Há via recomendada que não está disponível para este caso.');
    }
    if (checklist.some((c) => c.required && c.status === 'MISSING')) {
      problems.push('Há documento obrigatório faltando no checklist.');
    }
    if (!result.economicallyViable && !opinion.trim()) {
      problems.push('Custo maior que o benefício: registre o parecer que justifica seguir.');
    }
  } else if (!opinion.trim()) {
    problems.push('Para concluir como inviável, registre o parecer.');
  }
  return problems;
}

/**
 * Análise de viabilidade: premissas (outros bens, taxa de realização, probabilidade de êxito,
 * custas previstas), cálculo no servidor, vias disponíveis, checklist documental, pesquisa
 * patrimonial e parecer (guardados criptografados) e conclusão.
 */
@Component({
  selector: 'app-viability-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Análise de viabilidade</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <h3 class="app-section-title">Premissas</h3>
        <div class="vd-grid">
          <app-field label="Outros bens localizados (R$)">
            <input appInput ngbAutofocus type="number" min="0" step="0.01" formControlName="otherAssetsValue" />
          </app-field>
          <app-field label="Taxa de realização (%)" hint="Padrão 70%">
            <input appInput type="number" min="0" max="100" formControlName="realizationRate" />
          </app-field>
          <app-field label="Probabilidade de êxito (%)" hint="Padrão 50%">
            <input appInput type="number" min="0" max="100" formControlName="successProbability" />
          </app-field>
          <app-field label="Custas previstas (R$)">
            <input appInput type="number" min="0" step="0.01" formControlName="expectedCosts" />
          </app-field>
        </div>
        <button class="btn btn-outline-primary mt-2" type="button" (click)="calculate()" [disabled]="calculating()">
          <app-icon name="calculator" />
          Calcular
        </button>

        @if (result(); as r) {
          <dl class="app-definition-list vd-result" aria-live="polite">
            <dt>Dívida cobrável (não prescrita)</dt>
            <dd><app-amount [value]="r.collectibleDebt" /> de <app-amount [value]="r.debtAmount" /></dd>
            <dt>Garantias recuperáveis</dt>
            <dd><app-amount [value]="r.collateralRecoverable" /> (avaliadas em <app-amount [value]="r.collateralValue" />)</dd>
            <dt>Valor recuperável estimado</dt>
            <dd><strong><app-amount [value]="r.estimatedRecoverable" /></strong></dd>
            <dt>Benefício líquido</dt>
            <dd><app-amount [value]="r.netBenefit" [signed]="true" /> {{ r.economicallyViable ? '(compensa)' : '(custo maior que o benefício)' }}</dd>
            <dt>Vias disponíveis</dt>
            <dd>{{ routeText(r.availableRoutes) }}</dd>
            @if (r.flags.length) {
              <dt>Sinalizações</dt>
              <dd>{{ flagText(r.flags) }}</dd>
            }
          </dl>

          <h3 class="app-section-title">Checklist documental</h3>
          <ul class="vd-checklist">
            @for (c of checklist(); track c.code; let i = $index) {
              <li>
                <span>{{ c.label }}{{ c.required ? ' *' : '' }}</span>
                <app-field label="Situação">
                  <select appInput (change)="setStatus(i, $event)">
                    @for (s of statusOptions; track s.value) {
                      <option [value]="s.value" [selected]="s.value === c.status">{{ s.label }}</option>
                    }
                  </select>
                </app-field>
              </li>
            }
          </ul>

          <h3 class="app-section-title">Conclusão</h3>
          <app-field class="app-full-width" label="Resultado">
            <select appInput formControlName="viable">
              <option [ngValue]="true">Viável</option>
              <option [ngValue]="false">Inviável</option>
            </select>
          </app-field>
          @if (form.controls.viable.value) {
            <fieldset class="mb-3">
              <legend class="form-label fs-5">Vias recomendadas</legend>
              @for (rt of r.availableRoutes; track rt) {
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    [id]="'vd-route-' + rt"
                    [checked]="form.controls.routes.value.includes(rt)"
                    (change)="toggleRoute(rt, $event)"
                  />
                  <label class="form-check-label" [for]="'vd-route-' + rt">{{ routeLabels[rt] }}</label>
                </div>
              }
            </fieldset>
          }
          <app-field class="app-full-width" label="Pesquisa patrimonial (opcional)">
            <textarea appInput rows="2" formControlName="assetSearchNotes" maxlength="6000"></textarea>
          </app-field>
          <app-field class="app-full-width" label="Parecer" hint="Guardado criptografado. Obrigatório para inviabilidade ou custo maior que o benefício.">
            <textarea appInput rows="3" formControlName="opinion" maxlength="8000"></textarea>
          </app-field>
        }
        @for (p of problems(); track p) {
          <div class="invalid-feedback d-block" role="alert">{{ p }}</div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || !result()">Registrar análise</button>
      </div>
    </form>
  `,
  styles: `
    .vd-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .vd-result {
      margin: 16px 0;
      padding: 12px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
    .vd-checklist {
      margin: 0 0 12px;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        justify-content: space-between;
        align-items: center;
        gap: 8px;
        padding: 4px 0;
      }
    }
  `,
})
export class ViabilityDialog {
  protected readonly data = inject<ViabilityDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AssessmentView>>(DialogRef);
  private readonly api = inject(LegalMattersApi);
  protected readonly routeLabels = ROUTE_LABELS;
  protected readonly statusOptions = Object.entries(CHECKLIST_STATUS_LABELS).map(([value, label]) => ({ value, label }));

  protected readonly result = signal<ViabilityResultView | null>(null);
  protected readonly checklist = signal<ChecklistEntry[]>([]);
  protected readonly calculating = signal(false);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly p = this.data.previous;
  protected readonly form = this.fb.group({
    otherAssetsValue: this.fb.control<number | null>(this.p?.otherAssetsValue ?? null, Validators.min(0)),
    realizationRate: this.fb.control<number | null>(this.p?.realizationRate ?? 70, [Validators.min(0), Validators.max(100)]),
    successProbability: this.fb.control<number | null>(this.p?.successProbability ?? 50, [Validators.min(0), Validators.max(100)]),
    expectedCosts: this.fb.control<number | null>(this.p?.expectedCosts ?? null, Validators.min(0)),
    viable: [true],
    routes: this.fb.control<string[]>([]),
    assetSearchNotes: [''],
    opinion: [''],
  });
  protected readonly problems = computed(() => (this.submitted() ? this.currentProblems() : []));

  private input(): AssessmentInput {
    const v = this.form.getRawValue();
    const n = (x: number | null) => (x === null || (x as unknown) === '' ? null : Number(x));
    return { otherAssetsValue: n(v.otherAssetsValue), realizationRate: n(v.realizationRate), successProbability: n(v.successProbability), expectedCosts: n(v.expectedCosts) };
  }

  protected calculate(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.calculating.set(true);
    this.api
      .previewViability(this.data.matterId, this.input())
      .pipe(finalize(() => this.calculating.set(false)))
      .subscribe((r) => {
        this.result.set(r);
        const previous = new Map((this.p?.checklist ?? []).map((c) => [c.code, c.status]));
        this.checklist.set(r.suggestedChecklist.map((c) => ({ ...c, status: previous.get(c.code) ?? c.status })));
        this.form.controls.routes.setValue(r.suggestedRoutes.filter((x) => r.availableRoutes.includes(x)));
        this.form.controls.viable.setValue(r.availableRoutes.length > 0);
      });
  }

  protected setStatus(index: number, event: Event): void {
    const status = (event.target as HTMLSelectElement).value as ChecklistEntry['status'];
    this.checklist.update((list) => list.map((c, i) => (i === index ? { ...c, status } : c)));
  }

  /** Vias recomendadas em caixas de seleção, na ordem das vias disponíveis. */
  protected toggleRoute(route: LegalRoute, event: Event): void {
    const chosen = new Set(this.form.controls.routes.value);
    if ((event.target as HTMLInputElement).checked) {
      chosen.add(route);
    } else {
      chosen.delete(route);
    }
    this.form.controls.routes.setValue((this.result()?.availableRoutes ?? []).filter((r) => chosen.has(r)));
  }

  protected routeText(routes: readonly LegalRoute[]): string {
    return routes.length ? routes.map((r) => ROUTE_LABELS[r]).join(', ') : 'Nenhuma';
  }

  protected flagText(flags: readonly string[]): string {
    return flags.map((f) => FLAG_LABELS[f] ?? f).join(', ');
  }

  private currentProblems(): string[] {
    const v = this.form.getRawValue();
    return viabilityProblems(v.viable, v.routes, this.checklist(), v.opinion, this.result());
  }

  protected save(): void {
    this.submitted.set(true);
    if (this.currentProblems().length || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .recordViability(this.data.matterId, {
        input: this.input(),
        viable: v.viable,
        recommendedRoutes: v.viable ? (v.routes as LegalRoute[]) : [],
        checklist: this.checklist(),
        assetSearchNotes: v.assetSearchNotes.trim() || null,
        opinion: v.opinion.trim() || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((a) => this.ref.close(a));
  }
}
