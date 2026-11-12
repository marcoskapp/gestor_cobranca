import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, tick } from '../../../../testing/campaign-fixtures';
import { CREDITOR_A, CREDITOR_B, PORTFOLIO_A1, marginReport } from '../../../../testing/costing-fixtures';
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

  async function render(report: MarginReport | ((req: never) => unknown)): Promise<ComponentFixture<MarginPanel>> {
    const fixture = TestBed.createComponent(MarginPanel);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, { ...names, '/api/v1/costing/margin': report });
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
    flushAll(http, { ...names, '/api/v1/costing/margin': marginReport() });
  });

  it('período acima de 93 dias: avisa sem chamar o servidor', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    query.next(convertToParamMap({ de: '2026-01-01', ate: '2026-10-31' }));
    const fixture = await render(marginReport());
    (el(fixture).querySelector('[data-tour="margin-refresh"]') as HTMLButtonElement).click();
    http.expectNone((r) => r.url === '/api/v1/costing/refresh');
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
    await tick();
    fixture.detectChanges();
    expect(text(el(fixture))).toContain('Não foi possível carregar a margem');
    expect(text(el(fixture))).toContain('Tentar novamente');
  });
});
