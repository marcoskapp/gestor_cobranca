import { Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

export interface FunnelStage {
  label: string;
  value: number;
  display: string;
  /** Conversão a partir da etapa anterior, já formatada ("75,0% · 3 de 4"); vazia na primeira. */
  conversion?: string;
}

let nextId = 0;

/**
 * Funil de volumes por etapa (uma cor, barras proporcionais à maior etapa) com a conversão entre
 * etapas escrita ao lado — o funil é de fluxo: cada etapa conta os eventos do período, não as
 * mesmas pessoas. Tabela equivalente sob demanda.
 */
@Component({
  selector: 'app-funnel-chart',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <figure class="fn" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s'">
      <div class="fn__head">
        <figcaption [id]="id + '-t'" class="fn__title">{{ title() }}</figcaption>
        <button mat-button type="button" (click)="table.set(!table())" [attr.aria-pressed]="table()">
          <mat-icon aria-hidden="true">{{ table() ? 'filter_alt' : 'table_rows' }}</mat-icon>
          {{ table() ? 'Ver funil' : 'Ver tabela' }}
        </button>
      </div>
      <p [id]="id + '-s'" class="fn__summary">{{ summary() }}</p>
      @if (table()) {
        <table class="fn__table">
          <thead><tr><th scope="col">Etapa</th><th scope="col" class="num">Quantidade</th><th scope="col">Conversão da etapa anterior</th></tr></thead>
          <tbody>
            @for (s of stages(); track s.label) {
              <tr><th scope="row">{{ s.label }}</th><td class="num app-numeric">{{ s.display }}</td><td>{{ s.conversion ?? '—' }}</td></tr>
            }
          </tbody>
        </table>
      } @else {
        <ol class="fn__stages">
          @for (s of stages(); track s.label) {
            @if (s.conversion) {
              <li class="fn__conv" aria-hidden="true"><span aria-hidden="true">↓</span> {{ s.conversion }}</li>
            }
            <li class="fn__stage" role="img" [attr.aria-label]="s.label + ': ' + s.display + (s.conversion ? ', conversão ' + s.conversion : '')">
              <span class="fn__label">{{ s.label }}</span>
              <span class="fn__track"><span class="fn__bar" [style.width.%]="width(s.value)"></span></span>
              <span class="fn__value app-numeric">{{ s.display }}</span>
            </li>
          }
        </ol>
      }
    </figure>
  `,
  styles: `
    .fn { margin: 0; }
    .fn__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .fn__head > button { flex: none; white-space: nowrap; }
    .fn__title { font-size: 15px; font-weight: 600; }
    .fn__summary { margin: 0 0 8px; font-size: var(--app-text-sm); color: var(--mat-sys-on-surface-variant); }
    .fn__stages { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
    .fn__stage { display: grid; grid-template-columns: minmax(110px, 28%) 1fr auto; align-items: center; gap: 12px; }
    .fn__label { font-size: var(--app-text-sm); }
    .fn__track { height: 20px; }
    .fn__bar { display: block; height: 20px; border-radius: 0 4px 4px 0; background: var(--app-chart-1); }
    .fn__value { min-width: 56px; font-size: var(--app-text-sm); font-weight: 600; text-align: right; }
    .fn__conv { padding-left: calc(28% + 12px); font-size: var(--app-text-xs); color: var(--mat-sys-on-surface-variant); }
    .fn__table { width: 100%; border-collapse: collapse; font-size: var(--app-text-sm); }
    .fn__table th, .fn__table td { padding: 6px 8px; border-bottom: 1px solid var(--app-border); text-align: left; }
    .fn__table .num { text-align: right; }
    @media (max-width: 599.98px) {
      .fn__stage { grid-template-columns: 1fr auto; }
      .fn__track { grid-column: 1 / -1; grid-row: 2; }
      .fn__conv { padding-left: 0; }
    }
  `,
})
export class FunnelChart {
  readonly title = input.required<string>();
  readonly summary = input.required<string>();
  readonly stages = input.required<FunnelStage[]>();

  protected readonly id = `fn-${nextId++}`;
  protected readonly table = signal(false);
  private readonly max = computed(() => Math.max(0, ...this.stages().map((s) => s.value)));

  protected width(value: number): number {
    const max = this.max();
    return max > 0 && value > 0 ? Math.max(0.5, (value / max) * 100) : 0;
  }
}
