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
import { ScoringSettingsApi } from '../data/scoring.api';
import { LEGAL_BASIS_HINTS, LEGAL_BASIS_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { LegalBasis, ScoringSettingsView } from '../data/scoring.models';

export interface LegalBasisDialogData {
  current: ScoringSettingsView;
}

const BASES: readonly LegalBasis[] = ['LEGITIMATE_INTEREST', 'CREDIT_PROTECTION'];

/**
 * Registro (ou troca) da base legal do tratamento para o score (LGPD art. 7º). Sem ele nenhum
 * fato é coletado e nenhum score é calculado; o registro é auditado com o valor anterior.
 */
@Component({
  selector: 'app-legal-basis-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.current.scoringEnabled ? 'Alterar a base legal do score' : 'Registrar a base legal do score' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          O score usa somente dados da dívida e do relacionamento de cobrança, para priorizar o atendimento. Registre o fundamento
          legal aprovado pelo encarregado de dados (DPO).
        </p>
        @if (!data.current.scoringEnabled) {
          <div class="alert alert-info d-flex gap-2 align-items-start" role="note">
            <app-icon name="info-circle" />
            <span>Ao registrar, o sistema passa a coletar os fatos de cobrança e a calcular o score com o scorecard padrão (campeão inicial).</span>
          </div>
        }
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <fieldset class="mb-3">
          <legend class="form-label">Base legal</legend>
          @for (b of bases; track b) {
            <div class="form-check">
              <input class="form-check-input" type="radio" [id]="'lb-' + b" [value]="b" formControlName="legalBasis" />
              <label class="form-check-label" [for]="'lb-' + b">{{ labels[b] }}</label>
            </div>
          }
        </fieldset>
        <app-field label="Referência do registro" [hint]="hint()" [hintEnd]="referenceLength() + ' / 500'" error="Informe a referência com 5 a 500 caracteres.">
          <textarea appInput formControlName="reference" rows="3" maxlength="500" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="sending()">{{ data.current.scoringEnabled ? 'Salvar base legal' : 'Registrar e habilitar o score' }}</button>
      </div>
    </form>
  `,
})
export class LegalBasisDialog {
  protected readonly data = inject<LegalBasisDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ScoringSettingsView>>(DialogRef);
  private readonly api = inject(ScoringSettingsApi);
  protected readonly bases = BASES;
  protected readonly labels = LEGAL_BASIS_LABELS;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    legalBasis: this.fb.control<LegalBasis>(this.data.current.legalBasis ?? 'LEGITIMATE_INTEREST', [Validators.required]),
    reference: [this.data.current.legalBasisReference ?? '', [AppValidators.notBlank, Validators.minLength(5), Validators.maxLength(500)]],
  });
  private readonly basis = toSignal(this.form.controls.legalBasis.valueChanges, { initialValue: this.form.controls.legalBasis.value });
  private readonly reference = toSignal(this.form.controls.reference.valueChanges, { initialValue: this.form.controls.reference.value });
  protected readonly hint = computed(() => LEGAL_BASIS_HINTS[this.basis()]);
  protected readonly referenceLength = computed(() => this.reference().length);

  protected send(): void {
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.sending.set(true);
    this.error.set(null);
    this.api
      .registerLegalBasis({ legalBasis: v.legalBasis, reference: v.reference.trim() })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (saved) => this.ref.close(saved),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
