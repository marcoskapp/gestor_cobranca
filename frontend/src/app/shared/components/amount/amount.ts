import { Component, computed, input } from '@angular/core';

const FORMATS = {
  BRL: new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }),
  compact: new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', notation: 'compact', maximumFractionDigits: 1 }),
};

/** Converte o valor monetário do backend (`BigDecimal` como número ou texto) em número. */
export function toAmount(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const amount = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(amount) ? amount : null;
}

/** Formata em reais; espaços não separáveis entre "R$" e o número (não quebra a linha). */
export function formatAmount(value: number | string | null | undefined, compact = false): string {
  const amount = toAmount(value);
  return amount === null ? '—' : (compact ? FORMATS.compact : FORMATS.BRL).format(amount).replace(/\s/g, ' ');
}

/**
 * Valor monetário com algarismos tabulares (alinham em colunas). `signed` destaca negativos e
 * positivos com cor **e** sinal explícito (nunca só a cor), útil em saldos e estornos.
 * Em `compact` ("R$ 1,2 mi") o valor exato fica disponível no `title` e para leitores de tela.
 */
@Component({
  selector: 'app-amount',
  host: {
    class: 'app-numeric',
    '[class.app-amount--negative]': 'signed() && (amount() ?? 0) < 0',
    '[class.app-amount--positive]': 'signed() && (amount() ?? 0) > 0',
    '[attr.title]': 'compact() ? full() : null',
  },
  template: `{{ prefix() }}{{ text() }}@if (compact()) {<span class="cdk-visually-hidden"> ({{ full() }})</span>}`,
})
export class Amount {
  readonly value = input.required<number | string | null | undefined>();
  readonly signed = input(false);
  readonly compact = input(false);

  protected readonly amount = computed(() => toAmount(this.value()));
  protected readonly full = computed(() => formatAmount(this.value()));
  protected readonly text = computed(() => formatAmount(this.value(), this.compact()));
  protected readonly prefix = computed(() => (this.signed() && (this.amount() ?? 0) > 0 ? '+' : ''));
}
