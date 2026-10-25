import { Component, computed, input } from '@angular/core';

/**
 * Esqueleto de carregamento com os placeholders do Bootstrap (`placeholder-glow`): reserva o
 * espaço do conteúdo para evitar saltos de layout. A animação some com `prefers-reduced-motion`.
 * O contêiner que carrega deve ter `aria-busy="true"`; o esqueleto é ignorado por leitores de tela.
 */
@Component({
  selector: 'app-skeleton',
  host: { 'aria-hidden': 'true', class: 'placeholder-glow' },
  template: `
    @switch (variant()) {
      @case ('table') {
        @for (row of rowList(); track row) {
          <div class="sk-row">
            @for (col of colList(); track col) {
              <span class="placeholder rounded-pill bg-secondary" [style.width.%]="widthOf(row, col)"></span>
            }
          </div>
        }
      }
      @case ('card') {
        <div class="card">
          <div class="card-body d-grid gap-2">
            <span class="placeholder placeholder-lg rounded-pill bg-secondary col-5 mb-2"></span>
            @for (row of rowList(); track row) {
              <span class="placeholder rounded-pill bg-secondary" [style.width.%]="widthOf(row, 0)"></span>
            }
          </div>
        </div>
      }
      @default {
        <div class="d-grid gap-2">
          @for (row of rowList(); track row) {
            <span class="placeholder rounded-pill bg-secondary" [style.width.%]="widthOf(row, 0)"></span>
          }
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .sk-row {
      display: flex;
      gap: 24px;
      align-items: center;
      height: 44px;
      padding: 0 16px;
      border-bottom: var(--bs-border-width) solid var(--bs-border-color);
    }
    .sk-row .placeholder {
      flex: 1 1 auto;
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
