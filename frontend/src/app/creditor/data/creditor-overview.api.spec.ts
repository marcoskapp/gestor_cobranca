import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { SKIP_ERROR_NOTIFICATION } from '../../core/http/http-context';
import { CreditorOverviewApi } from './creditor-overview.api';
import { agreementsDetail, agreementsValue, monthLabel, periodOf, portfolioRows, ratioText, variationText } from './creditor-indicators';

/** Chamadas do Início e da atividade: sem id de credor ou de pessoa, erros mostrados na própria tela. */
describe('API do Início do portal do credor', () => {
  let api: CreditorOverviewApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(CreditorOverviewApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('overview leva só o período', async () => {
    const done = firstValueFrom(api.overview('LAST_MONTH'));
    const req = http.expectOne((r) => r.url === '/api/creditor/v1/overview');
    expect(req.request.params.keys()).toEqual(['period']);
    expect(req.request.params.get('period')).toBe('LAST_MONTH');
    expect(req.request.context.get(SKIP_ERROR_NOTIFICATION)).toBe(true);
    req.flush({ period: 'LAST_MONTH' });
    expect((await done).period).toBe('LAST_MONTH');
  });

  it('awaiting sem parâmetros', () => {
    api.awaiting().subscribe();
    const req = http.expectOne('/api/creditor/v1/awaiting');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
    req.flush({});
  });

  it('activity com escopo, tamanho e cursor só quando houver', () => {
    api.activity('team').subscribe();
    const first = http.expectOne((r) => r.url === '/api/creditor/v1/activity');
    expect(first.request.params.get('scope')).toBe('team');
    expect(first.request.params.get('size')).toBe('20');
    expect(first.request.params.has('before')).toBe(false);
    first.flush({});
    api.activity('own', 'abc_1', 50).subscribe();
    const next = http.expectOne((r) => r.url === '/api/creditor/v1/activity');
    expect(next.request.params.get('before')).toBe('abc_1');
    expect(next.request.params.get('size')).toBe('50');
    next.flush({});
  });
});

describe('textos dos indicadores do credor', () => {
  it('acordos em dia: porcentagem truncada (nunca arredonda para 80% em alerta) e texto sem acordo', () => {
    expect(ratioText(0.82)).toBe('82%');
    expect(ratioText(0.7999)).toBe('79,9%');
    const none = { onTrack: 0, active: 0, ratio: null, alert: false, referenceDate: '2026-11-26' };
    expect(agreementsValue(none)).toBe('Nenhum acordo em vigor');
    expect(agreementsDetail(none)).toContain('26/11/2026');
    expect(agreementsDetail({ onTrack: 1, active: 2, ratio: 0.5, alert: true, referenceDate: '2026-11-26' }))
      .toBe('1 de 2 acordos em vigor em 26/11/2026. Abaixo de 80%: 1 acordo tem parcela vencida e não paga');
  });

  it('variação com sinal e texto', () => {
    expect(variationText(110, 100)).toBe('+10% sobre o período anterior');
    expect(variationText(92, 100)).toBe('−8% sobre o período anterior');
    expect(variationText(100, 100)).toBe('Igual ao período anterior');
    expect(variationText(100, 0)).toBe('Sem recuperado no período anterior para comparar');
  });

  it('período da URL, meses e carteiras', () => {
    expect(periodOf('este-ano')).toBe('THIS_YEAR');
    expect(periodOf('qualquer')).toBe('THIS_MONTH');
    expect(monthLabel('2026-11')).toBe('nov/26');
    const rows = portfolioRows([
      { portfolioId: null, name: null, recovered: 5, others: true },
      { portfolioId: 'a', name: 'A', recovered: 1, others: false },
      { portfolioId: 'b', name: 'B', recovered: 9, others: false },
    ]);
    expect(rows.map((r) => r.label)).toEqual(['B', 'A', 'Outros']);
  });
});
