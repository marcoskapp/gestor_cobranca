import { Component, input } from '@angular/core';

/**
 * Cartão de indicador: rótulo, valor (projetado — texto ou `app-amount`) e o denominador sempre
 * visível ("1.100 de 4.000"), para que nenhuma taxa apareça sem a base.
 */
@Component({
  selector: 'app-kpi-card',
  host: { class: 'kpi', role: 'group', '[attr.aria-label]': 'label()' },
  template: `
    <span class="kpi__label">{{ label() }}</span>
    <span class="kpi__value app-numeric"><ng-content /></span>
    @if (detail()) {
      <span class="kpi__detail">{{ detail() }}</span>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
      /* o tamanho do valor acompanha a largura do próprio cartão (unidades cqi), não a da tela */
      container-type: inline-size;
      padding: 12px 16px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    /* Valor fluido e sem estouro: a fonte encolhe com o cartão (de 22px até 14px); valores compostos
       ("12 de 40 · 30%") quebram entre as partes — sobrepõe o nowrap de .app-numeric —, e só
       em último caso dentro de uma palavra. Valores monetários (app-amount) não quebram ao meio. */
    .kpi__value {
      font-size: clamp(var(--app-text-md), 10cqi, var(--app-text-xl));
      font-weight: 600;
      line-height: var(--app-line-tight);
      white-space: normal;
      overflow-wrap: anywhere;
    }
    .kpi__detail {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
  `,
})
export class KpiCard {
  readonly label = input.required<string>();
  /** Base do indicador (denominador) ou explicação curta. */
  readonly detail = input<string | null>(null);
}
