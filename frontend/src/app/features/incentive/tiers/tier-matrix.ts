import { Component, input, output } from '@angular/core';
import { QuartileKey, TierLevel, TierMatrix } from './tier.models';
import { QUARTILES_DESC, TIER_LEVELS, cloneMatrix } from './tier.rules';

/**
 * Matriz qualidade × eficiência → nível (distribuicao-por-desempenho.md §3.2). Tabela com cabeçalhos de linha e de
 * coluna (leitor de tela anuncia "Qualidade Q4, eficiência Q3"); editável com um seletor por célula ou só leitura,
 * com a célula da própria pessoa destacada.
 */
@Component({
  selector: 'app-tier-matrix',
  template: `
    <div class="table-responsive">
      <table class="table table-sm table-bordered align-middle text-center mb-0 tier-matrix">
        <caption class="visually-hidden">{{ caption() }}</caption>
        <thead>
          <tr>
            <td class="text-start"><span class="visually-hidden">Qualidade nas linhas, eficiência nas colunas</span></td>
            @for (e of quartiles; track e) {
              <th scope="col" [id]="idPrefix() + '-e-' + e">Eficiência {{ e }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (q of quartiles; track q) {
            <tr>
              <th scope="row" class="text-start" [id]="idPrefix() + '-q-' + q">Qualidade {{ q }}</th>
              @for (e of quartiles; track e) {
                <td [class.table-active]="highlighted(q, e)" [attr.aria-current]="highlighted(q, e) ? 'true' : null">
                  @if (editable()) {
                    <select
                      class="form-select form-select-sm"
                      [value]="matrix()[q][e]"
                      [attr.aria-labelledby]="idPrefix() + '-q-' + q + ' ' + idPrefix() + '-e-' + e"
                      (change)="set(q, e, $any($event.target).value)"
                    >
                      @for (t of levels; track t) {
                        <option [value]="t" [selected]="matrix()[q][e] === t">{{ t }}</option>
                      }
                    </select>
                  } @else {
                    <strong>{{ matrix()[q][e] }}</strong>
                    @if (highlighted(q, e)) {
                      <span class="visually-hidden"> (o seu)</span>
                    }
                  }
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .tier-matrix {
      max-width: 36rem;
    }
    .tier-matrix select {
      min-width: 4rem;
    }
  `,
})
export class TierMatrixTable {
  readonly matrix = input.required<TierMatrix>();
  readonly editable = input(false);
  readonly caption = input('Matriz de níveis: qualidade × eficiência');
  /** Prefixo único dos ids (mais de uma matriz na página). */
  readonly idPrefix = input('tier-matrix');
  /** Célula destacada (quartis 1–4 da própria pessoa). */
  readonly quality = input<number | null>(null);
  readonly efficiency = input<number | null>(null);
  readonly matrixChange = output<TierMatrix>();

  protected readonly quartiles = QUARTILES_DESC;
  protected readonly levels = TIER_LEVELS;

  protected highlighted(q: QuartileKey, e: QuartileKey): boolean {
    return this.quality() !== null && this.efficiency() !== null && q === `Q${this.quality()}` && e === `Q${this.efficiency()}`;
  }

  protected set(q: QuartileKey, e: QuartileKey, value: string): void {
    if (!TIER_LEVELS.includes(value as TierLevel)) {
      return;
    }
    const next = cloneMatrix(this.matrix());
    next[q][e] = value as TierLevel;
    this.matrixChange.emit(next);
  }
}
