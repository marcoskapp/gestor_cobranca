import { Component, computed, input } from '@angular/core';

/**
 * Esqueleto de carregamento: reserva o espaço do conteúdo para evitar saltos de layout.
 * Pulsação suave desativada com `prefers-reduced-motion`. O contêiner que carrega deve ter
 * `aria-busy="true"`; o esqueleto é ignorado por leitores de tela.
 */
@Component({
  selector: 'app-skeleton',
  host: { 'aria-hidden': 'true' },
  template: `
    @switch (variant()) {
      @case ('table') {
        @for (row of rowList(); track row) {
          <div class="sk-row">
            @for (col of colList(); track col) {
              <span class="sk" [style.width.%]="widthOf(row, col)"></span>
            }
          </div>
        }
      }
      @case ('card') {
        <div class="sk-card">
          <span class="sk sk--title"></span>
          @for (row of rowList(); track row) {
            <span class="sk" [style.width.%]="widthOf(row, 0)"></span>
          }
        </div>
      }
      @default {
        @for (row of rowList(); track row) {
          <span class="sk sk--line" [style.width.%]="widthOf(row, 0)"></span>
        }
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .sk {
      display: block;
      height: 12px;
      border-radius: var(--app-radius-sm);
      background: var(--mat-sys-surface-container-highest);
      animation: sk-pulse 1.4s ease-in-out infinite;
    }
    .sk--line + .sk--line {
      margin-top: 10px;
    }
    .sk--title {
      width: 40%;
      height: 16px;
      margin-bottom: 14px;
    }
    .sk-row {
      display: flex;
      gap: 24px;
      align-items: center;
      height: 44px;
      padding: 0 16px;
      border-bottom: 1px solid var(--app-border);
    }
    .sk-row .sk {
      flex: 1 1 auto;
    }
    .sk-card {
      display: grid;
      gap: 10px;
      padding: 16px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
    }
    @keyframes sk-pulse {
      50% {
        opacity: 0.45;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .sk {
        animation: none;
      }
    }
  `,
})
export class Skeleton {
  readonly variant = input<'lines' | 'table' | 'card'>('lines');
  readonly rows = input(3);
  readonly columns = input(4);
  protected readonly rowList = computed(() => Array.from({ length: this.rows() }, (_, i) => i));
  protected readonly colList = computed(() => Array.from({ length: this.columns() }, (_, i) => i));

  /** Larguras variadas e determinísticas (aparência natural sem aleatoriedade). */
  protected widthOf(row: number, col: number): number {
    return 55 + ((row * 37 + col * 23) % 40);
  }
}
