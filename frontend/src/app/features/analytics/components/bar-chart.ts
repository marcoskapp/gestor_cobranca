import { Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ChartRow, ChartSeries } from './chart.models';

let nextId = 0;

/**
 * Barras horizontais (1 ou 2 séries agrupadas) para comparar magnitudes entre categorias.
 * Acessível: título e resumo no `figure`, legenda com 2 séries, valor escrito na ponta de cada
 * barra (a cor nunca é o único canal), dica no foco/ponteiro e tabela equivalente sob demanda.
 */
@Component({
  selector: 'app-bar-chart',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <figure class="bc" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s'">
      <div class="bc__head">
        <figcaption [id]="id + '-t'" class="bc__title">{{ title() }}</figcaption>
        <button mat-button type="button" class="bc__toggle" (click)="table.set(!table())" [attr.aria-pressed]="table()">
          <mat-icon aria-hidden="true">{{ table() ? 'bar_chart' : 'table_rows' }}</mat-icon>
          {{ table() ? 'Ver gráfico' : 'Ver tabela' }}
        </button>
      </div>
      <p [id]="id + '-s'" class="bc__summary">{{ summary() }}</p>
      @if (series().length > 1) {
        <ul class="bc__legend" aria-label="Legenda">
          @for (s of series(); track s.name) {
            <li><span class="bc__swatch" [style.background]="'var(' + s.color + ')'"></span>{{ s.name }}</li>
          }
        </ul>
      }
      @if (rows().length === 0) {
        <p class="bc__empty" role="status">Sem dados no período e filtros escolhidos.</p>
      } @else if (table()) {
        <div class="bc__table-wrap">
          <table class="bc__table">
            <thead>
              <tr>
                <th scope="col">{{ categoryHeader() }}</th>
                @for (s of series(); track s.name) {
                  <th scope="col" class="num">{{ s.name }}</th>
                }
                @if (hasNotes()) {
                  <th scope="col">{{ noteHeader() }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of rows(); track r.key) {
                <tr>
                  <th scope="row">{{ r.label }}</th>
                  @for (d of r.display; track $index) {
                    <td class="num app-numeric">{{ d }}</td>
                  }
                  @if (hasNotes()) {
                    <td>{{ r.note ?? '' }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      } @else {
        <ol class="bc__rows" (pointerleave)="hover.set(null)">
          @for (r of rows(); track r.key) {
            <li class="bc__row">
              <span class="bc__label" [title]="r.label">{{ r.label }}</span>
              <span class="bc__bars">
                @for (v of r.values; track $index; let i = $index) {
                  <span
                    class="bc__bar-line"
                    tabindex="0"
                    role="img"
                    [attr.aria-label]="r.label + ', ' + series()[i].name + ': ' + r.display[i] + (r.note ? ' (' + r.note + ')' : '')"
                    (pointerenter)="hover.set({ row: r, index: i })"
                    (focus)="hover.set({ row: r, index: i })"
                    (blur)="hover.set(null)"
                  >
                    <span class="bc__bar" [style.--bc-w]="width(v) / 100" [style.background]="'var(' + series()[i].color + ')'"></span>
                    <span class="bc__value app-numeric">{{ r.display[i] }}</span>
                  </span>
                }
              </span>
            </li>
          }
        </ol>
        @if (hover(); as h) {
          <div class="bc__tip" role="status">
            <strong class="app-numeric">{{ h.row.display[h.index] }}</strong>
            <span>{{ series()[h.index].name }} · {{ h.row.label }}</span>
            @if (h.row.note) {
              <span>{{ h.row.note }}</span>
            }
          </div>
        }
      }
    </figure>
  `,
  styleUrl: './bar-chart.scss',
})
export class BarChart {
  readonly title = input.required<string>();
  /** Texto alternativo: o que o gráfico mostra e o destaque principal. */
  readonly summary = input.required<string>();
  readonly series = input.required<ChartSeries[]>();
  readonly rows = input.required<ChartRow[]>();
  readonly categoryHeader = input('Categoria');
  readonly noteHeader = input('Observação');

  protected readonly id = `bc-${nextId++}`;
  protected readonly table = signal(false);
  protected readonly hover = signal<{ row: ChartRow; index: number } | null>(null);
  protected readonly hasNotes = computed(() => this.rows().some((r) => !!r.note));
  private readonly max = computed(() => Math.max(0, ...this.rows().flatMap((r) => r.values)));

  protected width(value: number): number {
    const max = this.max();
    return max > 0 && value > 0 ? Math.max(0.5, (value / max) * 100) : 0;
  }
}
