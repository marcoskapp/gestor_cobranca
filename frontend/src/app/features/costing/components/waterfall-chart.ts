import { Component, computed, input, signal } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { WaterfallStep } from '../data/margin.rules';

let nextId = 0;

const KIND_LABELS: Record<WaterfallStep['kind'], string> = { start: 'ponto de partida', cost: 'custo', subtotal: 'resultado' };

/**
 * Barras horizontais flutuantes sobre um eixo com zero (cascata): cada barra vai do valor acumulado antes do passo ao
 * valor depois dele; subtotais partem do zero; valores negativos ficam à esquerda do zero. Serve também para séries com
 * sinal (margem por mês: todos os passos são subtotais). Acessível como o `app-bar-chart`: título e resumo no `figure`,
 * valor escrito em cada linha (a cor nunca é o único canal — custos com "−", margem negativa com "(negativa)"), foco
 * por teclado em cada barra e tabela equivalente sob demanda.
 */
@Component({
  selector: 'app-waterfall-chart',
  imports: [Icon, InfoHint],
  template: `
    <figure class="wf" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s'">
      <div class="wf__head">
        <div class="d-flex align-items-center gap-1 min-w-0">
          <figcaption [id]="id + '-t'" class="wf__title">{{ title() }}</figcaption>
          @if (info(); as hint) {
            <app-info-hint [indicator]="hint" [label]="title()" />
          }
        </div>
        <button type="button" class="btn btn-link wf__toggle" (click)="table.set(!table())" [attr.aria-pressed]="table()">
          <app-icon [name]="table() ? 'bar-chart' : 'table'" />
          {{ table() ? 'Ver gráfico' : 'Ver tabela' }}
        </button>
      </div>
      <p [id]="id + '-s'" class="wf__summary">{{ summary() }}</p>
      @if (steps().length === 0) {
        <p class="wf__summary" role="status">Sem dados no período e filtros escolhidos.</p>
      } @else if (table()) {
        <div class="wf__table-wrap">
          <table class="wf__table">
            <thead>
              <tr><th scope="col">{{ categoryHeader() }}</th><th scope="col" class="num">{{ valueHeader() }}</th>@if (hasNotes()) {<th scope="col">Observação</th>}</tr>
            </thead>
            <tbody>
              @for (s of steps(); track s.key) {
                <tr [class.wf__total]="s.kind !== 'cost'">
                  <th scope="row">{{ s.label }}</th>
                  <td class="num app-numeric">{{ s.display }}</td>
                  @if (hasNotes()) {
                    <td>{{ notes()[s.key] ?? '' }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <ol class="wf__rows">
          @for (b of bars(); track b.step.key) {
            <li class="wf__row" [class.wf__row--total]="b.step.kind !== 'cost'">
              <span class="wf__label" [title]="b.step.label">{{ b.step.label }}</span>
              <span class="wf__track" tabindex="0" role="img"
                    [attr.aria-label]="b.step.label + ' (' + kindLabel(b.step.kind) + '): ' + b.step.display + (notes()[b.step.key] ? '. ' + notes()[b.step.key] : '')">
                <span class="wf__zero" [style.left.%]="zero()"></span>
                <span [class]="'wf__bar wf__bar--' + b.tone" [style.left.%]="b.left" [style.width.%]="b.width"></span>
              </span>
              <span class="wf__value app-numeric" [class.wf__value--negative]="b.tone === 'negative'">{{ b.step.display }}</span>
            </li>
          }
        </ol>
      }
    </figure>
  `,
  styles: `
    .wf { margin: 0; }
    .wf__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .wf__head > button { flex: none; white-space: nowrap; }
    .wf__title { font-size: var(--app-text-lg); font-weight: var(--app-weight-semibold); }
    .wf__summary { margin: 0 0 8px; font-size: var(--app-text-sm); color: var(--bs-secondary-color); }
    .wf__rows { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
    .wf__row {
      display: grid;
      grid-template-columns: minmax(120px, 28%) 1fr minmax(96px, max-content);
      align-items: center;
      gap: 12px;
    }
    .wf__row--total .wf__label,
    .wf__row--total .wf__value { font-weight: var(--app-weight-semibold); }
    .wf__label { overflow: hidden; font-size: var(--app-text-sm); text-overflow: ellipsis; white-space: nowrap; }
    .wf__track {
      position: relative;
      display: block;
      height: 16px;
      border-radius: 4px;
      background: var(--app-chart-track);
      outline-offset: 2px;
    }
    .wf__zero { position: absolute; top: -3px; bottom: -3px; width: 1px; background: var(--app-chart-axis); }
    .wf__bar { position: absolute; top: 2px; bottom: 2px; border-radius: 3px; }
    .wf__bar--positive { background: var(--app-chart-1); }
    .wf__bar--cost { background: var(--app-chart-2); }
    .wf__bar--negative { background: var(--bs-danger); }
    .wf__track:hover .wf__bar,
    .wf__track:focus-visible .wf__bar { filter: brightness(1.12); }
    .wf__value { font-size: var(--app-text-xs); text-align: right; white-space: nowrap; }
    .wf__value--negative { color: var(--bs-danger-text-emphasis); }
    .wf__table-wrap { overflow-x: auto; }
    .wf__table { width: 100%; border-collapse: collapse; font-size: var(--app-text-sm); }
    .wf__table th, .wf__table td { padding: 6px 8px; border-bottom: 1px solid var(--bs-border-color); text-align: left; }
    .wf__table .num { text-align: right; white-space: nowrap; }
    .wf__total th, .wf__total td { font-weight: var(--app-weight-semibold); }
    @media (max-width: 599.98px) {
      .wf__row { grid-template-columns: 1fr auto; gap: 2px 8px; }
      .wf__track { grid-column: 1 / -1; grid-row: 2; }
    }
  `,
})
export class WaterfallChart {
  readonly title = input.required<string>();
  /** Id do indicador no catálogo de informação contextual. */
  readonly info = input<string | null>(null);
  /** Texto alternativo: o que o gráfico mostra e o destaque principal. */
  readonly summary = input.required<string>();
  readonly steps = input.required<WaterfallStep[]>();
  readonly categoryHeader = input('Etapa');
  readonly valueHeader = input('Valor');
  /** Observação por passo (ex.: receita e custo do mês), na tabela e no rótulo acessível. */
  readonly notes = input<Record<string, string>>({});

  protected readonly id = `wf-${nextId++}`;
  protected readonly table = signal(false);
  protected readonly hasNotes = computed(() => Object.keys(this.notes()).length > 0);

  private readonly range = computed(() => {
    const values = this.steps().flatMap((s) => [s.start, s.end]);
    const lo = Math.min(0, ...values);
    const hi = Math.max(0, ...values);
    return { lo, span: hi - lo || 1 };
  });
  protected readonly zero = computed(() => ((0 - this.range().lo) / this.range().span) * 100);
  protected readonly bars = computed(() => {
    const { lo, span } = this.range();
    return this.steps().map((step) => {
      const from = Math.min(step.start, step.end);
      const size = Math.abs(step.end - step.start);
      const tone = step.kind === 'cost' ? 'cost' : step.end < 0 ? 'negative' : 'positive';
      return { step, tone, left: ((from - lo) / span) * 100, width: size > 0 ? Math.max(0.5, (size / span) * 100) : 0 };
    });
  });

  protected kindLabel(kind: WaterfallStep['kind']): string {
    return KIND_LABELS[kind];
  }
}
