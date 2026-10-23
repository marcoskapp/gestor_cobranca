import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { Amount } from '../../../shared/components/amount/amount';
import { LegalMattersApi } from '../data/legal.api';
import { CHECKLIST_STATUS_LABELS, FLAG_LABELS, ROUTE_LABELS } from '../data/legal.labels';
import { AssessmentInput, AssessmentView, ChecklistEntry, LegalRoute, ViabilityResultView } from '../data/legal.models';

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
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, Amount],
  template: `
    <h2 mat-dialog-title>Análise de viabilidade</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <h3 class="app-section-title">Premissas</h3>
        <div class="vd-grid">
          <mat-form-field>
            <mat-label>Outros bens localizados (R$)</mat-label>
            <input matInput type="number" min="0" step="0.01" formControlName="otherAssetsValue" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Taxa de realização (%)</mat-label>
            <input matInput type="number" min="0" max="100" formControlName="realizationRate" />
            <mat-hint>Padrão 70%</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Probabilidade de êxito (%)</mat-label>
            <input matInput type="number" min="0" max="100" formControlName="successProbability" />
            <mat-hint>Padrão 50%</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Custas previstas (R$)</mat-label>
            <input matInput type="number" min="0" step="0.01" formControlName="expectedCosts" />
          </mat-form-field>
        </div>
        <button mat-stroked-button type="button" (click)="calculate()" [disabled]="calculating()">
          <mat-icon aria-hidden="true">calculate</mat-icon>
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
                <mat-form-field class="app-compact" subscriptSizing="dynamic">
                  <mat-label>Situação</mat-label>
                  <mat-select [value]="c.status" (selectionChange)="setStatus(i, $event.value)">
                    @for (s of statusOptions; track s.value) {
                      <mat-option [value]="s.value">{{ s.label }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
              </li>
            }
          </ul>

          <h3 class="app-section-title">Conclusão</h3>
          <mat-form-field class="app-full-width">
            <mat-label>Resultado</mat-label>
            <mat-select formControlName="viable">
              <mat-option [value]="true">Viável</mat-option>
              <mat-option [value]="false">Inviável</mat-option>
            </mat-select>
          </mat-form-field>
          @if (form.controls.viable.value) {
            <mat-form-field class="app-full-width">
              <mat-label>Vias recomendadas</mat-label>
              <mat-select formControlName="routes" multiple>
                @for (rt of r.availableRoutes; track rt) {
                  <mat-option [value]="rt">{{ routeLabels[rt] }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          <mat-form-field class="app-full-width">
            <mat-label>Pesquisa patrimonial (opcional)</mat-label>
            <textarea matInput rows="2" formControlName="assetSearchNotes" maxlength="6000"></textarea>
          </mat-form-field>
          <mat-form-field class="app-full-width">
            <mat-label>Parecer</mat-label>
            <textarea matInput rows="3" formControlName="opinion" maxlength="8000"></textarea>
            <mat-hint>Guardado criptografado. Obrigatório para inviabilidade ou custo maior que o benefício.</mat-hint>
          </mat-form-field>
        }
        @for (p of problems(); track p) {
          <p class="app-field-error" role="alert">{{ p }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving() || !result()">Registrar análise</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .vd-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 0 12px;
    }
    .vd-result {
      margin: 16px 0;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
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
  protected readonly data = inject<ViabilityDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ViabilityDialog, AssessmentView>>(MatDialogRef);
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

  protected setStatus(index: number, status: ChecklistEntry['status']): void {
    this.checklist.update((list) => list.map((c, i) => (i === index ? { ...c, status } : c)));
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
