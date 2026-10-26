import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, map, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { ScoringModelsApi } from '../data/scoring.api';
import { SCORING_ERROR_LABELS, featureLabel } from '../data/scoring.labels';
import { FeatureCatalog, FeatureInfo, ModelView } from '../data/scoring.models';
import {
  BinForm,
  RuleForm,
  SCORECARD_LIMITS,
  duplicatedFeatures,
  isScorecard,
  probabilityFromOdds,
  ruleProblems,
  rulesFromDefinition,
  scorecardRequest,
} from '../data/scoring.rules';

export interface ScorecardDialogData {
  /** Scorecards existentes que podem servir de ponto de partida (o v1 é o padrão de mercado). */
  templates: readonly ModelView[];
}

type BinGroup = FormGroup<{ from: FormControl<number | null>; to: FormControl<number | null>; points: FormControl<number | null> }>;
type RuleGroup = FormGroup<{ feature: FormControl<string>; missingPoints: FormControl<number | null>; bins: FormArray<BinGroup> }>;

/**
 * Criação de scorecard por pontos (`POST /models/scorecards`): pontos base, escala (pontos para
 * dobrar a chance, PDO) e, por variável do catálogo fechado, faixas `[de, até)` com pontos e os
 * pontos para valor ausente. Entra como desafiante, com retroteste no histórico quando houver.
 */
@Component({
  selector: 'app-scorecard-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Criar scorecard</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Regras transparentes por faixa, com as variáveis permitidas. O scorecard entra como <strong>desafiante</strong>: é comparado com o
          campeão no histórico e calculado em sombra até outro gestor decidir.
        </p>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        @if (catalog.status() === 'error') {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">
            <app-icon name="exclamation-circle" />
            <span>Não foi possível carregar as variáveis. <button type="button" class="btn btn-link p-0 align-baseline" (click)="catalog.reload()">Tentar novamente</button></span>
          </div>
        }

        <div class="app-form-grid">
          @if (data.templates.length) {
            <app-field label="Começar a partir de" hint="Copia pontos, escala e regras; você ajusta depois.">
              <select appInput [formControl]="template" (change)="applyTemplate()">
                <option value="">Em branco</option>
                @for (t of data.templates; track t.id) {
                  <option [value]="t.id">v{{ t.modelVersion }}{{ t.description ? ' · ' + t.description : '' }}</option>
                }
              </select>
            </app-field>
          }
          <app-field label="Descrição" hint="Opcional, até 200 caracteres." error="No máximo 200 caracteres.">
            <input appInput formControlName="description" maxlength="200" />
          </app-field>
          <app-field label="Pontos base" hint="Somados a todo caso." error="Inteiro entre −5000 e 5000.">
            <input appInput type="number" formControlName="basePoints" step="1" min="-5000" max="5000" />
          </app-field>
          <app-field label="Pontos para dobrar a chance (PDO)" hint="Padrão de mercado: 20." error="Inteiro entre 1 e 200.">
            <input appInput type="number" formControlName="pdo" step="1" min="1" max="200" />
          </app-field>
          <app-field label="Pontos de referência" error="Inteiro entre −5000 e 5000.">
            <input appInput type="number" formControlName="anchorPoints" step="1" min="-5000" max="5000" />
          </app-field>
          <app-field label="Chance nos pontos de referência (%)" hint="Ex.: 500 pontos = 10% de chance." error="Entre 0,01% e 99,8%.">
            <input appInput type="number" formControlName="anchorProbabilityPct" step="any" min="0.01" max="99.8" />
          </app-field>
        </div>

        <h3 class="app-section-title mt-3">Regras por variável ({{ rules.length }} de {{ limits.rules }})</h3>
        <div formArrayName="rules" class="sc-rules">
          @for (rule of rules.controls; track rule; let ri = $index) {
            @let problems = ruleIssues()[ri] ?? [];
            <fieldset class="sc-rule" [formGroupName]="ri" [attr.aria-describedby]="problems.length ? 'sc-rule-' + ri + '-problems' : null">
              <legend class="visually-hidden">Regra {{ ri + 1 }}: {{ ruleName(ri) }}</legend>
              <div class="sc-rule__head">
                <app-field label="Variável" class="sc-rule__feature">
                  <select appInput formControlName="feature">
                    <option value="" disabled>Escolha a variável</option>
                    @for (f of features(); track f.name) {
                      <option [value]="f.name">{{ f.label }}</option>
                    }
                  </select>
                </app-field>
                <app-field label="Pontos se ausente" hint="Ex.: nunca houve contato.">
                  <input appInput type="number" formControlName="missingPoints" step="1" min="-500" max="500" />
                </app-field>
                <button type="button" class="btn btn-link text-danger-emphasis sc-rule__remove app-control-offset" (click)="removeRule(ri)" [attr.aria-label]="'Remover a regra ' + (ri + 1) + ' (' + ruleName(ri) + ')'">
                  <app-icon name="trash3" />
                  Remover regra
                </button>
              </div>
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-1 sc-bins">
                  <caption class="visually-hidden">Faixas de {{ ruleName(ri) }}: início incluído, fim excluído; vazio = sem limite</caption>
                  <thead>
                    <tr>
                      <th scope="col">De (incluído)</th>
                      <th scope="col">Até (excluído)</th>
                      <th scope="col">Pontos</th>
                      <th scope="col"><span class="visually-hidden">Ações</span></th>
                    </tr>
                  </thead>
                  <tbody formArrayName="bins">
                    @for (bin of binsOf(ri).controls; track bin; let bi = $index) {
                      <tr [formGroupName]="bi">
                        <td><input class="form-control form-control-sm" type="number" step="any" formControlName="from" [attr.aria-label]="'Início da faixa ' + (bi + 1) + ' de ' + ruleName(ri) + ' (vazio = sem limite)'" /></td>
                        <td><input class="form-control form-control-sm" type="number" step="any" formControlName="to" [attr.aria-label]="'Fim da faixa ' + (bi + 1) + ' de ' + ruleName(ri) + ' (vazio = sem limite)'" /></td>
                        <td><input class="form-control form-control-sm" type="number" step="1" formControlName="points" [attr.aria-label]="'Pontos da faixa ' + (bi + 1) + ' de ' + ruleName(ri)" /></td>
                        <td class="text-end">
                          <button type="button" class="btn btn-sm btn-link" (click)="removeBin(ri, bi)" [attr.aria-label]="'Remover a faixa ' + (bi + 1) + ' de ' + ruleName(ri)">
                            <app-icon name="x-lg" />
                          </button>
                        </td>
                      </tr>
                    } @empty {
                      <tr><td colspan="4" class="app-muted">Sem faixas: só os pontos de ausente se aplicam.</td></tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (binsOf(ri).length < limits.bins) {
                <button type="button" class="btn btn-sm btn-link" (click)="addBin(ri)"><app-icon name="plus-lg" />Adicionar faixa</button>
              }
              @if (problems.length && (submitted() || rule.touched)) {
                <ul class="sc-problems" [id]="'sc-rule-' + ri + '-problems'" role="alert">
                  @for (p of problems; track p) {
                    <li>{{ p }}</li>
                  }
                </ul>
              }
            </fieldset>
          } @empty {
            <p class="app-muted">Nenhuma regra ainda. Adicione ao menos uma variável.</p>
          }
        </div>
        @if (rules.length < limits.rules) {
          <button type="button" class="btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-2" (click)="addRule()" [disabled]="!features().length">
            <app-icon name="plus-lg" />
            Adicionar regra
          </button>
        }
        @if (submitted() && formIssues().length) {
          <ul class="sc-problems mt-2" role="alert">
            @for (p of formIssues(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="sending()">Criar scorecard desafiante</button>
      </div>
    </form>
  `,
  styles: `
    .sc-rules {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin-bottom: 12px;
    }
    .sc-rule {
      padding: 12px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
    }
    .sc-rule__head {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto;
      gap: 12px;
      align-items: start;
      margin-bottom: 8px;
    }
    .sc-bins input {
      min-width: 90px;
    }
    .sc-problems {
      margin: 4px 0 0;
      padding-left: 20px;
      font-size: var(--app-text-sm);
      color: var(--bs-danger-text-emphasis);
    }
    @media (max-width: 599.98px) {
      .sc-rule__head {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class ScorecardDialog {
  protected readonly data = inject<ScorecardDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ModelView>>(DialogRef);
  private readonly api = inject(ScoringModelsApi);
  protected readonly limits = SCORECARD_LIMITS;
  protected readonly sending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly catalog = rxResource({ stream: () => this.api.features() });
  private readonly catalogValue = valueOr<FeatureCatalog | undefined>(this.catalog, undefined);
  protected readonly features = computed<FeatureInfo[]>(() => this.catalogValue()?.allowed ?? []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly template = new FormControl('', { nonNullable: true });
  protected readonly rules = this.fb.array<RuleGroup>([]);
  protected readonly form = this.fb.group({
    description: ['', [Validators.maxLength(200)]],
    basePoints: this.fb.control<number | null>(500, [Validators.required, Validators.min(-5000), Validators.max(5000)]),
    pdo: this.fb.control<number | null>(20, [Validators.required, Validators.min(1), Validators.max(200)]),
    anchorPoints: this.fb.control<number | null>(500, [Validators.required, Validators.min(-5000), Validators.max(5000)]),
    anchorProbabilityPct: this.fb.control<number | null>(10, [Validators.required, Validators.min(0.01), Validators.max(99.8)]),
    rules: this.rules,
  });

  private readonly ruleValues = toSignal(
    this.form.valueChanges.pipe(
      startWith(null),
      map(() => this.rules.getRawValue() as RuleForm[]),
    ),
    { initialValue: [] as RuleForm[] },
  );
  protected readonly ruleIssues = computed(() => this.ruleValues().map(ruleProblems));
  protected readonly formIssues = computed(() => {
    const issues: string[] = [];
    const rules = this.ruleValues();
    if (!rules.length) {
      issues.push('Inclua ao menos uma regra.');
    }
    const dup = duplicatedFeatures(rules);
    if (dup.length) {
      issues.push(`Variável repetida: ${dup.map((d) => featureLabel(this.features(), d)).join(', ')}. Cada variável aparece em uma regra só.`);
    }
    if (this.ruleIssues().some((p) => p.length)) {
      issues.push('Corrija as regras destacadas.');
    }
    return issues;
  });

  constructor() {
    this.addRule();
  }

  protected binsOf(index: number): FormArray<BinGroup> {
    return this.rules.at(index).controls.bins;
  }

  protected ruleName(index: number): string {
    const name = this.ruleValues()[index]?.feature;
    return name ? featureLabel(this.features(), name) : `regra ${index + 1}`;
  }

  protected addRule(rule: RuleForm = { feature: '', missingPoints: 0, bins: [{ from: null, to: null, points: 0 }] }): void {
    this.rules.push(
      this.fb.group({
        feature: this.fb.control(rule.feature),
        missingPoints: this.fb.control<number | null>(rule.missingPoints),
        bins: this.fb.array<BinGroup>(rule.bins.map((b) => this.bin(b))),
      }),
    );
  }

  protected removeRule(index: number): void {
    this.rules.removeAt(index);
  }

  protected addBin(ruleIndex: number): void {
    const bins = this.binsOf(ruleIndex);
    const last = bins.length ? bins.at(bins.length - 1).getRawValue() : null;
    bins.push(this.bin({ from: last?.to ?? null, to: null, points: 0 }));
  }

  protected removeBin(ruleIndex: number, binIndex: number): void {
    this.binsOf(ruleIndex).removeAt(binIndex);
  }

  protected applyTemplate(): void {
    const model = this.data.templates.find((t) => t.id === this.template.value);
    if (!model || !isScorecard(model.definition)) {
      return;
    }
    const d = model.definition;
    this.rules.clear();
    for (const r of rulesFromDefinition(d)) {
      this.addRule(r);
    }
    this.form.patchValue({
      basePoints: d.basePoints,
      pdo: d.pdo,
      anchorPoints: d.anchorPoints,
      anchorProbabilityPct: Math.round(probabilityFromOdds(d.anchorOdds) * 10000) / 100,
      description: model.description ? `Baseado em v${model.modelVersion}` : '',
    });
  }

  protected send(): void {
    this.submitted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid || this.formIssues().length || this.sending()) {
      return;
    }
    const v = this.form.getRawValue();
    this.sending.set(true);
    this.error.set(null);
    this.api
      .createScorecard(
        scorecardRequest({
          description: v.description,
          basePoints: v.basePoints ?? 0,
          pdo: v.pdo ?? 20,
          anchorPoints: v.anchorPoints ?? 0,
          anchorProbabilityPct: v.anchorProbabilityPct ?? 10,
          rules: v.rules as RuleForm[],
        }),
      )
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (model) => this.ref.close(model),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  private bin(b: BinForm): BinGroup {
    return this.fb.group({
      from: this.fb.control<number | null>(b.from),
      to: this.fb.control<number | null>(b.to),
      points: this.fb.control<number | null>(b.points),
    });
  }
}
