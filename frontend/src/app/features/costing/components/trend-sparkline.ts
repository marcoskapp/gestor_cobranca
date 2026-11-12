import { Component, ElementRef, computed, inject, input, signal } from '@angular/core';
import { TrendMonth, TrendPoint } from '../data/costing.models';
import { sparkline, trendPointText, trendText } from '../data/margin-trend.rules';

const W = 72;
const H = 22;

/**
 * Minigráfico da margem líquida % dos últimos 6 meses (coluna "Tendência" da tabela por credor). SVG próprio, sem
 * biblioteca: linha interrompida no mês sem receita ou sem apuração (nunca zero inventado), ponto em cada mês com
 * base, ponto final maior, linha do zero quando a série fica negativa e pontos negativos em vermelho. A frase da
 * tendência é o texto acessível (`role="img"`); a dica (ponteiro e foco) lista mês a mês. Posição fixa: a dica não é
 * cortada pela rolagem da tabela.
 */
@Component({
  selector: 'app-trend-sparkline',
  template: `
    <span class="ts" tabindex="0" role="img" [attr.aria-label]="text()"
          (pointerenter)="show()" (pointerleave)="tip.set(null)" (focus)="show()" (blur)="tip.set(null)">
      <svg [attr.width]="w" [attr.height]="h" [attr.viewBox]="'0 0 ' + w + ' ' + h" aria-hidden="true" focusable="false">
        @if (geometry().zeroY !== null) {
          <line class="ts__zero" x1="0" [attr.x2]="w" [attr.y1]="geometry().zeroY" [attr.y2]="geometry().zeroY" />
        }
        @for (d of geometry().paths; track $index) {
          <path class="ts__line" [attr.d]="d" />
        }
        @for (dot of geometry().dots; track $index) {
          <circle [class]="'ts__dot' + (dot.negative ? ' ts__dot--neg' : '')" [attr.cx]="dot.x" [attr.cy]="dot.y" [attr.r]="dot.last ? 2.75 : 1.75" />
        }
      </svg>
      @if (!geometry().dots.length) {
        <span class="ts__none" aria-hidden="true">sem base</span>
      }
    </span>
    @if (tip(); as at) {
      <span class="ts__tip" role="tooltip" [style.top.px]="at.top" [style.left.px]="at.left" [class.ts__tip--below]="at.below">
        <strong>{{ text() }}</strong>
        @for (line of lines(); track $index) {
          <span>{{ line }}</span>
        }
      </span>
    }
  `,
  styles: `
    :host { display: inline-block; }
    .ts { display: inline-flex; align-items: center; gap: 4px; border-radius: 4px; outline-offset: 2px; vertical-align: middle; }
    .ts__line { fill: none; stroke: var(--app-chart-1); stroke-width: 1.5; stroke-linejoin: round; stroke-linecap: round; }
    .ts__dot { fill: var(--app-chart-1); }
    .ts__dot--neg { fill: var(--bs-danger); }
    .ts__zero { stroke: var(--app-chart-axis); stroke-width: 1; stroke-dasharray: 2 2; }
    .ts__none { font-size: var(--app-text-xs); color: var(--bs-secondary-color); }
    .ts__tip {
      position: fixed;
      z-index: 1080;
      display: grid;
      gap: 2px;
      max-width: 280px;
      padding: 8px 10px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      box-shadow: var(--bs-box-shadow);
      color: var(--bs-body-color);
      font-size: var(--app-text-xs);
      text-align: left;
      white-space: normal;
      pointer-events: none;
      transform: translate(-100%, calc(-100% - 6px));
    }
    .ts__tip--below { transform: translate(-100%, 6px); }
  `,
})
export class TrendSparkline {
  readonly points = input.required<TrendPoint[]>();
  readonly months = input.required<TrendMonth[]>();

  protected readonly w = W;
  protected readonly h = H;
  protected readonly tip = signal<{ top: number; left: number; below: boolean } | null>(null);
  protected readonly geometry = computed(() => sparkline(this.points(), W, H));
  protected readonly text = computed(() => trendText(this.points(), this.months()));
  protected readonly lines = computed(() => this.months().map((m, i) => trendPointText(this.points()[i] ?? { month: m.month, recovered: null, revenue: null, netMargin: null, netMarginRate: null }, m)));

  private readonly host = inject(ElementRef<HTMLElement>);

  protected show(): void {
    const rect = (this.host.nativeElement as HTMLElement).getBoundingClientRect();
    // perto do topo da janela, a dica abre para baixo (nunca sai da tela)
    const below = rect.top < 160;
    this.tip.set({ top: below ? rect.bottom : rect.top, left: rect.right, below });
  }
}
