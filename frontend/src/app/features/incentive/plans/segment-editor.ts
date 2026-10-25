import { Component, inject, input, output } from '@angular/core';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { DELAY_BUCKET_LABELS, PLAN_DELAY_BUCKETS } from '../data/incentive.labels';
import { DelayBucket, SegmentSelector } from '../data/incentive.models';
import { IdsField } from './ids-field';

let nextId = 0;

/** Seleção de segmento (credor × carteira × faixa de atraso); vazio = qualquer valor na dimensão. */
@Component({
  selector: 'app-segment-editor',
  imports: [IdsField],
  template: `
    @let s = segment();
    <fieldset class="se">
      <legend class="form-label mb-1">{{ legend() }}</legend>
      <div class="se__row">
        <app-ids-field [ids]="s.creditorIds" [source]="creditors" label="Credores" hint="Vazio = qualquer credor" (changed)="changed.emit()" />
        <app-ids-field [ids]="s.portfolioIds" [source]="portfolios" label="Carteiras" hint="Vazio = qualquer carteira" (changed)="changed.emit()" />
      </div>
      <div class="se__buckets" role="group" [attr.aria-label]="'Faixas de atraso — ' + legend()">
        <span class="app-muted small">Faixas de atraso (nenhuma = qualquer):</span>
        @for (b of buckets; track b) {
          <div class="form-check form-check-inline">
            <input class="form-check-input" type="checkbox" [id]="id + b" [checked]="s.delayBuckets.includes(b)" (change)="toggle(b)" />
            <label class="form-check-label" [for]="id + b">{{ labels[b] }}</label>
          </div>
        }
      </div>
    </fieldset>
  `,
  styles: `
    .se {
      margin: 0;
      padding: 0;
      border: 0;
      min-width: 0;
    }
    .se__row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 4px 16px;
    }
    .se__buckets {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
      margin-top: 4px;
    }
  `,
})
export class SegmentEditor {
  readonly segment = input.required<SegmentSelector>();
  readonly legend = input('Segmento');
  readonly changed = output<void>();
  protected readonly creditors = inject(CreditorLookup);
  protected readonly portfolios = inject(PortfolioLookup).source();
  protected readonly buckets = PLAN_DELAY_BUCKETS;
  protected readonly labels = DELAY_BUCKET_LABELS;
  protected readonly id = `se-${++nextId}-`;

  protected toggle(bucket: DelayBucket): void {
    const list = this.segment().delayBuckets;
    const i = list.indexOf(bucket);
    if (i >= 0) {
      list.splice(i, 1);
    } else {
      list.push(bucket);
    }
    this.changed.emit();
  }
}
