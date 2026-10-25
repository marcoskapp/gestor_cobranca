import { Component, computed, input } from '@angular/core';
import { BAND_DESCRIPTIONS, BANDS } from '../data/scoring.labels';
import { ScoreBand } from '../data/scoring.models';

/**
 * Faixa do score (A–E) em texto ("Faixa B · propensão alta") com uma régua de cinco segmentos
 * decorativa. Tom neutro de propósito: a faixa ordena o atendimento e não é um julgamento do
 * devedor (sem verde/vermelho); a posição é dada pelo texto, nunca só pela cor (WCAG 1.4.1).
 */
@Component({
  selector: 'app-score-band',
  host: { class: 'sb' },
  template: `
    <span class="sb__meter" aria-hidden="true">
      @for (b of bands; track b) {
        <span class="sb__seg" [class.sb__seg--on]="b === band()"></span>
      }
    </span>
    <span class="sb__text"><strong>Faixa {{ band() }}</strong>@if (!compact()) { · {{ description() }}}</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0 10px 0 6px;
      border: 1px solid var(--bs-primary-border-subtle);
      border-radius: var(--bs-border-radius-pill);
      background: var(--bs-primary-bg-subtle);
      color: var(--bs-primary-text-emphasis);
      font-size: var(--app-text-sm);
      line-height: 1.5rem;
      white-space: nowrap;
    }
    .sb__meter {
      display: inline-flex;
      gap: 2px;
    }
    .sb__seg {
      width: 6px;
      height: 10px;
      border-radius: 2px;
      border: 1px solid currentColor;
      opacity: 0.45;
    }
    .sb__seg--on {
      background: currentColor;
      opacity: 1;
    }
    .sb__text strong {
      font-weight: 600;
    }
    @media (forced-colors: active) {
      .sb__seg--on {
        background: CanvasText;
      }
    }
  `,
})
export class ScoreBandChip {
  readonly band = input.required<ScoreBand>();
  /** Só a letra (tabelas densas); a descrição fica no texto da coluna. */
  readonly compact = input(false);
  protected readonly bands = BANDS;
  protected readonly description = computed(() => BAND_DESCRIPTIONS[this.band()] ?? '');
}
