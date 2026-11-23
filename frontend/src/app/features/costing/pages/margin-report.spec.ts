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
import { MarginReportPage } from './margin-report';

describe('relatório completo de margem por carteira', () => {
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

  async function render(report: MarginReport | ((req: never) => unknown), trend: unknown = marginTrend()): Promise<ComponentFixture<MarginReportPage>> {
    const fixture = TestBed.createComponent(MarginReportPage);
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

  it('todas as colunas, com o custo por categoria já ligado; o switch esconde as categorias', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(marginReport());
    const head = text(el(fixture).querySelector('thead'));
    for (const label of ['Credor / carteira', 'Recuperado', 'Receita', 'Telefonia', 'Custo direto', 'Custo total', 'Margem de contribuição', 'Margem líquida', 'ROI', 'Tendência']) {
      expect(head, label).toContain(label);
    }
    const toggle = el(fixture).querySelector<HTMLInputElement>('#mp-categories')!;
    expect(toggle.checked).toBe(true);
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text(el(fixture).querySelector('thead'))).not.toContain('Telefonia');
    expect(text(el(fixture).querySelector('thead'))).toContain('ROI');
    // sem corte do painel: nem "Outros" nem link para o próprio relatório
    expect(el(fixture).querySelector('tr.mp__others')).toBeNull();
    expect(el(fixture).querySelector('[data-tour="margin-report-link"]')).toBeNull();
  });

  it('categorias desligadas vêm da URL (recarregar, compartilhar e voltar do drill-down mantêm a escolha)', async () => {
    permissions.set(['COST_VIEW']);
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31', categorias: 'nao' }));
    const fixture = await render(marginReport());
    expect(el(fixture).querySelector<HTMLInputElement>('#mp-categories')!.checked).toBe(false);
    expect(text(el(fixture).querySelector('thead'))).not.toContain('Telefonia');
  });

  it('mesmos filtros da URL na API; "Ver o painel" volta com o mesmo recorte; exportar só com a permissão', async () => {
    permissions.set(['COST_VIEW', 'REPORT_EXPORT']);
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31', credor: CREDITOR_A, base: 'conciliado', ordem: 'name' }));
    const fixture = await render(((req: { request: { params: { get(n: string): string | null } } }) => {
      expect(req.request.params.get('from')).toBe('2026-10-01');
      expect(req.request.params.get('basis')).toBe('RECONCILED');
      return marginReport({ basis: 'RECONCILED' });
    }) as never);
    expect(el(fixture).querySelector<HTMLInputElement>('#mp-basis-RECONCILED')!.checked).toBe(true);
    const back = decodeURIComponent(el(fixture).querySelector('[data-tour="margin-report-panel"]')?.getAttribute('href') ?? '');
    expect(back.startsWith('/paineis/margem?')).toBe(true);
    for (const part of ['de=2026-10-01', 'ate=2026-10-31', `credor=${CREDITOR_A}`, 'base=conciliado', 'ordem=name']) {
      expect(back, part).toContain(part);
    }
    expect(el(fixture).querySelector('[data-tour="margin-export"]')).not.toBeNull();
    expect(el(fixture).querySelector('[data-tour="margin-refresh"]')).toBeNull();
  });

  it('ordenar por coluna grava a ordem na URL', async () => {
    permissions.set(['COST_VIEW']);
    const fixture = await render(marginReport());
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const roi = [...el(fixture).querySelectorAll<HTMLButtonElement>('button.mp__sort')].find((b) => b.textContent?.includes('ROI'))!;
    roi.click();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { ordem: '-roi' }, replaceUrl: true }));
  });

  async function type(fixture: ComponentFixture<MarginReportPage>, value: string): Promise<void> {
    const box = el(fixture).querySelector<HTMLInputElement>('#mp-search')!;
    box.value = value;
    box.dispatchEvent(new Event('input'));
    await tick(300); // espera da busca (250 ms)
    fixture.detectChanges();
  }

  it('busca por carteira mostra o credor que a contém, já aberto; sem acento nem maiúscula; total do rodapé é o do período', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(marginReport());
    expect(el(fixture).querySelector('label[for="mp-search"]')?.textContent).toContain('Buscar credor ou carteira');
    expect(el(fixture).querySelector('#mp-search')?.getAttribute('placeholder')).toBe('Nome do credor ou da carteira');
    const totalBefore = text(el(fixture).querySelector('tr.mp__total'));

    await type(fixture, 'CARTAO');
    const creditors = [...el(fixture).querySelectorAll('tbody tr.mp__creditor')].map((r) => text(r));
    expect(creditors.length).toBe(1);
    expect(creditors[0]).toContain('Banco Alfa');
    // aberto pela busca, com a carteira encontrada destacada
    expect(el(fixture).querySelector('button.mp__expand')?.getAttribute('aria-expanded')).toBe('true');
    expect(text(el(fixture).querySelector('tr.mp__portfolio mark'))).toBe('Cartão 2026');
    expect(text(el(fixture).querySelector('.mp__search-count'))).toContain('1 credor encontrado');
    expect(el(fixture).querySelector('.mp__search-count')?.getAttribute('aria-live')).toBe('polite');
    // os valores do total continuam os do período; o rótulo avisa que é o de todos os credores
    expect(text(el(fixture).querySelector('tr.mp__total'))).toBe(totalBefore.replace(/^Total/, 'Total do período (todos os credores)'));
    expect(el(fixture).querySelector('tr.mp__unallocated')).toBeNull();

    // busca pelo nome do credor: não abre as carteiras
    await type(fixture, 'loja');
    expect([...el(fixture).querySelectorAll('tbody tr.mp__creditor')].map((r) => text(r))[0]).toContain('Loja Beta Ltda.');
    expect(el(fixture).querySelectorAll('tbody tr.mp__creditor').length).toBe(1);
  });

  it('busca sem resultado orienta e "Limpar busca" volta à lista inteira', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const fixture = await render(marginReport());
    await type(fixture, 'xyz');
    expect(el(fixture).querySelector('tbody tr.mp__creditor')).toBeNull();
    expect(text(el(fixture).querySelector('tbody'))).toContain('Nenhum credor ou carteira com "xyz". Confira a grafia ou limpe a busca.');
    [...el(fixture).querySelectorAll<HTMLButtonElement>('tbody button')].find((b) => b.textContent?.includes('Limpar busca'))!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('tbody tr.mp__creditor').length).toBe(2);
    expect(el(fixture).querySelector('.mp__search-count')).toBeNull();
    expect(document.activeElement?.id).toBe('mp-search');
  });

  it('termo da busca vem da URL (busca=) ao abrir', async () => {
    permissions.set(['COST_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    query.next(convertToParamMap({ de: '2026-10-01', ate: '2026-10-31', busca: 'alfa' }));
    const fixture = await render(marginReport());
    expect((el(fixture).querySelector('#mp-search') as HTMLInputElement).value).toBe('alfa');
    expect(el(fixture).querySelectorAll('tbody tr.mp__creditor').length).toBe(1);
  });

  it('sem permissão para ver os nomes, sem busca', async () => {
    permissions.set(['COST_VIEW']);
    const fixture = await render(marginReport());
    expect(el(fixture).querySelector('#mp-search')).toBeNull();
  });

  it('muitos credores: 25 primeiros (pior margem primeiro), "Mostrar mais 25" e a contagem; o foco vai ao primeiro credor novo', async () => {
    permissions.set(['COST_VIEW']);
    const report = marginReport();
    report.creditors = Array.from({ length: 30 }, (_, i) => ({
      ...marginLine({ netMargin: i * 10 }),
      creditorId: `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`,
      name: `Credor ${String(i + 1).padStart(2, '0')}`,
      portfolios: [],
    }));
    const fixture = await render(report, marginTrend({ creditors: [] }));
    const rows = () => [...el(fixture).querySelectorAll('tbody tr.mp__creditor')];
    expect(rows().length).toBe(25);
    expect(text(rows()[0])).toContain('Credor 01');
    const more = el(fixture).querySelector('app-show-more')!;
    expect(text(more)).toContain('Mostrando 25 de 30 credores');
    const button = more.querySelector<HTMLButtonElement>('button')!;
    expect(text(button)).toContain('Mostrar mais 5 credores');
    button.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(rows().length).toBe(30);
    expect(el(fixture).querySelector('app-show-more .show-more')).toBeNull();
    expect(text(document.activeElement)).toContain('Credor 26');
  });
});
