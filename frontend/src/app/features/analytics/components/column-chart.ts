import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal } from '@angular/core';
import { ChartRow, ChartSeries } from './chart.models';
import { Icon } from '../../../shared/components/icon/icon';

let nextId = 0;

const H = 220;
const PAD = { top: 12, right: 8, bottom: 28, left: 64 };
const AXIS_MONEY = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 1 });
const AXIS_COUNT = new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 1 });

/**
 * Colunas por período (uma série) em SVG próprio: grade recessiva, eixo com 3 marcas arredondadas,
 * colunas ≤ 24 px com ponta arredondada, rótulo do último valor, dica por coluna (ponteiro e
 * teclado) e tabela equivalente. Sem dependência de biblioteca de gráficos.
 */
@Component({
  selector: 'app-column-chart',
  imports: [Icon],
  template: `
    <figure class="cc" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s'">
      <div class="cc__head">
        <figcaption [id]="id + '-t'" class="cc__title">{{ title() }}</figcaption>
        <button class="btn btn-link" type="button" (click)="table.set(!table())" [attr.aria-pressed]="table()">
          <app-icon [name]="table() ? 'bar-chart' : 'table'" />
          {{ table() ? 'Ver gráfico' : 'Ver tabela' }}
        </button>
      </div>
      <p [id]="id + '-s'" class="cc__summary">{{ summary() }}</p>
      @if (rows().length === 0) {
        <p class="cc__summary" role="status">Sem dados no período e filtros escolhidos.</p>
      } @else if (table()) {
        <div class="cc__table-wrap">
          <table class="cc__table">
            <thead>
              <tr><th scope="col">Período</th><th scope="col" class="num">{{ series().name }}</th>@if (hasNotes()) {<th scope="col">Observação</th>}</tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.key) {
                <tr><th scope="row">{{ r.label }}</th><td class="num app-numeric">{{ r.display[0] }}</td>@if (hasNotes()) {<td>{{ r.note ?? '' }}</td>}</tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <svg class="cc__svg" [attr.viewBox]="'0 0 ' + w() + ' ' + h" [attr.width]="w()" [attr.height]="h" role="group" [attr.aria-label]="title()" (pointerleave)="hover.set(null)">
          @for (t of ticks(); track t.value) {
            <line class="cc__grid" [attr.x1]="pad.left" [attr.x2]="w() - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
            <text class="cc__tick" [attr.x]="pad.left - 6" [attr.y]="t.y + 4" text-anchor="end">{{ t.label }}</text>
          }
          <line class="cc__axis" [attr.x1]="pad.left" [attr.x2]="w() - pad.right" [attr.y1]="baseline" [attr.y2]="baseline" />
          @for (b of bars(); track b.row.key; let last = $last) {
            <g
              class="cc__col"
              tabindex="0"
              role="img"
              [attr.aria-label]="b.row.label + ': ' + b.row.display[0]"
              (pointerenter)="hover.set(b)"
              (focus)="hover.set(b)"
              (blur)="hover.set(null)"
            >
              <rect class="cc__hit" [attr.x]="b.slotX" [attr.y]="pad.top" [attr.width]="b.slotW" [attr.height]="baseline - pad.top" />
              <path class="cc__bar" [attr.d]="b.path" [style.fill]="'var(' + series().color + ')'" />
              @if (b.showLabel) {
                <text class="cc__xlabel" [attr.x]="b.cx" [attr.y]="h - 8" text-anchor="middle">{{ b.row.label }}</text>
              }
              @if (last) {
                <text class="cc__value" [attr.x]="b.labelX" [attr.y]="b.top - 4" [attr.text-anchor]="b.labelAnchor">{{ b.row.display[0] }}</text>
              }
            </g>
          }
        </svg>
        @if (hover(); as hv) {
          <div class="cc__tip" role="status">
            <strong class="app-numeric">{{ hv.row.display[0] }}</strong>
            <span>{{ hv.row.label }}</span>
            @if (hv.row.note) {
              <span>{{ hv.row.note }}</span>
            }
          </div>
        }
      }
    </figure>
  `,
  styleUrl: './column-chart.scss',
})
export class ColumnChart {
  readonly title = input.required<string>();
  readonly summary = input.required<string>();
  readonly series = input.required<ChartSeries>();
  readonly rows = input.required<ChartRow[]>();
  /** Eixo em reais (marcas compactas "R$ 1,2 mil") ou contagem. */
  readonly money = input(true);
  /** Casas decimais das marcas do eixo de contagem (índices pequenos, como PSI ou AUC, pedem 2). */
  readonly axisFractionDigits = input(1);

  protected readonly id = `cc-${nextId++}`;
  /** Largura real do contêiner (px): o SVG é desenhado em escala 1:1, sem distorcer o texto. */
  protected readonly w = signal(640);
  protected readonly h = H;
  protected readonly pad = PAD;
  protected readonly baseline = H - PAD.bottom;
  protected readonly table = signal(false);
  protected readonly hover = signal<Bar | null>(null);
  protected readonly hasNotes = computed(() => this.rows().some((r) => !!r.note));

  /** Máximo do eixo arredondado para cima (1, 2, 2,5 ou 5 × 10ⁿ). */
  private readonly top = computed(() => niceCeil(Math.max(0, ...this.rows().map((r) => r.values[0] ?? 0))));

  protected readonly ticks = computed(() => {
    const top = this.top();
    const digits = this.axisFractionDigits();
    const count = digits === 1 ? AXIS_COUNT : new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: digits });
    return [0, top / 2, top].map((value) => ({
      value,
      y: this.y(value),
      label: (this.money() ? AXIS_MONEY : count).format(value).replace(/\s/g, '\u00a0'),
    }));
  });

  protected readonly bars = computed<Bar[]>(() => {
    const rows = this.rows();
    const slotW = (this.w() - PAD.left - PAD.right) / Math.max(1, rows.length);
    const barW = Math.min(24, Math.max(2, slotW - 2));
    const every = Math.ceil(rows.length / 8);
    return rows.map((row, i) => {
      const slotX = PAD.left + i * slotW;
      const cx = slotX + slotW / 2;
      const value = Math.max(0, row.values[0] ?? 0);
      const top = this.y(value);
      // rótulo do último valor: centrado na coluna, ou alinhado à direita perto da borda (nunca cortado)
      const nearEdge = cx + 60 > this.w() - PAD.right;
      return {
        row, slotX, slotW, cx, top,
        path: columnPath(cx - barW / 2, top, barW, this.baseline),
        showLabel: i % every === 0 || i === rows.length - 1,
        labelX: nearEdge ? cx + barW / 2 : cx,
        labelAnchor: nearEdge ? 'end' : 'middle',
      };
    });
  });

  constructor() {
    const host = inject(ElementRef<HTMLElement>);
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const element = host.nativeElement as HTMLElement;
      const measure = () => this.w.set(Math.max(240, Math.round(element.clientWidth || 640)));
      measure();
      if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver(measure);
        observer.observe(element);
        destroyRef.onDestroy(() => observer.disconnect());
      }
    });
  }

  private y(value: number): number {
    const top = this.top();
    return top > 0 ? this.baseline - (value / top) * (this.baseline - PAD.top) : this.baseline;
  }
}

interface Bar {
  row: ChartRow;
  slotX: number;
  slotW: number;
  cx: number;
  top: number;
  path: string;
  showLabel: boolean;
  labelX: number;
  labelAnchor: 'end' | 'middle';
}

export function niceCeil(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const exp = Math.pow(10, Math.floor(Math.log10(value)));
  const f = value / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return nice * exp;
}

/** Coluna com ponta superior arredondada (4 px) e base reta na linha de base. */
export function columnPath(x: number, top: number, width: number, baseline: number): string {
  const height = baseline - top;
  if (height <= 0) {
    return '';
  }
  const r = Math.min(4, width / 2, height);
  return `M${x},${baseline}V${top + r}Q${x},${top} ${x + r},${top}H${x + width - r}Q${x + width},${top} ${x + width},${top + r}V${baseline}Z`;
}
