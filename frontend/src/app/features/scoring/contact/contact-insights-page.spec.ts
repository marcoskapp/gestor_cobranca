import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { button, render, setup, text, tick } from '../../../../testing/component-harness';
import { ContactInsightsView } from '../data/contact-intelligence.models';
import { ContactInsightsPage } from './contact-insights-page';

const URL = '/api/v1/scoring/contact-insights';

function insights(overrides: Partial<ContactInsightsView> = {}): ContactInsightsView {
  return {
    from: '2026-01-01',
    to: '2026-03-31',
    creditorId: 'c-1',
    portfolioId: null,
    personType: 'COMPANY',
    minAttempts: 10,
    totals: { attempts: 120, successes: 48, rate: 0.4, debtorInitiated: 7, agreements: 12, completed: 6, broken: 2, fulfillmentRate: 0.75 },
    heatmap: [
      { weekday: 2, startHour: 18, attempts: 30, successes: 18, rate: 0.6 },
      { weekday: 6, startHour: 9, attempts: 4, successes: 4, rate: 1 },
    ],
    firstSuccessCurve: [
      { attempts: 1, contacts: 20, cumulativeShare: 0.5 },
      { attempts: 2, contacts: 20, cumulativeShare: 1 },
    ],
    neverAnswered: 9,
    channels: [{ channel: 'PHONE', attempts: 100, successes: 40, rate: 0.4, debtorInitiated: 0, reads: 0 }],
    agreementsByAttempts: [{ bucket: '2-3', agreements: 12, completed: 6, broken: 2, active: 3, cancelled: 1, fulfillmentRate: 0.75 }],
    medianAttemptsToAgreement: 3,
    medianAttemptsToCompletedAgreement: 2.5,
    medianDaysFirstContactToAgreement: 12,
    byPersonType: [{ personType: 'COMPANY', attempts: 120, successes: 48, rate: 0.4, agreements: 12, completed: 6, broken: 2, fulfillmentRate: 0.75 }],
    byContactOrigin: [
      { origin: 'NEW', attempts: 40, successes: 18, rate: 0.45 },
      { origin: 'EXISTING', attempts: 300, successes: 90, rate: 0.3 },
    ],
    notice: 'Retrato agregado para orientar horários e canais.',
    ...overrides,
  };
}

const settle = async (f: { detectChanges(): void }) => {
  await new Promise((r) => setTimeout(r, 300));
  f.detectChanges();
  await tick();
};

describe('ContactInsightsPage', () => {
  it('consulta com os filtros da URL e mostra indicadores, mapa de calor, gráficos e cortes por tipo de pessoa e por contato novo × antigo', async () => {
    const h = setup(ContactInsightsPage, ['SCORING_AREA_VIEW', 'SCORING_VIEW']);
    await TestBed.inject(Router).navigateByUrl('/score/contato?de=2026-01-01&ate=2026-03-31&credor=c-1&pessoa=COMPANY');
    const f = TestBed.createComponent(ContactInsightsPage);
    f.detectChanges();
    await settle(f);
    const req = h.http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('from')).toBe('2026-01-01');
    expect(req.request.params.get('to')).toBe('2026-03-31');
    expect(req.request.params.get('creditorId')).toBe('c-1');
    expect(req.request.params.get('personType')).toBe('COMPANY');
    expect(req.request.params.has('portfolioId')).toBe(false);
    req.flush(insights());
    for (const r of h.http.match(() => true)) {
      r.flush(r.request.url === '/api/v1/creditors/c-1' ? { id: 'c-1', legalName: 'Banco Horizonte S.A.' } : {});
    }
    await tick();
    f.detectChanges();
    const t = text(f);
    expect(t).toContain('40,0%');
    expect(t).toContain('48 de 120 tentativas');
    expect(t).toContain('75,0%');
    expect(t).toContain('Melhor combinação no período: terça-feira, 18h–21h, com 60,0%');
    // sábado 09h com 4 tentativas: abaixo do mínimo, sem taxa
    const heat = (f.nativeElement as HTMLElement).querySelector('table.ci-heat')!;
    expect(heat.querySelectorAll('th[scope="col"]')).toHaveLength(7);
    expect(heat.querySelectorAll('th[scope="row"]')).toHaveLength(7);
    expect(heat.textContent).toContain('Sábado, 09h–12h: poucas tentativas (4)');
    expect(t).toContain('Tentativas até o 1º atendimento');
    expect(t).toContain('9 contatos tiveram tentativas e nenhum sucesso');
    expect(t).toContain('Taxa por canal');
    expect(t).toContain('Contatos até o acordo');
    expect(t).toContain('2,5 tentativas');
    expect(t).toContain('Pessoa jurídica');
    expect(t).toContain('Acordos em vigor e cancelados ficam fora da taxa');
    expect(t).toContain('Contato novo × antigo');
    expect(t).toContain('Recebido em carga posterior');
    expect(t).toContain('45,0%');
  });

  it('período acima de 400 dias: mostra o problema e não chama a API', async () => {
    const h = setup(ContactInsightsPage, ['SCORING_AREA_VIEW', 'SCORING_VIEW']);
    await TestBed.inject(Router).navigateByUrl('/score/contato?de=2024-01-01&ate=2026-03-31');
    const f = TestBed.createComponent(ContactInsightsPage);
    f.detectChanges();
    await settle(f);
    expect(h.http.match((r) => r.url === URL)).toHaveLength(0);
    expect(text(f)).toContain('Período máximo de 400 dias.');
  });

  it('sem tentativas: estado vazio com o próximo passo; erro: "Tentar novamente"', async () => {
    const h = setup(ContactInsightsPage, ['SCORING_AREA_VIEW', 'SCORING_VIEW']);
    const empty = insights({
      totals: { attempts: 0, successes: 0, rate: null, debtorInitiated: 0, agreements: 0, completed: 0, broken: 0, fulfillmentRate: null },
      heatmap: [],
      firstSuccessCurve: [],
      channels: [],
      agreementsByAttempts: [],
      byPersonType: [],
    });
    const f = await render(ContactInsightsPage, h.http, (url) => (url === URL ? empty : undefined), {}, 3);
    await settle(f);
    for (const r of h.http.match(() => true)) {
      r.flush(empty);
    }
    await tick();
    f.detectChanges();
    expect(text(f)).toContain('Os dados aparecem conforme as ligações e mensagens com contato do devedor são registradas');

    const g = TestBed.createComponent(ContactInsightsPage);
    g.detectChanges();
    await settle(g);
    h.http.expectOne((r) => r.url === URL).flush({ code: 'scoring.range_too_long' }, { status: 400, statusText: 'Bad Request' });
    await tick();
    g.detectChanges();
    expect(text(g)).toContain('O período consultado pode ter no máximo 400 dias.');
    expect(button(g, 'Tentar novamente')).toBeDefined();
  });
});
