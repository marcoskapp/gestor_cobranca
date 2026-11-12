import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, tick } from '../../../../testing/campaign-fixtures';
import { CREDITOR_A, CREDITOR_B, PORTFOLIO_A1, marginLine, marginReport, marginTrend } from '../../../../testing/costing-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { MarginReport } from '../data/costing.models';
import { MarginPanel } from './margin-panel';

describe('painel de margem por carteira', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const allCreditors = signal(true);
  const query = new BehaviorSubject(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31' }));

  beforeEach(() => {
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31' }));
    allCreditors.set(true);
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: () => fakeUser({ allCreditors: allCreditors() }),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: query.asObservable(), get snapshot() { return { queryParamMap: query.value }; } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const names = {
    [`/api/v1/creditors/${CREDITOR_A}`]: { id: CREDITOR_A, legalName: 'Banco Alfa S.A.', tradeName: 'Banco Alfa' },
    [`/api/v1/creditors/${CREDITOR_B}`]: { id: CREDITOR_B, legalName: 'Loja Beta Ltda.', tradeName: null },
    [`/api/v1/portfolios/${PORTFOLIO_A1}`]: { id: PORTFOLIO_A1, code: 'A1', name: 'Cartão 2026' },
  };

  async function render(report: MarginReport | ((req: never) => unknown), trend: unknown = marginTrend()): Promise<ComponentFixture<MarginPanel>> {
    const fixture = TestBed.createComponent(MarginPanel);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, { ...names, '/api/v1/costing/margin': report, '/api/v1/costing/margin/trend': trend });
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ');

  it('filtros da URL viram parâmetros da API; cartões, cascata e tabela vêm da resposta', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(((req: { request: { params: { get(n: string): string | null } } }) => {
      expect(req.request.params.get('from')).toBe('2026-10-01');
      expect(req.request.params.get('to')).toBe('2026-10-31');
      return marginReport();
    }) as never);
    const kpis = text(el(fixture).querySelector('[data-tour="margin-kpis"]'));
    for (const label of ['Receita', 'Custo total', 'Margem de contribuição', 'Margem líquida', 'Custo por real recuperado', 'Custo por acordo cumprido']) {
      expect(kpis).toContain(label);
    }
    expect(kpis).toContain('de R$ 10.000,00 recuperado');
    expect(kpis).toContain('R$ 750,00');
    expect(kpis).toContain('37,5% da receita · ROI 60,0%');
    expect(kpis).toContain('R$ 1.000,00 direto + R$ 250,00 de ociosidade e fixos');
    expect(kpis).toContain('5 acordos cumpridos no período');
    // "estimado" explicado na tela
    expect(text(el(fixture).querySelector('[data-tour="margin-basis"]'))).toContain('Estimado');
    // cascata com os custos com sinal e as duas margens
    const waterfall = text(el(fixture).querySelector('[data-tour="margin-waterfall"]'));
    expect(waterfall).toContain('Pessoas (tempo direto)');
    expect(waterfall).toContain('−R$ 600,00');
    expect(waterfall).toContain('Margem de contribuição');
    // tabela: menor margem líquida primeiro; margem negativa escrita, não só na cor
    const rows = [...el(fixture).querySelectorAll('tbody tr.mp__creditor')].map((r) => text(r));
    expect(rows[0]).toContain('Loja Beta Ltda.');
    expect(rows[0]).toContain('−R$ 150,00 (negativa)');
    expect(rows[1]).toContain('Banco Alfa');
    expect(el(fixture).querySelector('tbody tr.mp__creditor.mp__negative')).not.toBeNull();
    // sem avisos, sem bloco de avisos; sem REPORT_EXPORT, sem exportar; sem COST_MANAGE, sem reapurar
    expect(el(fixture).querySelector('[data-tour="margin-warnings"]')).toBeNull();
    expect(el(fixture).querySelector('[data-tour="margin-export"]')).toBeNull();
    expect(el(fixture).querySelector('[data-tour="margin-refresh"]')).toBeNull();
  });

  it('abre o credor para ver as carteiras e as colunas por categoria sob demanda', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(marginReport());
    expect(el(fixture).querySelector('tr.mp__portfolio')).toBeNull();
    const expand = el(fixture).querySelector<HTMLButtonElement>('button.mp__expand')!;
    expect(expand.getAttribute('aria-expanded')).toBe('false');
    expand.click();
    fixture.detectChanges();
    expect(text(el(fixture).querySelector('tr.mp__portfolio'))).toContain('Cartão 2026');
    expect(expand.getAttribute('aria-expanded')).toBe('true');
    expect(text(el(fixture).querySelector('thead'))).not.toContain('Telefonia');
    const toggle = el(fixture).querySelector<HTMLInputElement>('#mp-categories')!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text(el(fixture).querySelector('thead'))).toContain('Telefonia');
  });

  it('ordenar grava a coluna na URL (o voltar e o link compartilhado mantêm a ordem)', async () => {
    permissions.set(['COST_VIEW']);
    const fixture = await render(marginReport());
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const revenue = [...el(fixture).querySelectorAll<HTMLButtonElement>('button.mp__sort')].find((b) => b.textContent?.includes('Receita'))!;
    revenue.click();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { ordem: '-revenue' }, replaceUrl: true }));
  });

  it('avisos da apuração em texto claro, com atalho para a configuração só para quem pode corrigir', async () => {
    permissions.set(['COST_VIEW', 'COST_RATE_MANAGE']);
    const fixture = await render(
      marginReport({ warnings: [{ code: 'HOURS_WITHOUT_BAND', count: 12 }, { code: 'MISSING_FX_RATE', count: 1 }, { code: 'MISSING_TARIFF', count: 0 }] }),
    );
    const box = el(fixture).querySelector('[data-tour="margin-warnings"]')!;
    expect(text(box)).toContain('12 horas de trabalho sem faixa de custo');
    expect(text(box)).toContain('1 mês sem câmbio do dólar');
    expect(text(box)).not.toContain('tarifa');
    expect(text(box)).not.toContain('HOURS_WITHOUT_BAND');
    const links = [...box.querySelectorAll('a')].map((a) => decodeURIComponent(a.getAttribute('href') ?? ''));
    // faixas: COST_RATE_MANAGE; câmbio exige COST_MANAGE, que o perfil não tem → sem atalho
    expect(links).toEqual(['/organizacao/custos?aba=faixas']);
  });

  it('exportar e reapurar só com as permissões; exportar chama a rota de exportação com o formato', async () => {
    permissions.set(['COST_VIEW', 'REPORT_EXPORT', 'COST_MANAGE']);
    const fixture = await render(marginReport());
    expect(el(fixture).querySelector('[data-tour="margin-export"]')).not.toBeNull();
    expect(el(fixture).querySelector('[data-tour="margin-refresh"]')).not.toBeNull();
    (el(fixture).querySelector('[data-tour="margin-export"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const xlsx = [...document.querySelectorAll<HTMLButtonElement>('[ngbdropdownitem], .dropdown-item')].find((b) => b.textContent?.includes('XLSX'))!;
    xlsx.click();
    const req = http.expectOne((r) => r.url.startsWith('/api/v1/costing/margin/export'));
    expect(req.request.url).toContain('format=xlsx');
    expect(req.request.url).toContain('from=2026-10-01');
    // o download dispara o clique num link temporário: sem navegação no ambiente de teste
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    req.flush(new Blob(['x']));
    expect(click).toHaveBeenCalled();
    click.mockRestore();
  });

  it('reapurar exige todos os credores (o servidor exige escopo total); COST_RATE_MANAGE também reapura', async () => {
    permissions.set(['COST_VIEW', 'COST_RATE_MANAGE']);
    let fixture = await render(marginReport());
    expect(el(fixture).querySelector('[data-tour="margin-refresh"]')).not.toBeNull();
    fixture.destroy();
    permissions.set(['COST_VIEW', 'COST_MANAGE', 'COST_RATE_MANAGE']);
    allCreditors.set(false);
    fixture = await render(marginReport());
    expect(el(fixture).querySelector('[data-tour="margin-refresh"]')).toBeNull();
  });

  it('reapurar envia o período do filtro (mês fechado) e recarrega o painel', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    const fixture = await render(marginReport());
    (el(fixture).querySelector('[data-tour="margin-refresh"]') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.url === '/api/v1/costing/refresh');
    expect(req.request.params.get('from')).toBe('2026-10-01');
    expect(req.request.params.get('to')).toBe('2026-10-31');
    req.flush({});
    await tick();
    flushAll(http, { ...names, '/api/v1/costing/margin': marginReport(), '/api/v1/costing/margin/trend': marginTrend() });
  });

  it('período acima de 93 dias: avisa sem chamar o servidor', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    query.next(convertToParamMap({ de: '2026-01-01', ate: '2026-10-31' }));
    const fixture = await render(marginReport());
    (el(fixture).querySelector('[data-tour="margin-refresh"]') as HTMLButtonElement).click();
    http.expectNone((r) => r.url === '/api/v1/costing/refresh');
  });

  const reconciliation = (status: 'ESTIMATED' | 'PARTIAL' | 'RECONCILED') => ({
    status,
    months: [{ month: '2026-10', status, invoiced: ['TELEPHONY' as const], pending: status === 'PARTIAL' ? ['WHATSAPP' as const, 'SMS' as const] : [] }],
    adjustments: { TELEPHONY: 30, MESSAGING: 0, AI: 0 },
    invoices: [{ month: '2026-10', item: 'TELEPHONY' as const, estimated: 50, invoiced: 80, adjustment: 30 }],
  });

  it('base "Conciliado com a fatura" vem da URL, vai à API e explica o ajuste; a escolha grava na URL', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31', base: 'conciliado' }));
    const fixture = await render(((req: { request: { params: { get(n: string): string | null } } }) => {
      expect(req.request.params.get('basis')).toBe('RECONCILED');
      return marginReport({ basis: 'RECONCILED', reconciliation: reconciliation('PARTIAL') });
    }) as never);
    const basis = el(fixture).querySelector('[data-tour="margin-basis"]')!;
    expect(text(basis)).toContain('Parcialmente conciliado');
    expect(basis.querySelector<HTMLInputElement>('#mp-basis-RECONCILED')!.checked).toBe(true);
    const box = text(el(fixture).querySelector('[data-tour="margin-reconciliation"]'));
    // telefonia na base conciliada (R$ 80) = estimado R$ 50 + ajuste R$ 30
    expect(box).toContain('Telefonia: estimado R$ 50,00, com a fatura R$ 80,00 (ajuste +R$ 30,00)');
    expect(box).toContain('out. de 2026: WhatsApp e SMS');
    expect(box).toContain('Informar faturas');
    expect(box).not.toMatch(/TELEPHONY|WHATSAPP|PARTIAL/);
    expect(text(el(fixture).querySelector('[data-tour="kpi-total-cost"]'))).toContain('ajuste pela fatura +R$ 30,00');
    expect(text(el(fixture).querySelector('[data-tour="margin-waterfall"]'))).toContain('incluem o ajuste pela fatura');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const estimated = basis.querySelector<HTMLInputElement>('#mp-basis-ESTIMATED')!;
    estimated.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { base: null }, replaceUrl: true }));
  });

  it('base estimada (padrão) não manda o parâmetro e sugere a conciliada quando há faturas', async () => {
    permissions.set(['COST_VIEW']);
    const fixture = await render(((req: { request: { params: { has(n: string): boolean } } }) => {
      expect(req.request.params.has('basis')).toBe(false);
      return marginReport({ reconciliation: reconciliation('RECONCILED') });
    }) as never);
    const basis = text(el(fixture).querySelector('[data-tour="margin-basis"]'));
    expect(basis).toContain('Estimado');
    expect(basis).toContain('Há faturas de canal informadas no período');
    expect(el(fixture).querySelector('[data-tour="margin-reconciliation"]')).toBeNull();
  });

  it('conciliado com escopo restrito: sem atalho para as faturas (a aba é da assessoria inteira)', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    allCreditors.set(false);
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31', base: 'conciliado' }));
    const fixture = await render(marginReport({ basis: 'RECONCILED', reconciliation: { ...reconciliation('PARTIAL'), invoices: null } }));
    const box = el(fixture).querySelector('[data-tour="margin-reconciliation"]')!;
    expect(text(box)).toContain('Telefonia: estimado');
    expect(text(box)).not.toContain('Informar faturas');
    expect(box.querySelector('table')).toBeNull();
  });

  it('quem não vê todos os credores é avisado de que os totais são a soma dos próprios credores', async () => {
    permissions.set(['COST_VIEW']);
    allCreditors.set(false);
    const fixture = await render(marginReport());
    expect(text(el(fixture))).toContain('Você vê só os credores do seu escopo');
  });

  it('sem receita nem custo: estado vazio com o próximo passo; erro: tentar novamente', async () => {
    permissions.set(['COST_VIEW']);
    const empty = await render(marginReport({ creditors: [], months: [], totals: { ...marginReport().totals, revenue: 0, totalCost: 0 } }));
    expect(text(el(empty))).toContain('Sem receita nem custo no período');
    expect(el(empty).querySelector('[data-tour="margin-kpis"]')).toBeNull();
    empty.destroy();

    const fixture = TestBed.createComponent(MarginPanel);
    fixture.detectChanges();
    await tick();
    http.expectOne((r) => r.url === '/api/v1/costing/margin').flush({ title: 'falha' }, { status: 500, statusText: 'Erro' });
    http.expectOne((r) => r.url === '/api/v1/costing/margin/trend').flush(marginTrend());
    await tick();
    fixture.detectChanges();
    expect(text(el(fixture))).toContain('Não foi possível carregar a margem');
    expect(text(el(fixture))).toContain('Tentar novamente');
  });

  it('tendência de 6 meses: minigráfico com a frase acessível por credor e carteira, sem pesar a resposta principal', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(marginReport(), ((req: {
      request: { params: { get(n: string): string | null } };
    }) => {
      // mesmos filtros do painel
      expect(req.request.params.get('from')).toBe('2026-10-01');
      expect(req.request.params.get('to')).toBe('2026-10-31');
      return marginTrend();
    }) as never);
    expect(text(el(fixture).querySelector('thead'))).toContain('Tendência');
    const rows = [...el(fixture).querySelectorAll('tbody tr.mp__creditor')];
    const label = (row: Element) => row.querySelector('[role="img"]')?.getAttribute('aria-label') ?? '';
    // B (menor margem) primeiro: caiu até ficar negativa, com a palavra "negativa"
    expect(label(rows[0])).toBe('Margem líquida de mai. a out.: caiu de 10,0% para -30,0% (negativa) da receita.');
    expect(label(rows[1])).toBe('Margem líquida de mai. a out.: subiu de 8,0% para 12,0% da receita. 1 mês sem receita.');
    // a carteira tem a própria série
    el(fixture).querySelector<HTMLButtonElement>('button.mp__expand')!.click();
    fixture.detectChanges();
    expect(label(el(fixture).querySelector('tr.mp__portfolio')!)).toContain('subiu de 8,0% para 12,0%');
    // dica no foco, mês a mês
    (rows[1].querySelector('[role="img"]') as HTMLElement).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(text(el(fixture).querySelector('[role="tooltip"]'))).toContain('mai.: sem receita');
  });

  it('tendência indisponível não derruba o painel; credor sem fato nos 6 meses mostra "sem base"', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW']);
    const fixture = TestBed.createComponent(MarginPanel);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.url === '/api/v1/costing/margin/trend')) {
        req.flush({ title: 'falha' }, { status: 500, statusText: 'Erro' });
      }
      flushAll(http, { ...names, '/api/v1/costing/margin': marginReport() });
    }
    fixture.detectChanges();
    expect(text(el(fixture).querySelector('[data-tour="margin-kpis"]'))).toContain('Receita');
    expect(text(el(fixture).querySelector('tbody tr.mp__creditor'))).toContain('Indisponível');
    fixture.destroy();

    const empty = await render(marginReport(), marginTrend({ creditors: [] }));
    expect(el(empty).querySelector('tbody tr.mp__creditor [role="img"]')?.getAttribute('aria-label')).toBe(
      'Margem líquida de mai. a out.: sem receita nos meses apurados.',
    );
  });

  it('mapa de dispersão: carteiras nos quadrantes com os cortes escritos; sem base fora com a contagem; clique filtra pela carteira', async () => {
    permissions.set(['COST_VIEW']);
    const P2 = '00000000-0000-0000-0000-0000000000a2';
    const P3 = '00000000-0000-0000-0000-0000000000a3';
    const report = marginReport();
    report.creditors[0].portfolios = [
      { ...marginLine({ recovered: 100000, revenue: 10000, netMargin: 3000, netMarginRate: 0.3 }), portfolioId: PORTFOLIO_A1 },
      { ...marginLine({ recovered: 4000, revenue: 800, netMargin: -200, netMarginRate: -0.25 }), portfolioId: P2 },
      { ...marginLine({ recovered: 0, revenue: 0, netMargin: -50, netMarginRate: null }), portfolioId: P3 },
      { ...marginLine({ recovered: 0, revenue: 0, netMargin: -10, netMarginRate: null }), portfolioId: null },
    ];
    const fixture = await render(report);
    const map = el(fixture).querySelector('[data-tour="margin-scatter"]')!;
    expect(text(map)).toContain('Cortes: volume na mediana do recuperado');
    for (const q of ['Rende e escala', 'Rende pouco', 'Consome', 'Revisar contrato']) {
      expect(text(map)).toContain(q);
    }
    expect(text(map)).toContain('1 carteira sem base');
    const points = [...map.querySelectorAll<SVGGElement>('g.sc__point')];
    expect(points.length).toBe(2);
    const negative = points.find((p) => p.getAttribute('aria-label')!.includes('(negativa)'))!;
    expect(negative.getAttribute('aria-label')).toContain('Quadrante: Revisar contrato');
    expect(negative.querySelector('path.sc__mark--neg')).not.toBeNull();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    negative.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    // passo novo do histórico (sem replaceUrl): o "voltar" retorna ao mapa
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: expect.objectContaining({ credor: [CREDITOR_A], carteira: [P2], de: '2026-10-01', ate: '2026-10-31' }) }),
    );
    expect(navigate.mock.calls[0][1]?.replaceUrl).toBeUndefined();
    // tabela equivalente com link para o mesmo filtro
    [...map.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Ver tabela'))!.click();
    fixture.detectChanges();
    const links = [...map.querySelectorAll('tbody a')].map((a) => decodeURIComponent(a.getAttribute('href') ?? ''));
    expect(links.length).toBe(2);
    expect(links.some((h) => h.includes(`carteira=${P2}`) && h.includes(`credor=${CREDITOR_A}`))).toBe(true);
  });
});
