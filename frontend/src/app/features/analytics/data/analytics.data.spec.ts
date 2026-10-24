import { convertToParamMap } from '@angular/router';
import { analyticsParams } from './analytics.api';
import { filterParams, parseFilter, presetOf, presetRange } from './analytics.filters';
import { count, percent, ratioDetail, seconds, unavailable } from './analytics.format';
import { groupLabel, monthLabel } from './analytics.labels';
import { AnalyticsFilter, Ratio } from './analytics.models';

const TODAY = new Date(2026, 9, 24);
const ID = '0193a2b4-1111-7222-8333-444455556666';

describe('filtros dos painéis na URL', () => {
  it('atalhos de período incluem as duas pontas', () => {
    expect(presetRange('7d', TODAY)).toEqual({ from: '2026-10-18', to: '2026-10-24' });
    expect(presetRange('30d', TODAY)).toEqual({ from: '2026-09-25', to: '2026-10-24' });
    expect(presetRange('month', TODAY)).toEqual({ from: '2026-10-01', to: '2026-10-24' });
    expect(presetRange('previous-month', TODAY)).toEqual({ from: '2026-09-01', to: '2026-09-30' });
    expect(presetOf('2026-09-01', '2026-09-30', TODAY)).toBe('previous-month');
    expect(presetOf('2026-09-02', '2026-09-30', TODAY)).toBe('custom');
  });

  it('lê a URL descartando valores malformados e aplica padrões', () => {
    const f = parseFilter(
      convertToParamMap({ de: '2026-06-01', ate: '2026-06-30', credor: [ID, 'x; drop'], canal: ['PHONE', 'phone'], agrupar: 'TEAM' }),
      TODAY,
      ['CREDITOR', 'MONTH'],
      'MONTH',
    );
    expect(f.from).toBe('2026-06-01');
    expect(f.creditorIds).toEqual([ID]);
    expect(f.channels).toEqual(['PHONE']);
    expect(f.groupBy).toBe('MONTH');
    const fallback = parseFilter(convertToParamMap({ de: '2026-07-01', ate: '2026-06-01' }), TODAY, [], null);
    expect(fallback).toMatchObject({ from: '2026-09-25', to: '2026-10-24', groupBy: null });
  });

  it('ida e volta URL ↔ filtro e parâmetros repetidos na API', () => {
    const filter: AnalyticsFilter = {
      from: '2026-06-01',
      to: '2026-06-30',
      creditorIds: [ID, ID.replace('6666', '7777')],
      portfolioIds: [],
      operatorIds: [],
      teamIds: [],
      channels: ['PHONE'],
      groupBy: 'CREDITOR',
    };
    const params = filterParams(filter);
    expect(params['carteira']).toBeNull();
    expect(parseFilter(convertToParamMap(params as Record<string, string | string[]>), TODAY, ['CREDITOR'], null)).toEqual(filter);
    const api = analyticsParams(filter).toString();
    expect(api).toBe(`from=2026-06-01&to=2026-06-30&creditorId=${ID}&creditorId=${ID.replace('6666', '7777')}&channel=PHONE&groupBy=CREDITOR`);
    expect(analyticsParams(filter, false).has('groupBy')).toBe(false);
  });
});

describe('formatação dos indicadores', () => {
  const ratio = (value: number | null, numerator: number, denominator: number, reason: string | null = null): Ratio => ({
    value,
    numerator,
    denominator,
    unavailableReason: reason,
  });

  it('taxa sempre acompanhada da base', () => {
    expect(percent(ratio(0.275, 1100, 4000))).toBe('27,5%');
    expect(ratioDetail(ratio(0.275, 1100, 4000))).toBe('1.100 de 4.000');
    expect(ratioDetail(ratio(0.275, 1100, 4000), 'money').replace(/\u00a0/g, ' ')).toBe('R$ 1.100,00 de R$ 4.000,00');
    expect(percent(ratio(null, 5, 0, 'no_denominator'))).toBe('—');
    expect(ratioDetail(ratio(null, 5, 0, 'no_denominator'))).toBe('sem base no período');
    expect(unavailable({ value: null, numerator: null, denominator: null, unavailableReason: 'privacy_minimum' })).toContain('privacidade');
  });

  it('contagens, tempos e rótulos', () => {
    expect(count(12345)).toBe('12.345');
    expect(seconds(200)).toBe('3 min 20 s');
    expect(seconds(45)).toBe('45 s');
    expect(monthLabel('2026-06')).toBe('jun/26');
    expect(groupLabel('ORIGIN', 'ASSISTANT', 'ASSISTANT')).toBe('Assistente de IA');
    expect(groupLabel('CREDITOR', 'c-1', 'Banco Alfa')).toBe('Banco Alfa');
  });
});
