import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import {
  MIN_RATE_SHOWN,
  QUADRANTS,
  Quadrant,
  ScatterModel,
  ScatterPoint,
  cutsText,
  logDomain,
  percentText,
  pointLabel,
  rateDomain,
} from '../data/margin-scatter.rules';

let nextId = 0;

const H = 320;
const PAD = { top: 22, right: 16, bottom: 34, left: 56 };
const AXIS_MONEY = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', notation: 'compact', maximumFractionDigits: 1 });
const AXIS_PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 0 });
const QUADRANT_ORDER: readonly Quadrant[] = ['scale', 'niche', 'consumes', 'review'];

interface Mark {
  p: ScatterPoint;
  cx: number;
  cy: number;
  r: number;
  clamped: boolean;
  /** Losango (margem negativa) ou círculo. */
  path: string | null;
}

/**
 * Mapa de dispersão carteira × margem líquida % × volume recuperado (margem-por-carteira.md §14.2), em SVG próprio:
 * x = recuperado em escala logarítmica, y = margem líquida % da receita, área do ponto pela receita, cortes tracejados
 * com os quatro quadrantes rotulados. Margem negativa: vermelho **e** losango, com "(negativa)" no rótulo. Cada ponto
 * é focável (rótulo completo, dica no ponteiro e no foco) e abre o painel filtrado pela carteira (clique ou Enter);
 * "Ver tabela" mostra o equivalente com link para o mesmo filtro.
 */
@Component({
  selector: 'app-margin-scatter',
  imports: [Icon, InfoHint, RouterLink],
  template: `
    <figure class="sc" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s ' + id + '-c'">
      <div class="sc__head">
        <div class="d-flex align-items-center gap-1 min-w-0">
          <figcaption [id]="id + '-t'" class="sc__title">{{ title() }}</figcaption>
          @if (info(); as hint) {
            <app-info-hint [indicator]="hint" [label]="title()" />
          }
        </div>
        @if (model().points.length) {
          <button type="button" class="btn btn-link sc__toggle" (click)="table.set(!table())" [attr.aria-pressed]="table()">
            <app-icon [name]="table() ? 'bar-chart' : 'table'" />
            {{ table() ? 'Ver gráfico' : 'Ver tabela' }}
          </button>
        }
      </div>
      <p [id]="id + '-s'" class="sc__summary">{{ summary() }}</p>
      @if (model().points.length) {
        <p [id]="id + '-c'" class="sc__cuts">{{ cuts() }}</p>
      }
      @if (!model().points.length) {
        <p class="sc__summary" role="status">Nenhuma carteira com receita e valor recuperado no período e filtros escolhidos.</p>
      } @else if (table()) {
        <div class="sc__table-wrap">
          <table class="sc__table">
            <thead>
              <tr>
                <th scope="col">Carteira</th>
                <th scope="col">Credor</th>
                <th scope="col" class="num">Recuperado</th>
                <th scope="col" class="num">Receita</th>
                <th scope="col" class="num">Margem líquida</th>
                <th scope="col">Quadrante</th>
              </tr>
            </thead>
            <tbody>
              @for (p of tableRows(); track p.key) {
                <tr>
                  <th scope="row"><a [routerLink]="[]" [queryParams]="queryParamsFor()(p)" queryParamsHandling="merge">{{ p.portfolioName }}</a></th>
                  <td>{{ p.creditorName }}</td>
                  <td class="num app-numeric">{{ money(p.recovered) }}</td>
                  <td class="num app-numeric">{{ money(p.revenue) }}</td>
                  <td class="num app-numeric" [class.sc__neg]="p.negative">{{ percent(p.rate) }}{{ p.negative ? ' (negativa)' : '' }}</td>
                  <td>{{ quadrants[p.quadrant].label }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <svg class="sc__svg" [attr.viewBox]="'0 0 ' + w() + ' ' + h" [attr.width]="w()" [attr.height]="h" role="group"
             [attr.aria-label]="title() + ': ' + model().points.length + ' carteiras'" (pointerleave)="hover.set(null)">
          @for (t of yTicks(); track t.value) {
            <line class="sc__grid" [attr.x1]="pad.left" [attr.x2]="w() - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
            <text class="sc__tick" [attr.x]="pad.left - 6" [attr.y]="t.y + 4" text-anchor="end">{{ t.label }}</text>
          }
          @for (t of xTicks(); track t.value) {
            <text class="sc__tick" [attr.x]="t.x" [attr.y]="h - pad.bottom + 16" [attr.text-anchor]="t.anchor">{{ t.label }}</text>
          }
          <line class="sc__axis" [attr.x1]="pad.left" [attr.x2]="w() - pad.right" [attr.y1]="h - pad.bottom" [attr.y2]="h - pad.bottom" />
          <text class="sc__axis-label" [attr.x]="w() - pad.right" [attr.y]="h - 4" text-anchor="end">Recuperado (escala logarítmica) →</text>
          <text class="sc__axis-label" [attr.x]="pad.left" [attr.y]="12" text-anchor="start">↑ Margem líquida (% da receita)</text>
          <!-- cortes dos quadrantes -->
          <line class="sc__cut" [attr.x1]="cutX()" [attr.x2]="cutX()" [attr.y1]="pad.top" [attr.y2]="h - pad.bottom" />
          <line class="sc__cut" [attr.x1]="pad.left" [attr.x2]="w() - pad.right" [attr.y1]="cutY()" [attr.y2]="cutY()" />
          <text class="sc__quadrant" [attr.x]="w() - pad.right - 4" [attr.y]="pad.top + 12" text-anchor="end">{{ quadrants.scale.label }}</text>
          <text class="sc__quadrant" [attr.x]="pad.left + 4" [attr.y]="pad.top + 12" text-anchor="start">{{ quadrants.niche.label }}</text>
          <text class="sc__quadrant" [attr.x]="w() - pad.right - 4" [attr.y]="h - pad.bottom - 6" text-anchor="end">{{ quadrants.consumes.label }}</text>
          <text class="sc__quadrant" [attr.x]="pad.left + 4" [attr.y]="h - pad.bottom - 6" text-anchor="start">{{ quadrants.review.label }}</text>
          @for (m of marks(); track m.p.key) {
            <g class="sc__point" tabindex="0" role="link"
               [attr.aria-label]="label(m.p) + ' Abre o painel filtrado por esta carteira.'"
               (pointerenter)="hover.set(m)" (focus)="hover.set(m)" (blur)="hover.set(null)"
               (click)="selected.emit(m.p)" (keydown.enter)="selected.emit(m.p)">
              <circle class="sc__hit" [attr.cx]="m.cx" [attr.cy]="m.cy" [attr.r]="m.r + 6" />
              @if (m.path) {
                <path class="sc__mark sc__mark--neg" [attr.d]="m.path" />
              } @else {
                <circle class="sc__mark" [attr.cx]="m.cx" [attr.cy]="m.cy" [attr.r]="m.r" />
              }
            </g>
          }
        </svg>
        <p class="sc__legend">
          <span><svg width="10" height="10" aria-hidden="true"><circle cx="5" cy="5" r="4" class="sc__mark" /></svg> Margem positiva</span>
          <span><svg width="10" height="10" aria-hidden="true"><path d="M5,0L10,5L5,10L0,5Z" class="sc__mark sc__mark--neg" /></svg> Margem negativa</span>
          <span>Tamanho do ponto: receita.</span>
          @if (hasClamped()) {
            <span>Abaixo de −100%: na borda inferior.</span>
          }
        </p>
        @if (hover(); as hv) {
          <div class="sc__tip" role="status">
            <strong>{{ hv.p.portfolioName }}</strong>
            <span>{{ hv.p.creditorName }}</span>
            <span class="app-numeric">Recuperado {{ money(hv.p.recovered) }} · receita {{ money(hv.p.revenue) }}</span>
            <span class="app-numeric" [class.sc__neg]="hv.p.negative">Margem líquida {{ percent(hv.p.rate) }}{{ hv.p.negative ? ' (negativa)' : '' }} da receita</span>
            <span>{{ quadrants[hv.p.quadrant].label }} — {{ quadrants[hv.p.quadrant].reading }}</span>
            <span class="sc__tip-hint">Clique ou Enter: filtra o painel por esta carteira.</span>
          </div>
        }
      }
      @if (model().withoutBasis > 0) {
        <p class="sc__outside">
          {{ model().withoutBasis }} {{ model().withoutBasis === 1 ? 'carteira sem base' : 'carteiras sem base' }}
          (sem receita ou sem valor recuperado no período): fora do mapa, continuam na tabela por credor.
        </p>
      }
    </figure>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .sc { position: relative; margin: 0; }
    .sc__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .sc__head > button { flex: none; white-space: nowrap; }
    .sc__title { font-size: var(--app-text-lg); font-weight: var(--app-weight-semibold); }
    .sc__summary, .sc__cuts, .sc__outside, .sc__legend { margin: 0 0 8px; font-size: var(--app-text-sm); color: var(--bs-secondary-color); }
    .sc__outside, .sc__legend { margin: 8px 0 0; font-size: var(--app-text-xs); }
    .sc__legend { display: flex; flex-wrap: wrap; gap: 4px 16px; }
    .sc__legend > span { display: inline-flex; align-items: center; gap: 4px; }
    .sc__svg { display: block; max-width: 100%; overflow: visible; }
    .sc__grid { stroke: var(--app-chart-grid); stroke-width: 1; }
    .sc__axis { stroke: var(--app-chart-axis); stroke-width: 1; }
    .sc__cut { stroke: var(--app-chart-axis); stroke-width: 1.5; stroke-dasharray: 4 3; }
    .sc__tick, .sc__axis-label { font-size: var(--app-text-xs); fill: var(--bs-secondary-color); font-variant-numeric: tabular-nums; }
    .sc__quadrant { font-size: var(--app-text-xs); font-weight: var(--app-weight-semibold); fill: var(--bs-secondary-color); }
    .sc__mark { fill: var(--app-chart-1); fill-opacity: 0.85; stroke: var(--bs-body-bg); stroke-width: 2; }
    .sc__mark--neg { fill: var(--bs-danger); }
    .sc__hit { fill: transparent; }
    .sc__point { cursor: pointer; outline: none; }
    .sc__point:hover .sc__mark, .sc__point:focus-visible .sc__mark { fill-opacity: 1; stroke: var(--bs-body-color); }
    .sc__point:focus-visible .sc__hit { stroke: var(--app-focus-color, var(--bs-primary)); stroke-width: 2; }
    .sc__neg { color: var(--bs-danger-text-emphasis); }
    .sc__tip {
      position: absolute;
      top: 64px;
      right: 8px;
      display: grid;
      gap: 2px;
      max-width: 300px;
      padding: 8px 10px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      box-shadow: var(--bs-box-shadow);
      font-size: var(--app-text-xs);
      pointer-events: none;
    }
    .sc__tip-hint { color: var(--bs-secondary-color); }
    .sc__table-wrap { overflow-x: auto; }
    .sc__table { width: 100%; border-collapse: collapse; font-size: var(--app-text-sm); }
    .sc__table th, .sc__table td { padding: 6px 8px; border-bottom: 1px solid var(--bs-border-color); text-align: left; }
    .sc__table .num { text-align: right; white-space: nowrap; }
  `,
})
export class MarginScatter {
  readonly title = input('Carteiras: margem × volume recuperado');
  readonly info = input<string | null>(null);
  readonly summary = input.required<string>();
  readonly model = input.required<ScatterModel>();
  /** Parâmetros de URL do filtro de uma carteira (link da tabela equivalente). */
  readonly queryParamsFor = input.required<(p: ScatterPoint) => Params>();
  /** Clique ou Enter num ponto. */
  readonly selected = output<ScatterPoint>();

  protected readonly id = `sc-${nextId++}`;
  protected readonly w = signal(640);
  protected readonly h = H;
  protected readonly pad = PAD;
  protected readonly table = signal(false);
  protected readonly hover = signal<Mark | null>(null);
  protected readonly quadrants = QUADRANTS;
  protected readonly money = (v: number) => formatAmount(v);
  protected readonly percent = percentText;
  protected readonly label = pointLabel;
  protected readonly cuts = computed(() => cutsText(this.model()));

  private readonly xDomain = computed(() => logDomain([...this.model().points.map((p) => p.recovered), this.model().volumeCut]));
  private readonly yDomain = computed(() => rateDomain(this.model().points.map((p) => p.rate), this.model().marginCut));

  private x(value: number): number {
    const { lo, hi } = this.xDomain();
    const t = (Math.log10(Math.max(value, lo)) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo));
    return PAD.left + t * (this.w() - PAD.left - PAD.right);
  }

  private y(rate: number): number {
    const { lo, hi } = this.yDomain();
    const v = Math.max(MIN_RATE_SHOWN, rate);
    return PAD.top + ((hi - v) / (hi - lo)) * (H - PAD.top - PAD.bottom);
  }

  protected readonly cutX = computed(() => this.x(this.model().volumeCut));
  protected readonly cutY = computed(() => this.y(this.model().marginCut));

  protected readonly xTicks = computed(() => {
    const ticks = this.xDomain().ticks;
    // poucas marcas em telas estreitas: no máximo uma a cada 70 px
    const every = Math.max(1, Math.ceil((ticks.length * 70) / Math.max(1, this.w() - PAD.left - PAD.right)));
    return ticks
      .filter((_, i) => i % every === 0 || i === ticks.length - 1)
      .map((value, i, all) => ({
        value,
        x: this.x(value),
        label: AXIS_MONEY.format(value).replace(/\s/g, ' '),
        anchor: i === 0 ? 'start' : i === all.length - 1 ? 'end' : 'middle',
      }));
  });

  protected readonly yTicks = computed(() => {
    const { lo, hi } = this.yDomain();
    const span = hi - lo;
    const step = span <= 0.3 ? 0.05 : span <= 0.6 ? 0.1 : span <= 1.2 ? 0.25 : 0.5;
    const ticks: { value: number; y: number; label: string }[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) {
      const value = Math.round(v * 1000) / 1000;
      ticks.push({ value, y: this.y(value), label: AXIS_PERCENT.format(value).replace(/\s/g, ' ') });
    }
    return ticks;
  });

  protected readonly marks = computed<Mark[]>(() => {
    const points = this.model().points;
    const maxRevenue = Math.max(...points.map((p) => p.revenue), 1);
    // maiores primeiro: os pequenos ficam por cima e continuam clicáveis
    return [...points]
      .sort((a, b) => b.revenue - a.revenue)
      .map((p) => {
        const r = 4 + 6 * Math.sqrt(p.revenue / maxRevenue);
        const cx = this.x(p.recovered);
        const cy = this.y(p.rate);
        const d = r * 1.25;
        return {
          p,
          cx,
          cy,
          r,
          clamped: p.rate < MIN_RATE_SHOWN,
          path: p.negative ? `M${cx},${cy - d}L${cx + d},${cy}L${cx},${cy + d}L${cx - d},${cy}Z` : null,
        };
      });
  });
  protected readonly hasClamped = computed(() => this.marks().some((m) => m.clamped));

  protected readonly tableRows = computed(() =>
    [...this.model().points].sort((a, b) => QUADRANT_ORDER.indexOf(a.quadrant) - QUADRANT_ORDER.indexOf(b.quadrant) || b.rate - a.rate),
  );

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
}
