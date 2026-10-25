import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { ScoringModelsApi } from '../data/scoring.api';
import { MODEL_KIND_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { DecisionKind, ModelView } from '../data/scoring.models';
import { VERDICT_TEXT, compareMetrics } from '../data/scoring.rules';

export interface DecisionDialogData {
  decision: DecisionKind;
  model: ModelView;
  champion: ModelView | null;
}

/** Palavra que confirma a promoção (troca o modelo de toda a assessoria). */
export const PROMOTE_WORD = 'PROMOVER';

/**
 * Decisão manual sobre o desafiante (promoção a campeão ou rejeição): mostra a comparação que fica
 * registrada com a decisão (validação do desafiante × campeão nas mesmas observações), exige
 * justificativa de 10 a 1000 caracteres e, na promoção, a palavra de confirmação.
 */
@Component({
  selector: 'app-decision-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0 d-flex align-items-center gap-2">
        @if (!promote) {
          <app-icon class="text-danger-emphasis" name="exclamation-triangle" />
        }
        {{ promote ? 'Promover o desafiante a campeão' : 'Rejeitar o desafiante' }}
      </h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          @if (promote) {
            O modelo v{{ data.model.modelVersion }} passa a calcular o score de todos os casos a partir de agora
            {{ data.champion ? 'e o campeão v' + data.champion.modelVersion + ' é aposentado' : '' }}. A escoragem em andamento termina com o campeão anterior.
          } @else {
            O modelo v{{ data.model.modelVersion }} deixa de ser calculado em sombra e não poderá mais ser promovido. O campeão continua o mesmo.
          }
        </p>
        <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
          <dt>Desafiante</dt>
          <dd>v{{ data.model.modelVersion }} · {{ kinds[data.model.kind] }}</dd>
          <dt>Campeão atual</dt>
          <dd>{{ data.champion ? 'v' + data.champion.modelVersion + ' · ' + kinds[data.champion.kind] : 'Nenhum' }}</dd>
        </dl>
        <div class="table-responsive mb-3">
          <table class="table table-sm align-middle mb-0">
            <caption class="dd-caption">Mesmas observações de validação{{ data.model.metrics.backtest ? ' (retroteste no histórico)' : '' }}</caption>
            <thead>
              <tr>
                <th scope="col">Métrica</th>
                <th scope="col" class="text-end">Desafiante</th>
                <th scope="col" class="text-end">Campeão</th>
                <th scope="col">Leitura</th>
              </tr>
            </thead>
            <tbody>
              @for (c of comparison; track c.key) {
                <tr>
                  <th scope="row" class="fw-normal">{{ c.label }}</th>
                  <td class="text-end app-numeric">{{ c.challenger }}</td>
                  <td class="text-end app-numeric">{{ c.champion }}</td>
                  <td>{{ c.verdict ? verdicts[c.verdict] : 'Sem comparação' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field
          class="mb-3"
          label="Justificativa"
          hint="Registrada com a comparação acima na trilha de auditoria (10 a 1000 caracteres)."
          [hintEnd]="length() + ' / 1000'"
          error="Escreva de 10 a 1000 caracteres."
        >
          <textarea appInput formControlName="justification" rows="4" maxlength="1000" required></textarea>
        </app-field>
        @if (promote) {
          <app-field [label]="'Digite ' + word + ' para confirmar'">
            <input appInput formControlName="confirmation" autocomplete="off" spellcheck="false" />
          </app-field>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn" [class.btn-primary]="promote" [class.btn-danger]="!promote" [disabled]="!confirmed() || sending()">
          {{ promote ? 'Promover a campeão' : 'Rejeitar desafiante' }}
        </button>
      </div>
    </form>
  `,
  styles: `
    .dd-caption {
      caption-side: top;
      padding-top: 0;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
  `,
})
export class DecisionDialog {
  protected readonly data = inject<DecisionDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ModelView>>(DialogRef);
  private readonly api = inject(ScoringModelsApi);
  protected readonly promote = this.data.decision === 'PROMOTE';
  protected readonly kinds = MODEL_KIND_LABELS;
  protected readonly verdicts = VERDICT_TEXT;
  protected readonly word = PROMOTE_WORD;
  protected readonly comparison = compareMetrics(this.data.model.metrics.validation, this.data.model.metrics.championOnValidation);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    justification: ['', [AppValidators.notBlank, Validators.minLength(10), Validators.maxLength(1000)]],
    confirmation: [''],
  });
  private readonly justification = toSignal(this.form.controls.justification.valueChanges, { initialValue: '' });
  private readonly typed = toSignal(this.form.controls.confirmation.valueChanges, { initialValue: '' });
  protected readonly length = computed(() => this.justification().length);
  protected readonly confirmed = computed(() => !this.promote || this.typed().trim().toUpperCase() === PROMOTE_WORD);

  protected send(): void {
    const justification = this.form.getRawValue().justification.trim();
    if (this.form.invalid || justification.length < 10 || !this.confirmed() || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    const call = this.promote ? this.api.promote(this.data.model.id, justification) : this.api.reject(this.data.model.id, justification);
    call.pipe(finalize(() => this.sending.set(false))).subscribe({
      next: (model) => this.ref.close(model),
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }
}
