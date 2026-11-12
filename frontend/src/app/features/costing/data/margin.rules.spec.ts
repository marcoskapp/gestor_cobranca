import { convertToParamMap } from '@angular/router';
import { marginLine as line } from '../../../../testing/costing-fixtures';
import { assignableBandNames, isCurrent, monthOptions, normalizeMonth, sortBands, validityText } from './cost-settings.rules';
import {
  DEFAULT_MARGIN_SORT,
  formatMarginSort,
  lastClosedMonth,
  marginFilterParams,
  marginText,
  monthLabel,
  parseMarginFilter,
  parseMarginSort,
  previousDay,
  rateText,
  refreshPeriod,
  sortMarginRows,
  warningView,
  warningViews,
  waterfallSteps,
} from './margin.rules';

const nbsp = (text: string) => text.replace(/\u00a0/g, ' ');


const ID_A = '00000000-0000-0000-0000-00000000000a';
const ID_B = '00000000-0000-0000-0000-00000000000b';

describe('margem por carteira — regras puras', () => {
  it('reapuração vai até ontem e tem no máximo 93 dias', () => {
    expect(previousDay('2026-03-01')).toBe('2026-02-28');
    expect(previousDay('2026-01-01')).toBe('2025-12-31');
    // mês fechado: o período do filtro
    expect(refreshPeriod({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-12')).toEqual({ ok: true, from: '2026-10-01', to: '2026-10-31' });
    // mês corrente: o fim é cortado em ontem
    expect(refreshPeriod({ from: '2026-11-01', to: '2026-11-30' }, '2026-11-12')).toEqual({ ok: true, from: '2026-11-01', to: '2026-11-11' });
    // só hoje: nada fechado para reapurar
    expect(refreshPeriod({ from: '2026-11-12', to: '2026-11-12' }, '2026-11-12').ok).toBe(false);
    // 93 dias passam; 94 não
    expect(refreshPeriod({ from: '2026-08-01', to: '2026-11-01' }, '2026-11-12').ok).toBe(true);
    const tooLong = refreshPeriod({ from: '2026-07-31', to: '2026-11-01' }, '2026-11-12');
    expect(tooLong.ok).toBe(false);
    expect(tooLong.ok ? '' : tooLong.message).toContain('93 dias');
  });

  it('período padrão é o último mês fechado; URL malformada cai no padrão e ids inválidos somem', () => {
    const today = new Date(2026, 10, 12);
    expect(lastClosedMonth(today)).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    const f = parseMarginFilter(convertToParamMap({ de: '2026-13-x', ate: '2026-10-31', credor: [ID_A, 'nao-e-id'], carteira: ID_B }), today);
    expect(f).toEqual({ from: '2026-10-01', to: '2026-10-31', creditorIds: [ID_A], portfolioIds: [ID_B] });
    // fim antes do início também cai no padrão
    expect(parseMarginFilter(convertToParamMap({ de: '2026-09-30', ate: '2026-09-01' }), today).from).toBe('2026-10-01');
    expect(marginFilterParams({ from: '2026-09-01', to: '2026-09-30', creditorIds: [], portfolioIds: [ID_B] })).toEqual({
      de: '2026-09-01',
      ate: '2026-09-30',
      credor: null,
      carteira: [ID_B],
    });
  });

  it('margem com sinal escrito e "negativa" (a cor não é o único sinal); taxa sem base vira "—"', () => {
    expect(nbsp(marginText(1200))).toBe('R$ 1.200,00');
    expect(nbsp(marginText(-350.5))).toBe('−R$ 350,50 (negativa)');
    expect(marginText(null)).toBe('—');
    expect(rateText(0.375)).toBe('37,5%');
    expect(rateText(null)).toBe('—');
  });

  it('meses por extenso', () => {
    expect(monthLabel('2026-10')).toContain('2026');
    expect(monthLabel('2026-10-01')).toBe(monthLabel('2026-10'));
  });

  it('traduz os avisos da apuração em texto claro, com o próximo passo e a aba da configuração', () => {
    const hours = warningView({ code: 'HOURS_WITHOUT_BAND', count: 12.5 });
    expect(hours.text).toContain('12,5 horas de trabalho sem faixa de custo');
    expect(hours.text).toContain('custo zero');
    expect(hours.queryParams).toEqual({ aba: 'faixas' });
    expect(hours.permissions).toEqual(['COST_RATE_MANAGE']);
    const fx = warningView({ code: 'MISSING_FX_RATE', count: 1 });
    expect(fx.text).toContain('1 mês sem câmbio');
    expect(fx.queryParams).toEqual({ aba: 'cambio' });
    expect(fx.permissions).toEqual(['COST_MANAGE']);
    const tariff = warningView({ code: 'MISSING_TARIFF', count: 30 });
    expect(tariff.text).toContain('30 unidades de uso sem tarifa');
    expect(tariff.queryParams).toEqual({ aba: 'tarifas' });
    // código desconhecido: texto genérico, sem expor o código
    const other = warningView({ code: 'ALGO_NOVO', count: 2 });
    expect(other.text).not.toContain('ALGO_NOVO');
    // contagem zero não vira aviso
    expect(warningViews([{ code: 'MISSING_TARIFF', count: 0 }, { code: 'MISSING_FX_RATE', count: 2 }]).map((w) => w.code)).toEqual(['MISSING_FX_RATE']);
    expect(warningViews(null)).toEqual([]);
  });

  it('ordenação: padrão = menor margem líquida primeiro; taxa sem base sempre no fim; URL inválida cai no padrão', () => {
    const rows = [
      { ...line({ netMargin: 500, netMarginRate: 0.2 }), name: 'Beta' },
      { ...line({ netMargin: -200, netMarginRate: null }), name: 'Alfa' },
      { ...line({ netMargin: 900, netMarginRate: 0.4 }), name: 'Gama' },
    ];
    const name = (r: { name: string }) => r.name;
    expect(sortMarginRows(rows, DEFAULT_MARGIN_SORT, name).map(name)).toEqual(['Alfa', 'Beta', 'Gama']);
    expect(sortMarginRows(rows, { key: 'netMargin', desc: true }, name).map(name)).toEqual(['Gama', 'Beta', 'Alfa']);
    expect(sortMarginRows(rows, { key: 'netMarginRate', desc: true }, name).map(name)).toEqual(['Gama', 'Beta', 'Alfa']);
    expect(sortMarginRows(rows, { key: 'netMarginRate', desc: false }, name).map(name)).toEqual(['Beta', 'Gama', 'Alfa']);
    expect(sortMarginRows(rows, { key: 'name', desc: false }, name).map(name)).toEqual(['Alfa', 'Beta', 'Gama']);
    expect(parseMarginSort('-revenue')).toEqual({ key: 'revenue', desc: true });
    expect(parseMarginSort('drop table')).toEqual(DEFAULT_MARGIN_SORT);
    expect(formatMarginSort(DEFAULT_MARGIN_SORT)).toBeNull();
    expect(formatMarginSort({ key: 'roi', desc: true })).toBe('-roi');
  });

  it('cascata: receita, custos diretos, margem de contribuição, ociosidade e fixos, margem líquida', () => {
    const steps = waterfallSteps(line());
    expect(steps.map((s) => s.key)).toEqual(['revenue', 'HUMAN_DIRECT', 'AI', 'TELEPHONY', 'MESSAGING', 'VARIABLE_COMMISSION', 'LEGAL', 'contribution', 'HUMAN_IDLE', 'OVERHEAD', 'net']);
    expect(steps[1]).toMatchObject({ start: 2000, end: 1400, kind: 'cost' });
    expect(nbsp(steps[1].display)).toBe('−R$ 600,00');
    expect(steps[7]).toMatchObject({ start: 0, end: 1000, kind: 'subtotal' });
    expect(steps[8]).toMatchObject({ start: 1000, end: 900 });
    expect(steps[10]).toMatchObject({ start: 0, end: 750 });
    // margem negativa: o subtotal fica abaixo de zero e diz "negativa"
    const loss = waterfallSteps(line({ contributionMargin: -100, netMargin: -350 }));
    expect(loss.at(-1)?.display).toContain('negativa');
  });
});

describe('custos operacionais — regras puras', () => {
  const band = (name: string, validFrom: string, validTo: string | null = null) => ({ id: name + validFrom, name, hourlyCost: 40, validFrom, validTo });

  it('vigência em texto, em vigor e faixas que podem receber pessoas', () => {
    expect(validityText({ validFrom: '2026-01-01', validTo: null })).toBe('a partir de 01/01/2026');
    expect(validityText({ validFrom: '2026-01-01', validTo: '2026-09-30' })).toBe('de 01/01/2026 a 30/09/2026');
    expect(isCurrent({ validFrom: '2026-01-01', validTo: '2026-11-12' }, '2026-11-12')).toBe(true);
    expect(isCurrent({ validFrom: '2026-12-01', validTo: null }, '2026-11-12')).toBe(false);
    const bands = [band('Supervisor', '2026-01-01'), band('Júnior', '2025-01-01', '2025-12-31'), band('Júnior', '2026-01-01'), band('Antiga', '2024-01-01', '2024-12-31')];
    expect(assignableBandNames(bands, '2026-11-12')).toEqual(['Júnior', 'Supervisor']);
    expect(sortBands(bands).map((b) => `${b.name} ${b.validFrom}`)).toEqual(['Antiga 2024-01-01', 'Júnior 2026-01-01', 'Júnior 2025-01-01', 'Supervisor 2026-01-01']);
  });

  it('meses para escolha e mês normalizado', () => {
    const months = monthOptions(new Date(2026, 10, 12), 2, 1);
    expect(months.map((m) => m.value)).toEqual(['2026-12-01', '2026-11-01', '2026-10-01', '2026-09-01']);
    expect(normalizeMonth('2026-10')).toBe('2026-10-01');
    expect(normalizeMonth('2026-10-15')).toBe('2026-10-01');
  });

  it('avisos do servidor: sem câmbio algum, fixo sem base e dias ainda não apurados', () => {
    expect(warningView({ code: 'NO_FX_RATE', count: 1 }).tab).toBe('cambio');
    expect(warningView({ code: 'OVERHEAD_WITHOUT_BASIS', count: 2 }).text).toContain('2 custos fixos ficaram sem base de rateio');
    const late = warningView({ code: 'NOT_REFRESHED', count: 1 });
    expect(late.text).toContain('1 dia do período ainda não foi apurado');
    expect(late.linkLabel).toBeNull();
  });
});

