import { Component, computed, input, signal } from '@angular/core';
import { Icon } from '../../shared/components/icon/icon';

/** Ponto do acumulado por dia do período: valor do período atual e do anterior no mesmo dia (nulo se não houver). */
export interface CumulativeRow {
  day: number;
  current: number | null;
  previous: number | null;
}

let nextId = 0;
const W = 640;
const H = 220;
const PAD = { top: 14, right: 16, bottom: 28, left: 72 };
const MONEY = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const MONEY_AXIS = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', notation: 'compact', maximumFractionDigits: 1 });
const COUNT = new Intl.NumberFormat('pt-BR');
const COUNT_AXIS = new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 1 });

/**
 * Crescimento acumulado por dia do período: linha do período atual (cor de série 1), linha do período anterior no mesmo
 * dia (tinta neutra tracejada, só referência) e, opcionalmente, a projeção até o fim (tracejada na cor da série). Um
 * eixo, grade recessiva, legenda, dica por dia (ponteiro e teclado) e tabela equivalente.
 */
@Component({
  selector: 'app-cumulative-chart',
  imports: [Icon],
  template: `
    <figure class="cum" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s'">
      <div class="cum__head">
        <figcaption [id]="id + '-t'" class="cum__title">{{ title() }}</figcaption>
        <button class="btn btn-link btn-sm" type="button" (click)="table.set(!table())" [attr.aria-pressed]="table()">
          <app-icon [name]="table() ? 'bar-chart' : 'table'" />
          {{ table() ? 'Ver gráfico' : 'Ver tabela' }}
        </button>
      </div>
      <p [id]="id + '-s'" class="cum__summary">{{ summary() }}</p>
      <ul class="cum__legend" aria-hidden="true">
        <li><span class="cum__key cum__key--current"></span>{{ currentLabel() }}</li>
        <li><span class="cum__key cum__key--previous"></span>{{ previousLabel() }}</li>
        @if (projection(); as p) {
          <li><span class="cum__key cum__key--projection"></span>Projeção ({{ fmt(p.value) }})</li>
        }
      </ul>
      @if (!rows().length) {
        <p class="cum__summary" role="status">Sem dados no período.</p>
      } @else if (table()) {
        <div class="table-responsive">
          <table class="table table-sm">
            <thead><tr><th scope="col">Dia</th><th scope="col" class="text-end">{{ currentLabel() }}</th><th scope="col" class="text-end">{{ previousLabel() }}</th></tr></thead>
            <tbody>
              @for (r of rows(); track r.day) {
                <tr><th scope="row">{{ r.day }}</th><td class="text-end app-numeric">{{ r.current === null ? '—' : fmt(r.current) }}</td><td class="text-end app-numeric">{{ r.previous === null ? '—' : fmt(r.previous) }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <div class="cum__plot">
          <svg [attr.viewBox]="'0 0 ' + w + ' ' + h" role="group" [attr.aria-label]="title()" (pointerleave)="hover.set(null)"
               (pointermove)="onMove($event)" preserveAspectRatio="none">
            @for (t of ticks(); track t.value) {
              <line class="cum__grid" [attr.x1]="pad.left" [attr.x2]="w - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
              <text class="cum__tick" [attr.x]="pad.left - 6" [attr.y]="t.y + 4" text-anchor="end">{{ t.label }}</text>
            }
            <line class="cum__axis" [attr.x1]="pad.left" [attr.x2]="w - pad.right" [attr.y1]="baseline" [attr.y2]="baseline" />
            <text class="cum__tick" [attr.x]="pad.left" [attr.y]="h - 8">dia 1</text>
            <text class="cum__tick" [attr.x]="w - pad.right" [attr.y]="h - 8" text-anchor="end">dia {{ maxDay() }}</text>
            <path class="cum__line cum__line--previous" [attr.d]="previousPath()" />
            @if (projectionPath(); as d) {
              <path class="cum__line cum__line--projection" [attr.d]="d" />
            }
            <path class="cum__line cum__line--current" [attr.d]="currentPath()" />
            @if (lastPoint(); as lp) {
              <circle class="cum__dot" [attr.cx]="lp.x" [attr.cy]="lp.y" r="4" />
            }
            @if (hover(); as hv) {
              <line class="cum__cross" [attr.x1]="x(hv.day)" [attr.x2]="x(hv.day)" [attr.y1]="pad.top" [attr.y2]="baseline" />
            }
            @for (r of rows(); track r.day) {
              <rect class="cum__hit" [attr.x]="x(r.day) - step() / 2" [attr.y]="pad.top" [attr.width]="step()" [attr.height]="baseline - pad.top"
                    tabindex="0" role="img" [attr.aria-label]="'Dia ' + r.day + ': ' + describe(r)" (focus)="hover.set(r)" (blur)="hover.set(null)" />
            }
          </svg>
          @if (hover(); as hv) {
            <div class="cum__tip" role="status" [style.left.%]="(x(hv.day) / w) * 100">
              <strong>Dia {{ hv.day }}</strong>
              <span><span class="cum__key cum__key--current"></span>{{ hv.current === null ? '—' : fmt(hv.current) }}</span>
              <span><span class="cum__key cum__key--previous"></span>{{ hv.previous === null ? '—' : fmt(hv.previous) }}</span>
            </div>
          }
        </div>
      }
    </figure>
  `,
  styles: `
    .cum { margin: 0; }
    .cum__head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
    .cum__title { font-weight: 600; }
    .cum__summary { font-size: var(--app-text-xs); color: var(--bs-secondary-color); margin: 2px 0 6px; }
    .cum__legend { display: flex; flex-wrap: wrap; gap: 4px 16px; list-style: none; padding: 0; margin: 0 0 6px;
      font-size: var(--app-text-xs); color: var(--bs-secondary-color); }
    .cum__legend li { display: inline-flex; align-items: center; gap: 6px; }
    .cum__key { display: inline-block; width: 16px; height: 0; border-top: 2px solid var(--app-chart-1); vertical-align: middle; }
    .cum__key--previous { border-top: 2px dashed var(--bs-secondary-color); }
    .cum__key--projection { border-top: 2px dashed var(--app-chart-1); opacity: 0.6; }
    .cum__plot { position: relative; }
    .cum__plot svg { width: 100%; height: 220px; display: block; overflow: visible; }
    .cum__grid { stroke: var(--app-chart-grid); stroke-width: 1; }
    .cum__axis { stroke: var(--app-chart-axis); stroke-width: 1; }
    .cum__tick { font-size: 11px; fill: var(--bs-secondary-color); }
    .cum__line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; vector-effect: non-scaling-stroke; }
    .cum__line--current { stroke: var(--app-chart-1); }
    .cum__line--previous { stroke: var(--bs-secondary-color); stroke-dasharray: 4 4; }
    .cum__line--projection { stroke: var(--app-chart-1); stroke-dasharray: 6 4; opacity: 0.6; }
    .cum__dot { fill: var(--app-chart-1); stroke: var(--app-surface, #fff); stroke-width: 2; }
    .cum__cross { stroke: var(--bs-secondary-color); stroke-width: 1; vector-effect: non-scaling-stroke; }
    .cum__hit { fill: transparent; outline: none; }
    .cum__hit:focus-visible { fill: color-mix(in srgb, var(--app-chart-1) 12%, transparent); }
    .cum__tip { position: absolute; top: 0; transform: translateX(-50%); pointer-events: none; z-index: 1;
      background: var(--app-surface, #fff); border: 1px solid var(--app-border); border-radius: 6px; padding: 4px 8px;
      font-size: var(--app-text-xs); display: flex; flex-direction: column; gap: 2px; white-space: nowrap; box-shadow: var(--app-shadow-sm, none); }
    .cum__tip span { display: inline-flex; align-items: center; gap: 6px; }
  `,
})
export class CumulativeChart {
  readonly title = input.required<string>();
  readonly summary = input('');
  readonly rows = input.required<CumulativeRow[]>();
  readonly money = input(true);
  readonly currentLabel = input('Período atual');
  readonly previousLabel = input('Período anterior');
  /** Valor projetado no último dia do período atual. */
  readonly projection = input<{ day: number; value: number } | null>(null);

  protected readonly id = `cum-${nextId++}`;
  protected readonly w = W;
  protected readonly h = H;
  protected readonly pad = PAD;
  protected readonly baseline = H - PAD.bottom;
  protected readonly table = signal(false);
  protected readonly hover = signal<CumulativeRow | null>(null);

  protected readonly maxDay = computed(() => Math.max(2, this.projection()?.day ?? 0, ...this.rows().map((r) => r.day)));
  private readonly maxValue = computed(() => {
    const values = this.rows().flatMap((r) => [r.current ?? 0, r.previous ?? 0]);
    return Math.max(1, this.projection()?.value ?? 0, ...values);
  });
  protected readonly step = computed(() => (W - PAD.left - PAD.right) / Math.max(1, this.maxDay() - 1));
  protected readonly ticks = computed(() => {
    const top = niceCeil(this.maxValue());
    return [0, top / 2, top].map((v) => ({ value: v, y: this.yOf(v, top), label: this.money() ? MONEY_AXIS.format(v) : COUNT_AXIS.format(v) }));
  });
  protected readonly currentPath = computed(() => this.path(this.rows().filter((r) => r.current !== null).map((r) => [r.day, r.current!])));
  protected readonly previousPath = computed(() => this.path(this.rows().filter((r) => r.previous !== null).map((r) => [r.day, r.previous!])));
  protected readonly lastPoint = computed(() => {
    const last = [...this.rows()].reverse().find((r) => r.current !== null);
    return last ? { x: this.x(last.day), y: this.y(last.current!) } : null;
  });
  protected readonly projectionPath = computed(() => {
    const p = this.projection();
    const last = [...this.rows()].reverse().find((r) => r.current !== null);
    return p && last && p.day > last.day ? this.path([[last.day, last.current!], [p.day, p.value]]) : null;
  });

  protected x(day: number): number {
    return PAD.left + (day - 1) * this.step();
  }

  protected y(value: number): number {
    return this.yOf(value, niceCeil(this.maxValue()));
  }

  protected fmt(value: number): string {
    return this.money() ? MONEY.format(value) : COUNT.format(value);
  }

  protected describe(r: CumulativeRow): string {
    return `${this.currentLabel()} ${r.current === null ? 'sem dado' : this.fmt(r.current)}; ${this.previousLabel()} ${
      r.previous === null ? 'sem dado' : this.fmt(r.previous)}`;
  }

  protected onMove(event: PointerEvent): void {
    const svg = event.currentTarget as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / rect.width) * W;
    const day = Math.round((px - PAD.left) / this.step()) + 1;
    this.hover.set(this.rows().find((r) => r.day === day) ?? null);
  }

  private yOf(value: number, top: number): number {
    return this.baseline - (value / top) * (this.baseline - PAD.top);
  }

  private path(points: [number, number][]): string {
    return points.map(([d, v], i) => `${i === 0 ? 'M' : 'L'}${this.x(d).toFixed(1)},${this.y(v).toFixed(1)}`).join(' ');
  }
}

function niceCeil(value: number): number {
  const exp = Math.pow(10, Math.floor(Math.log10(value)));
  const f = value / exp;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * exp;
}

/** Junta os acumulados atual e anterior pelo dia do período. */
export function mergeCumulative<T extends { day: number }>(
  current: T[],
  previous: T[],
  value: (p: T) => number,
): CumulativeRow[] {
  const days = Math.max(current.length ? current[current.length - 1].day : 0, previous.length ? previous[previous.length - 1].day : 0);
  const cur = new Map(current.map((p) => [p.day, value(p)]));
  const prev = new Map(previous.map((p) => [p.day, value(p)]));
  const rows: CumulativeRow[] = [];
  for (let d = 1; d <= days; d++) {
    rows.push({ day: d, current: cur.get(d) ?? null, previous: prev.get(d) ?? null });
  }
  return rows;
}
