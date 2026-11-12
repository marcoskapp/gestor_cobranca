import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { REASON_MAX, REASON_MIN } from '../data/incentive.rules';
import { TiersApi } from './tier.api';
import { TierLevel, TierPerson } from './tier.models';
import { TIER_DESCRIPTIONS, TIER_ERROR_LABELS, TIER_LEVELS, quartileShort, tierExplanation } from './tier.rules';

export interface AdjustTierDialogData {
  roundId: string;
  person: TierPerson;
  defaultTier: TierLevel;
}

export const tierError = (err: unknown): string => {
  const e = toApiError(err);
  return (e.code && TIER_ERROR_LABELS[e.code]) || userMessage(e);
};

/**
 * Ajuste do nível final de uma pessoa na revisão (motivo obrigatório, 10 a 2000 caracteres, como o servidor). O nível
 * proposto e a explicação ficam à vista; o motivo vai para a trilha de auditoria.
 */
@Component({
  selector: 'app-tier-adjust-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Ajustar o nível de {{ data.person.name ?? 'pessoa' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <dl class="app-definition-list mb-3">
          <dt>Proposto</dt>
          <dd>Nível {{ data.person.proposedTier }}</dd>
          <dt>Quartis</dt>
          <dd>Qualidade {{ quartile(data.person.qualityQuartile) }} · eficiência {{ quartile(data.person.efficiencyQuartile) }}</dd>
          <dt>Por quê</dt>
          <dd>{{ explanation }}</dd>
        </dl>
        <app-field label="Nível final" [hint]="tierHint()">
          <select appInput formControlName="tier" ngbAutofocus>
            @for (t of levels; track t) {
              <option [ngValue]="t">Nível {{ t }}{{ t === data.person.proposedTier ? ' (proposto)' : '' }}</option>
            }
          </select>
        </app-field>
        <app-field label="Motivo do ajuste" [hint]="reasonHint()" [error]="'Escreva ao menos ' + min + ' caracteres.'">
          <textarea appInput formControlName="reason" rows="3" [attr.maxlength]="max" placeholder="Ex.: férias em metade da janela, troca de carteira, licença"></textarea>
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="busy()">Salvar ajuste</button>
      </div>
    </form>
  `,
})
export class AdjustTierDialog {
  protected readonly data = inject<AdjustTierDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TierPerson>>(DialogRef);
  private readonly api = inject(TiersApi);
  protected readonly levels = TIER_LEVELS;
  protected readonly min = REASON_MIN;
  protected readonly max = REASON_MAX;
  protected readonly quartile = quartileShort;
  protected readonly explanation = tierExplanation(this.data.person, this.data.defaultTier);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    tier: this.fb.control<TierLevel>(this.data.person.finalTier),
    reason: [
      this.data.person.adjustmentReason ?? '',
      [Validators.maxLength(REASON_MAX), (c: AbstractControl) => ((c.value as string).trim().length < REASON_MIN ? { minlength: true } : null)],
    ],
  });
  private readonly tier = toSignal(this.form.controls.tier.valueChanges.pipe(startWith(this.form.controls.tier.value)), { initialValue: this.form.controls.tier.value });
  private readonly reason = toSignal(this.form.controls.reason.valueChanges.pipe(startWith(this.form.controls.reason.value)), { initialValue: this.form.controls.reason.value });
  protected readonly tierHint = computed(() => TIER_DESCRIPTIONS[this.tier()]);
  protected readonly reasonHint = computed(() => `${this.reason().trim().length}/${REASON_MAX} · mínimo ${REASON_MIN} caracteres · vai para a trilha de auditoria`);

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { tier, reason } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.api
      .adjust(this.data.roundId, this.data.person.userId, tier, reason.trim())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: (p) => this.ref.close(p), error: (err: unknown) => this.error.set(tierError(err)) });
  }
}
