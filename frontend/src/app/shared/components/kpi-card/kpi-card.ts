import { booleanAttribute, Component, input } from '@angular/core';
import { InfoHint } from '../info-hint/info-hint';

/** Tom do valor: só para o que exige atenção (amarelo) ou falha/atraso (vermelho). */
export type KpiTone = 'warning' | 'danger';

/**
 * Cartão de indicador único da aplicação (docs/tecnico/desenho/ux.md §12): rótulo em uma linha
 * (sentence case, reticências com o texto completo no `title`), valor projetado (texto ou
 * `app-amount`) e detalhe opcional — o denominador sempre visível ("1.100 de 4.000"), para que
 * nenhuma taxa apareça sem a base.
 *
 * - `info`: id do indicador no catálogo de informação contextual (ux.md §8.6). Obrigatório para
 *   indicadores calculados — o `npm run hints:check` recusa o cartão sem ele — salvo `record`.
 * - `record`: o valor é um campo do próprio registro (ex.: dívida do caso jurídico), não um
 *   indicador calculado; dispensa a informação contextual.
 * - `tone`: destaca o valor em amarelo (atenção) ou vermelho (falha/atraso); o rótulo e o detalhe
 *   continuam dizendo o que é (a cor nunca comunica sozinha).
 * - `emphasis`: o total de um resumo (ex.: "Líquido a repassar"), com fundo de destaque.
 * - `variant="text"`: valor textual (ex.: forma de pagamento), em tamanho de corpo.
 *
 * Dentro de um cartão (`.app-card`) assume a forma embutida (fundo terciário, sem borda) para não
 * aninhar cartão com borda dentro de cartão. Estilos globais `.app-kpi*` (styles.scss), para que o
 * conteúdo extra projetado (`appKpiExtra`, ex.: medidor) use `.app-kpi__detail`; a grade é a
 * classe global `.app-kpi-grid`.
 */
@Component({
  selector: 'app-kpi-card',
  imports: [InfoHint],
  host: {
    class: 'app-kpi',
    role: 'group',
    '[attr.aria-label]': 'label()',
    '[class.app-kpi--warning]': "tone() === 'warning'",
    '[class.app-kpi--danger]': "tone() === 'danger'",
    '[class.app-kpi--emphasis]': 'emphasis()',
    '[class.app-kpi--text]': "variant() === 'text'",
  },
  template: `
    <span class="app-kpi__head">
      <span class="app-kpi__label" [attr.title]="label()">{{ label() }}</span>
      @if (info(); as id) {
        <app-info-hint [indicator]="id" [label]="label()" />
      }
    </span>
    <span class="app-kpi__value app-numeric"><ng-content /></span>
    @if (detail()) {
      <span class="app-kpi__detail">{{ detail() }}</span>
    }
    <ng-content select="[appKpiExtra]" />
  `,
})
export class KpiCard {
  readonly label = input.required<string>();
  /** Base do indicador (denominador) ou explicação curta. */
  readonly detail = input<string | null>(null);
  /** Id do indicador no catálogo de informação contextual. */
  readonly info = input<string | null>(null);
  /** Valor do próprio registro (não é indicador calculado): dispensa `info`. */
  readonly record = input(false, { transform: booleanAttribute });
  readonly tone = input<KpiTone | null>(null);
  readonly emphasis = input(false, { transform: booleanAttribute });
  readonly variant = input<'number' | 'text'>('number');
}
