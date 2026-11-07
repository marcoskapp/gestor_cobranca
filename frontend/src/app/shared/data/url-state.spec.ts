import { convertToParamMap } from '@angular/router';
import { formatSortParam, parseSortParam, readPageParams, serverSort } from './paged-query';
import { readUrlValues, urlParam, urlParamsOf } from './url-state';

describe('estado de lista na URL', () => {
  const codecs = {
    creditor: urlParam.ids('credor'),
    status: urlParam.text('situacao'),
    kind: urlParam.choice('tipo', ['A', 'B']),
    withBalance: urlParam.flag('saldo'),
    from: urlParam.date('de'),
  };
  const defaults = {
    creditor: [] as string[],
    status: 'ACTIVE',
    kind: '' as '' | 'A' | 'B',
    withBalance: false,
    from: null as string | null,
    other: 'x',
  };

  it('lê os parâmetros e cai no padrão quando ausentes ou inválidos', () => {
    const params = convertToParamMap({ credor: 'c-1,c-2', tipo: 'Z', saldo: '1', de: '2026-13', outro: 'y' });
    expect(readUrlValues(params, codecs, defaults)).toEqual({ ...defaults, creditor: ['c-1', 'c-2'], withBalance: true });
    expect(readUrlValues(convertToParamMap({ credor: 'a,<script>' }), codecs, defaults).creditor).toEqual([]);
  });

  it('valor igual ao padrão sai da URL; vazio diferente do padrão fica como parâmetro vazio', () => {
    expect(urlParamsOf(defaults, codecs, defaults)).toEqual({ credor: null, situacao: null, tipo: null, saldo: null, de: null });
    const all = urlParamsOf({ ...defaults, status: '', creditor: ['c-1'], from: '2026-01-31' }, codecs, defaults);
    expect(all).toMatchObject({ situacao: '', credor: 'c-1', de: '2026-01-31' });
    // ida e volta: "Todas" continua "Todas"
    expect(readUrlValues(convertToParamMap({ situacao: '' }), codecs, defaults).status).toBe('');
  });

  it('página (1 em diante), tamanho e ordenação', () => {
    expect(readPageParams(convertToParamMap({ pagina: '3', tamanho: '50' }), '', 20)).toEqual({ page: 2, size: 50 });
    expect(readPageParams(convertToParamMap({ pagina: '-1', tamanho: '9999' }), '', 20)).toEqual({ page: 0, size: 200 });
    const fields = { balance: 'openBalance' };
    expect(parseSortParam('-balance', fields)).toEqual({ column: 'balance', direction: 'desc' });
    expect(parseSortParam('owner', fields)).toBeNull();
    expect(formatSortParam({ column: 'balance', direction: 'asc' })).toBe('balance');
    expect(serverSort({ column: 'balance', direction: 'desc' }, fields)).toBe('openBalance,desc');
    expect(serverSort({ column: 'x', direction: 'desc' }, fields)).toBeUndefined();
  });
});
