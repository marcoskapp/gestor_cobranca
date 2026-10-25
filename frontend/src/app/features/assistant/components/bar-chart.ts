import { Component, input } from '@angular/core';
import { Bar } from '../data/assistant.rules';

/**
 * Gráfico de barras horizontais acessível: cada barra tem o rótulo e o valor em texto (a barra é
 * só reforço visual, `aria-hidden`), a lista é lida em ordem pelos leitores de tela e o
 * significado nunca depende só da cor (WCAG 1.4.1).
 */
@Component({
  selector: 'app-bar-chart',
  template: `
    <figure class="bc" [attr.aria-labelledby]="id + '-t'">
      <figcaption class="bc__title" [id]="id + '-t'">{{ title() }}</figcaption>
      @if (data().length) {
        <ul class="bc__list">
          @for (b of data(); track b.key) {
            <li class="bc__row">
              <span class="bc__label">{{ b.label }}</span>
              <span class="bc__track" aria-hidden="true"><span class="bc__bar" [class.bc__bar--alt]="b.key === highlight()" [style.width.%]="b.share * 100"></span></span>
              <span class="bc__value app-numeric">{{ format()(b) }}</span>
            </li>
          }
        </ul>
      } @else {
        <p class="app-muted bc__empty">{{ empty() }}</p>
      }
    </figure>
  `,
  styles: `
    .bc {
      margin: 0;
    }
    .bc__title {
      margin-bottom: 8px;
      font-weight: 600;
      font-size: var(--app-text-sm);
    }
    .bc__list {
      display: grid;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .bc__row {
      display: grid;
      grid-template-columns: minmax(110px, 38%) 1fr auto;
      align-items: center;
      gap: 8px;
      font-size: var(--app-text-xs);
    }
    .bc__label {
      overflow-wrap: anywhere;
    }
    .bc__track {
      height: 10px;
      border-radius: var(--bs-border-radius-pill);
      background: var(--bs-border-color);
      overflow: hidden;
    }
    .bc__bar {
      display: block;
      height: 100%;
      min-width: 2px;
      border-radius: var(--bs-border-radius-pill);
      background: var(--bs-primary);
    }
    .bc__bar--alt {
      background: var(--bs-warning-text-emphasis);
    }
    .bc__value {
      min-width: 56px;
      text-align: right;
      font-weight: 600;
    }
    .bc__empty {
      margin: 0;
      font-size: var(--app-text-xs);
    }
  `,
})
export class BarChart {
  private static seq = 0;
  protected readonly id = `bc-${++BarChart.seq}`;
  readonly title = input.required<string>();
  readonly data = input.required<readonly Bar[]>();
  readonly empty = input('Sem dados no período.');
  /** Barra destacada (outra cor + o rótulo já diz qual é). */
  readonly highlight = input<string | null>(null);
  readonly format = input<(b: Bar) => string>((b) => `${b.value} (${Math.round(b.ofTotal * 100)}%)`);
}
