import { Component, input } from '@angular/core';
import { Amount } from '../../../shared/components/amount/amount';
import { STEP_LABELS } from '../data/settlement.labels';
import { CalculationView } from '../data/settlement.models';

/**
 * Memória de cálculo da comissão (recebimento, estorno ou simulação): cada passo com base,
 * percentual e valor, e o efeito no repasse destacado. Valores negativos no estorno.
 */
@Component({
  selector: 'app-calculation-memory',
  imports: [Amount],
  template: `
    @let c = calculation();
    <div class="cm-scroll">
      <table class="cm-table" [attr.aria-label]="caption()">
        <thead>
          <tr>
            <th scope="col">Passo</th>
            <th scope="col" class="cm-end">Base</th>
            <th scope="col" class="cm-end">%</th>
            <th scope="col" class="cm-end">Valor</th>
          </tr>
        </thead>
        <tbody>
          @for (m of c.memory; track $index) {
            <tr [class.cm-total]="m.step === 'TRANSFER_EFFECT'">
              <th scope="row">
                {{ steps[m.step] || m.step }}
                <div class="app-muted cm-small">{{ m.description }}</div>
              </th>
              <td class="cm-end">@if (m.base !== null) {<app-amount [value]="m.base" />} @else {—}</td>
              <td class="cm-end app-numeric">{{ m.rate !== null ? percent(m.rate) : '—' }}</td>
              <td class="cm-end">@if (m.amount !== null) {<app-amount [value]="m.amount" [signed]="m.step === 'TRANSFER_EFFECT'" />} @else {—}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <p class="app-muted cm-small">Regra aplicada: <strong>{{ c.ruleCode }}</strong> ({{ percent(c.rate) }}). Centavos arredondados pela regra bancária em cada linha.</p>
  `,
  styles: `
    .cm-scroll {
      overflow-x: auto;
    }
    .cm-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
      th,
      td {
        padding: 6px 8px;
        border-bottom: 1px solid var(--bs-border-color);
        text-align: left;
        font-weight: 400;
        vertical-align: top;
      }
      thead th {
        font-weight: 600;
        color: var(--bs-secondary-color);
      }
    }
    .cm-end {
      text-align: right !important;
    }
    .cm-total {
      font-weight: 600;
      background: var(--bs-tertiary-bg);
    }
    .cm-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class CalculationMemory {
  readonly calculation = input.required<CalculationView>();
  readonly caption = input('Memória de cálculo');
  protected readonly steps = STEP_LABELS;

  protected percent(value: number): string {
    return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 4 }).format(value)}%`;
  }
}
