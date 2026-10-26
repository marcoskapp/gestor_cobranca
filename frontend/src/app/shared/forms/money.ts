/**
 * Regras da máscara monetária (`appMoney`), sem dependência do Angular: o valor é guardado em
 * unidades mínimas inteiras (`bigint`, centavos com 2 casas), para que digitar, apagar e colar nunca
 * acumulem erro de ponto flutuante. A conversão para `number` acontece uma única vez, na saída
 * (`toModel`), dividindo um inteiro exato por uma potência de 10 — o resultado é o `double` mais
 * próximo do decimal, o mesmo que o backend (`BigDecimal`) recebe ao ler o JSON.
 */

/** Dígitos significativos aceitos: até 15 o `number` representa o valor sem perda (2^53 ≈ 9 × 10^15). */
export const MONEY_MAX_DIGITS = 15;

/** Estado do campo: `units` nulo = vazio; `negative` também guarda o "-" digitado antes dos dígitos. */
export interface MoneyState {
  readonly units: bigint | null;
  readonly negative: boolean;
}

export const EMPTY_MONEY: MoneyState = { units: null, negative: false };

const TEN = 10n;

function pow10(decimals: number): bigint {
  return TEN ** BigInt(decimals);
}

function digitCount(units: bigint): number {
  return (units < 0n ? -units : units).toString().length;
}

/** Acrescenta um dígito pela direita ("1" → 0,01; "12" → 0,12). Ignora se passar do limite de dígitos. */
export function pushDigit(state: MoneyState, digit: number, maxDigits = MONEY_MAX_DIGITS): MoneyState {
  const next = (state.units ?? 0n) * TEN + BigInt(digit);
  if (next !== 0n && digitCount(next) > maxDigits) {
    return state;
  }
  return { units: next, negative: state.negative };
}

/** Remove o último dígito (backspace). Chegar a zero esvazia o campo (vazio = `null`, não 0). */
export function popDigit(state: MoneyState): MoneyState {
  if (state.units === null || state.units === 0n) {
    return EMPTY_MONEY;
  }
  const next = state.units / TEN;
  return next === 0n ? EMPTY_MONEY : { units: next, negative: state.negative };
}

/** Texto exibido no campo: "1.234.567,89", "-12,30" ou "-" (sinal digitado antes dos dígitos). Sem "R$". */
export function formatMoneyState(state: MoneyState, decimals: number): string {
  if (state.units === null) {
    return state.negative ? '-' : '';
  }
  const raw = state.units.toString().padStart(decimals + 1, '0');
  const integer = raw.slice(0, raw.length - decimals).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const fraction = decimals ? ',' + raw.slice(raw.length - decimals) : '';
  return (state.negative ? '-' : '') + integer + fraction;
}

/** Valor do controle: `number` (padrão) ou texto decimal canônico ("-1234.56", o formato do `BigDecimal`). */
export function toModel(state: MoneyState, decimals: number, type: 'number' | 'string'): number | string | null {
  if (state.units === null) {
    return null;
  }
  const signed = state.negative && state.units !== 0n ? -state.units : state.units;
  if (type === 'number') {
    return Number(signed) / Number(pow10(decimals));
  }
  const raw = (signed < 0n ? -signed : signed).toString().padStart(decimals + 1, '0');
  const text = decimals ? `${raw.slice(0, raw.length - decimals)}.${raw.slice(raw.length - decimals)}` : raw;
  return signed < 0n ? `-${text}` : text;
}

/**
 * Valor vindo do modelo (`number`, texto decimal canônico do backend ou `null`) em unidades mínimas,
 * arredondando meio para cima (`HALF_UP`) quando há mais casas que o campo. Inválido = vazio.
 */
export function fromModel(value: unknown, decimals: number): MoneyState {
  if (value === null || value === undefined || value === '') {
    return EMPTY_MONEY;
  }
  let text: string;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      return EMPTY_MONEY;
    }
    // String(n) é a menor representação exata do double; em notação científica (1e21, 1e-7), toFixed
    text = /e/i.test(String(value)) ? value.toFixed(Math.min(decimals + 1, 100)) : String(value);
  } else if (typeof value === 'string') {
    text = value.trim();
  } else {
    return EMPTY_MONEY;
  }
  const match = /^([-+]?)(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match || (!match[2] && !match[3])) {
    return EMPTY_MONEY;
  }
  return fromParts(match[1] === '-', match[2] ?? '', match[3] ?? '', decimals);
}

function fromParts(negative: boolean, integer: string, fraction: string, decimals: number): MoneyState {
  const kept = fraction.slice(0, decimals).padEnd(decimals, '0');
  let units = BigInt((integer.replace(/^0+/, '') || '0') + kept);
  if ((fraction[decimals] ?? '0') >= '5') {
    units += 1n;
  }
  return { units, negative: negative && units !== 0n };
}

/**
 * Texto colado ou arrastado para o campo: "1.234,56", "1234.56", "R$ 1.234,56", "1234", "-150,00",
 * "(150,00)". Regras, em ordem:
 * - com ponto **e** vírgula, o separador que aparece por último é o decimal ("1,234.56" também vale);
 * - só vírgula: uma vírgula é decimal (pt-BR); várias são milhar ("1,234,567");
 * - só ponto: vários são milhar ("1.234.567"); um só é milhar quando seguido de exatamente 3 dígitos
 *   e o campo tem menos de 3 casas ("1.234" = mil duzentos e trinta e quatro, como se lê no Brasil);
 *   caso contrário é decimal ("1234.56", "10.5");
 * - sem separador, o número é inteiro ("1234" = 1.234,00, e não 12,34 como na digitação).
 * Devolve `null` quando não há número reconhecível (o campo mantém o valor anterior).
 */
export function parseMoneyText(text: string, decimals: number): MoneyState | null {
  let raw = text.replace(/R\$|BRL|[\s\u00a0\u202f]/gi, '');
  let negative = false;
  const parenthesized = /^\((.*)\)$/.exec(raw);
  if (parenthesized) {
    negative = true;
    raw = parenthesized[1];
  }
  // sinal antes ("-150,00", "−150,00", "+150,00") ou depois ("150,00-", comum em extratos)
  if (/^[-−+]/.test(raw)) {
    negative = negative || raw[0] !== '+';
    raw = raw.slice(1);
  } else if (/[-−]$/.test(raw)) {
    negative = true;
    raw = raw.slice(0, -1);
  }
  if (!/^[\d.,]+$/.test(raw) || !/\d/.test(raw)) {
    return null;
  }
  const lastDot = raw.lastIndexOf('.');
  const lastComma = raw.lastIndexOf(',');
  let decimalAt = -1;
  if (lastDot >= 0 && lastComma >= 0) {
    decimalAt = Math.max(lastDot, lastComma);
  } else if (lastComma >= 0) {
    decimalAt = raw.indexOf(',') === lastComma ? lastComma : -1;
  } else if (lastDot >= 0) {
    const single = raw.indexOf('.') === lastDot;
    const thousands = single && raw.length - lastDot - 1 === 3 && decimals < 3;
    decimalAt = single && !thousands ? lastDot : -1;
  }
  const integerPart = decimalAt >= 0 ? raw.slice(0, decimalAt) : raw;
  const fractionPart = decimalAt >= 0 ? raw.slice(decimalAt + 1) : '';
  if (/[.,]/.test(fractionPart)) {
    return null;
  }
  // separadores de milhar só entre grupos de 3 dígitos ("1.234.567"); "12.34.5" é recusado
  if (/[.,]/.test(integerPart) && !/^\d{1,3}([.,]\d{3})+$/.test(integerPart)) {
    return null;
  }
  return fromParts(negative, integerPart.replace(/[.,]/g, ''), fractionPart, decimals);
}

/** O estado cabe no limite de dígitos do campo? */
export function fitsDigits(state: MoneyState, maxDigits = MONEY_MAX_DIGITS): boolean {
  return state.units === null || digitCount(state.units) <= maxDigits;
}

/** Valor do limite (`min`/`max`, em reais) em unidades mínimas, para comparar sem ponto flutuante. */
export function limitUnits(limit: number | string | null | undefined, decimals: number): bigint | null {
  const state = fromModel(limit, decimals);
  if (state.units === null) {
    return null;
  }
  return state.negative ? -state.units : state.units;
}

/** Unidades com sinal (para validação). */
export function signedUnits(state: MoneyState): bigint | null {
  return state.units === null ? null : state.negative ? -state.units : state.units;
}
