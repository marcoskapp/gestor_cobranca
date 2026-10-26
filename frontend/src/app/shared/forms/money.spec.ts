import { EMPTY_MONEY, MoneyState, formatMoneyState, fromModel, parseMoneyText, popDigit, pushDigit, toModel } from './money';

function typed(digits: string, start: MoneyState = EMPTY_MONEY, maxDigits?: number): MoneyState {
  return [...digits].reduce((s, d) => pushDigit(s, Number(d), maxDigits), start);
}

describe('regras da máscara monetária', () => {
  it('cada dígito entra pela direita (centavos)', () => {
    expect(formatMoneyState(typed('1'), 2)).toBe('0,01');
    expect(formatMoneyState(typed('12345'), 2)).toBe('123,45');
    expect(formatMoneyState(typed('123456789'), 2)).toBe('1.234.567,89');
    expect(formatMoneyState(typed('100'), 2)).toBe('1,00');
  });

  it('zero digitado vale 0,00 (não vazio); apagar até zero esvazia', () => {
    expect(formatMoneyState(typed('0'), 2)).toBe('0,00');
    expect(toModel(typed('0'), 2, 'number')).toBe(0);
    expect(formatMoneyState(typed('00012'), 2)).toBe('0,12');
    const one = popDigit(typed('12'));
    expect(formatMoneyState(one, 2)).toBe('0,01');
    expect(popDigit(one)).toEqual(EMPTY_MONEY);
    expect(popDigit(EMPTY_MONEY)).toEqual(EMPTY_MONEY);
    expect(toModel(EMPTY_MONEY, 2, 'number')).toBeNull();
  });

  it('respeita o limite de dígitos (o número representa o valor sem perda)', () => {
    const max = typed('123456789012345');
    expect(formatMoneyState(max, 2)).toBe('1.234.567.890.123,45');
    expect(pushDigit(max, 6)).toBe(max);
    expect(formatMoneyState(typed('123456', EMPTY_MONEY, 4), 2)).toBe('12,34');
  });

  it('precisão com valores grandes: centavos inteiros, sem acumular erro de ponto flutuante', () => {
    expect(toModel(typed('123456789012345'), 2, 'number')).toBe(1234567890123.45);
    expect(toModel(typed('999999999999999'), 2, 'number')).toBe(9999999999999.99);
    expect(toModel(typed('30'), 2, 'number')).toBe(0.3);
    expect(toModel(typed('123456789012345'), 2, 'string')).toBe('1234567890123.45');
    // 0,1 + 0,2 vindo do modelo vira 0,30 exato
    expect(formatMoneyState(fromModel(0.1 + 0.2, 2), 2)).toBe('0,30');
    expect(toModel(fromModel(0.1 + 0.2, 2), 2, 'number')).toBe(0.3);
  });

  it('casas decimais configuráveis', () => {
    expect(formatMoneyState(typed('12345'), 4)).toBe('1,2345');
    expect(formatMoneyState(typed('12345'), 0)).toBe('12.345');
    expect(toModel(typed('5'), 4, 'number')).toBe(0.0005);
    expect(toModel(typed('5'), 4, 'string')).toBe('0.0005');
  });

  it('sinal negativo', () => {
    const negative: MoneyState = { units: 15000n, negative: true };
    expect(formatMoneyState(negative, 2)).toBe('-150,00');
    expect(toModel(negative, 2, 'number')).toBe(-150);
    expect(toModel(negative, 2, 'string')).toBe('-150.00');
    expect(formatMoneyState({ units: null, negative: true }, 2)).toBe('-');
    expect(toModel({ units: null, negative: true }, 2, 'number')).toBeNull();
    expect(toModel({ units: 0n, negative: true }, 2, 'number')).toBe(0);
  });

  it('lê o valor do modelo (number, texto do BigDecimal, nulo)', () => {
    expect(formatMoneyState(fromModel(1234.5, 2), 2)).toBe('1.234,50');
    expect(formatMoneyState(fromModel('1234.56', 2), 2)).toBe('1.234,56');
    expect(formatMoneyState(fromModel('-0.5', 2), 2)).toBe('-0,50');
    expect(formatMoneyState(fromModel(1e20, 2), 2)).toBe('100.000.000.000.000.000.000,00');
    expect(formatMoneyState(fromModel(1e-7, 2), 2)).toBe('0,00');
    expect(fromModel(1e21, 2)).toEqual(EMPTY_MONEY);
    expect(fromModel(null, 2)).toEqual(EMPTY_MONEY);
    expect(fromModel('', 2)).toEqual(EMPTY_MONEY);
    expect(fromModel('abc', 2)).toEqual(EMPTY_MONEY);
    expect(fromModel(Number.NaN, 2)).toEqual(EMPTY_MONEY);
    // mais casas que o campo: meio para cima (HALF_UP, como o BigDecimal)
    expect(formatMoneyState(fromModel('10.555', 2), 2)).toBe('10,56');
    expect(formatMoneyState(fromModel(2.5, 0), 0)).toBe('3');
  });

  describe('texto colado', () => {
    const pasted = (text: string, decimals = 2) => {
      const state = parseMoneyText(text, decimals);
      return state ? formatMoneyState(state, decimals) : null;
    };

    it('aceita os formatos usuais', () => {
      expect(pasted('1.234,56')).toBe('1.234,56');
      expect(pasted('1234.56')).toBe('1.234,56');
      expect(pasted('R$ 1.234,56')).toBe('1.234,56');
      expect(pasted('R$\u00a01.234,56')).toBe('1.234,56');
      expect(pasted('1234')).toBe('1.234,00');
      expect(pasted('1234,5')).toBe('1.234,50');
      expect(pasted('1,234.56')).toBe('1.234,56');
      expect(pasted('1.234.567')).toBe('1.234.567,00');
      expect(pasted('1,234,567')).toBe('1.234.567,00');
      expect(pasted('0,99')).toBe('0,99');
      expect(pasted(' 350 ')).toBe('350,00');
    });

    it('ponto único seguido de 3 dígitos é milhar (pt-BR); caso contrário, decimal', () => {
      expect(pasted('1.234')).toBe('1.234,00');
      expect(pasted('10.5')).toBe('10,50');
      expect(pasted('1.2345', 4)).toBe('1,2345');
      expect(pasted('1.234', 4)).toBe('1,2340');
    });

    it('sinal antes, depois ou entre parênteses', () => {
      expect(pasted('-150,00')).toBe('-150,00');
      expect(pasted('−150,00')).toBe('-150,00');
      expect(pasted('R$ -1.234,56')).toBe('-1.234,56');
      expect(pasted('150,00-')).toBe('-150,00');
      expect(pasted('(150,00)')).toBe('-150,00');
      expect(pasted('+150')).toBe('150,00');
    });

    it('arredonda casas a mais (HALF_UP) e recusa o que não é número', () => {
      expect(pasted('10,555')).toBe('10,56');
      expect(pasted('10,554')).toBe('10,55');
      expect(pasted('abc')).toBeNull();
      expect(pasted('')).toBeNull();
      expect(pasted('R$')).toBeNull();
      expect(pasted('12.34.5')).toBeNull();
      expect(pasted('1,2,3')).toBeNull();
      expect(pasted('1.234,5,6')).toBeNull();
      expect(pasted('12a')).toBeNull();
    });
  });
});
