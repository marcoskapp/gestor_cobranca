import { provideHttpClient } from '@angular/common/http';
import { resetSharedPanelFilter } from '../data/url-filter';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, page, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { DisputeReport, ProductivityReport, Ratio, RecoveryReport, ReportMeta } from '../data/analytics.models';
import { todayIso } from '../data/drill-down';
import { DisputesPanel } from './disputes-panel';
import { ProductivityPanel } from './productivity-panel';
import { RecoveryPanel } from './recovery-panel';

const ratio = (value: number | null, numerator: number, denominator: number, reason: string | null = null): Ratio => ({ value, numerator, denominator, unavailableReason: reason });
const meta = (o: Partial<ReportMeta> = {}): ReportMeta => ({
  from: '2026-06-01',
  to: '2026-06-30',
  groupBy: 'CREDITOR',
  dataUntil: '2026-10-24T13:00:00Z',
  notApplicableFilters: [],
  peopleRestricted: false,
  unavailableMetrics: ['cost.channelCosts'],
  ...o,
});

const recovery: RecoveryReport = {
  meta: meta(),
  totals: { recoveredGross: 1300, reversed: 200, recoveredNet: 1100, receipts: 4, recoveryRate: ratio(0.275, 1100, 4000), reversalShare: ratio(0.1538, 200, 1300) },
  rows: [
    { key: 'c-1', label: '=Banco Alfa', recoveredGross: 1100, reversed: 0, recoveredNet: 1100, receipts: 3, recoveryRate: ratio(0.44, 1100, 2500) },
    { key: 'c-2', label: 'Loja Beta', recoveredGross: 200, reversed: 200, recoveredNet: 0, receipts: 1, recoveryRate: ratio(0, 0, 1500) },
  ],
  timeToReceipt: { agreements: 2, averageDays: 4, medianDays: 4, p90Days: 4.8 },
  cost: { commissionPerReal: ratio(null, 0, 0, 'privacy_minimum'), monthFrom: '2026-06-01', monthTo: '2026-06-01', statements: 2 },
};

function productivity(rows: string[], restricted: boolean): ProductivityReport {
  const row = (key: string, label: string) => ({
    key,
    label,
    workedCases: 2,
    attempts: 6,
    effectiveContacts: 2,
    contactRate: ratio(0.3333, 2, 6),
    answeredCalls: 3,
    talkSeconds: 600,
    averageHandlingSeconds: ratio(200, 600, 3),
    loggedHours: 3,
    agreements: 1,
    agreementsPerLoggedHour: ratio(0.3333, 1, 3),
    recoveredNet: 800,
  });
  return { meta: meta({ groupBy: 'OPERATOR', peopleRestricted: restricted }), total: row('ALL', 'Total'), rows: rows.map((r) => row(r, `Pessoa ${r}`)) };
}

describe('painéis de analytics', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const query = new BehaviorSubject(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30', agrupar: 'CREDITOR' }));

  beforeEach(() => {
    resetSharedPanelFilter();
    query.next(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30', agrupar: 'CREDITOR' }));
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
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

  async function render<T>(component: Type<T>, handlers: Record<string, unknown>): Promise<ComponentFixture<T>> {
    const fixture = TestBed.createComponent(component);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, { ...lookups, ...handlers });
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\u00a0/g, ' ');
  const lookups = { '/api/v1/creditors': page([]), '/api/v1/portfolios': page([]), '/api/v1/users': page([]), '/api/v1/teams': [] };

  it('recuperação: filtros da URL viram parâmetros da API e cada taxa mostra a base', async () => {
    permissions.set(['REPORT_VIEW', 'REPORT_EXPORT', 'CREDITOR_VIEW', 'PERFORMANCE_VIEW_ALL']);
    const fixture = await render(RecoveryPanel, {
      '/api/v1/analytics/recovery': (req: { request: { params: { get(n: string): string | null } } }) => {
        expect(req.request.params.get('from')).toBe('2026-06-01');
        expect(req.request.params.get('groupBy')).toBe('CREDITOR');
        return recovery;
      },
    });
    const kpis = text(el(fixture).querySelector('[data-tour="analytics-kpis"]'));
    expect(kpis).toContain('27,5%');
    expect(kpis).toContain('R$ 1.100,00 de R$ 4.000,00');
    expect(kpis).toContain('15,4% do recebido bruto');
    expect(kpis).toContain('menos de 3 extratos');
    const all = text(el(fixture));
    expect(all).toContain('Dados até');
    expect(all).toContain('=Banco Alfa');
    expect(all).toContain('R$ 1.100,00 de R$ 2.500,00');
    expect(all).toContain('custos de canal');
    // exportação para quem tem REPORT_EXPORT
    expect([...el(fixture).querySelectorAll('button')].some((b) => b.textContent?.includes('Exportar'))).toBe(true);
  });

  it('recuperação: drill-down leva ao razão com o período e o credor da linha; sem permissão do razão, só texto', async () => {
    permissions.set(['REPORT_VIEW', 'SETTLEMENT_VIEW', 'PERFORMANCE_VIEW_ALL']);
    const fixture = await render(RecoveryPanel, { '/api/v1/analytics/recovery': recovery });
    const hrefs = [...el(fixture).querySelectorAll('a.app-drill-link')].map((a) => decodeURIComponent(a.getAttribute('href') ?? ''));
    expect(hrefs).toContain('/prestacao-de-contas/razao?pago-de=2026-06-01&pago-ate=2026-06-30');
    expect(hrefs).toContain('/prestacao-de-contas/razao?pago-de=2026-06-01&pago-ate=2026-06-30&tipo=REVERSAL');
    expect(hrefs).toContain('/prestacao-de-contas/razao?credor=c-1&pago-de=2026-06-01&pago-ate=2026-06-30');
    expect(hrefs).toContain('/prestacao-de-contas/razao?credor=c-2&pago-de=2026-06-01&pago-ate=2026-06-30&tipo=PAYMENT');
    fixture.destroy();

    permissions.set(['REPORT_VIEW', 'PERFORMANCE_VIEW_ALL']);
    const restricted = await render(RecoveryPanel, { '/api/v1/analytics/recovery': recovery });
    expect(el(restricted).querySelectorAll('a.app-drill-link')).toHaveLength(0);
    expect(text(el(restricted))).toContain('=Banco Alfa');
  });

  it('recuperação: com operador no filtro o razão não representa a base (sem link)', async () => {
    permissions.set(['REPORT_VIEW', 'SETTLEMENT_VIEW', 'PERFORMANCE_VIEW_ALL']);
    query.next(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30', agrupar: 'CREDITOR', operador: '11111111-1111-1111-1111-111111111111' }));
    const fixture = await render(RecoveryPanel, { '/api/v1/analytics/recovery': recovery });
    expect(el(fixture).querySelectorAll('a.app-drill-link')).toHaveLength(0);
  });

  it('mudar o agrupamento grava na URL (filtros persistidos)', async () => {
    permissions.set(['REPORT_VIEW', 'PERFORMANCE_VIEW_ALL']);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = await render(RecoveryPanel, { '/api/v1/analytics/recovery': recovery });
    const panel = fixture.componentInstance as unknown as { state: { update(f: unknown): void; filter(): unknown } };
    panel.state.update({ ...(panel.state.filter() as object), groupBy: 'ORIGIN' });
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ agrupar: 'ORIGIN', de: '2026-06-01' }), replaceUrl: true }));
    expect([...el(fixture).querySelectorAll('button')].some((b) => b.textContent?.includes('Exportar'))).toBe(false);
  });

  it('operador vê "Minha produtividade", sem filtro de pessoas, e o aviso de escopo', async () => {
    permissions.set(['PERFORMANCE_VIEW_OWN']);
    query.next(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30' }));
    const fixture = await render(ProductivityPanel, { '/api/v1/analytics/productivity': productivity(['u-1'], true) });
    const all = text(el(fixture));
    expect(all).toContain('Minha produtividade');
    expect(all).toContain('somente você');
    expect(all).toContain('3 min 20 s');
    expect(all).not.toContain('Agrupar por');
    expect(el(fixture).querySelector('app-bar-chart')).toBeNull();
    // sem abas repetindo o menu (T10): a troca de painel é pelo menu lateral
    expect(el(fixture).querySelector('nav[aria-label="Painéis"]')).toBeNull();
  });

  it('supervisor vê a equipe com gráfico e agrupamento', async () => {
    permissions.set(['REPORT_VIEW', 'PERFORMANCE_VIEW_TEAM', 'USER_VIEW', 'DASHBOARD_VIEW']);
    const fixture = await render(ProductivityPanel, { '/api/v1/analytics/productivity': productivity(['u-1', 'u-2'], true) });
    const all = text(el(fixture));
    expect(all).toContain('Produtividade da equipe');
    expect(all).toContain('você e as equipes que supervisiona');
    expect(el(fixture).querySelectorAll('app-bar-chart .bc__bar-line')).toHaveLength(2);
  });

  it('contestações: prazo, procedência e retorno do credor com a base; fila por responsável com atalho para a supervisão', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const row = { opened: 4, resolved: 2, upheld: 1, upheldRate: ratio(0.5, 1, 2), withinDeadline: ratio(1, 2, 2), averageResolutionDays: 3.5, openNow: 2, overdueNow: 1, creditorRequestsAnswered: 1, averageCreditorResponseHours: 30 };
    const report: DisputeReport = {
      meta: meta({ groupBy: 'NONE' }),
      referenceDate: '2026-06-30',
      totals: {
        opened: 4,
        resolved: 2,
        withdrawn: 0,
        openNow: 2,
        overdueNow: 1,
        averageResolutionDays: 3.5,
        withinDeadline: ratio(1, 2, 2),
        upheldRate: ratio(0.5, 1, 2),
        creditorRequestsSent: 3,
        creditorRequestsAnswered: 1,
        averageCreditorResponseHours: 30,
        creditorAwaitingNow: 2,
        creditorOverdueNow: 1,
      },
      byType: [{ key: 'FRAUD', label: 'FRAUD', ...row }],
      byOrigin: [{ key: 'DEBTOR', label: 'DEBTOR', ...row }],
      byCreditor: [{ key: 'c-1', label: 'Banco Alfa', ...row }],
      backlog: [
        { key: 'u-1', label: 'Ana Analista', open: 1, overdue: 0, internalOverdue: 1, awaitingCreditor: 1 },
        { key: '-', label: 'Sem responsável', open: 1, overdue: 1, internalOverdue: 1, awaitingCreditor: 0 },
      ],
    };
    const fixture = await render(DisputesPanel, { '/api/v1/analytics/disputes': report });
    const kpis = text(el(fixture).querySelector('[data-tour="analytics-kpis"]'));
    expect(kpis).toContain('1 com prazo de resposta vencido');
    expect(kpis).toContain('100,0%');
    expect(kpis).toContain('1 de 2');
    expect(kpis).toContain('1 de 3 pedidos respondidos');
    expect(kpis).toContain('30,0 h');
    const all = text(el(fixture));
    expect(all).toContain('Banco Alfa');
    expect(all).toContain('Fraude');
    const backlog = el(fixture).querySelector('[data-tour="analytics-disputes-backlog"]')!;
    expect(text(backlog)).toContain('Ana Analista');
    expect(backlog.querySelector('a')?.getAttribute('href')).toBe('/cobranca/contestacoes?responsavel=supervisao');
    // "em aberto agora" só vira link quando o período chega a hoje (a fila é a situação atual)
    expect(el(fixture).querySelectorAll('a.app-drill-link')).toHaveLength(0);
  });

  it('contestações: período até hoje → "em aberto agora" e a linha do credor levam à fila', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const iso = todayIso();
    query.next(convertToParamMap({ de: '2026-06-01', ate: iso }));
    const row = { opened: 1, resolved: 0, upheld: 0, upheldRate: ratio(null, 0, 0, 'no_base'), withinDeadline: ratio(null, 0, 0, 'no_base'), averageResolutionDays: null, openNow: 1, overdueNow: 0, creditorRequestsAnswered: 0, averageCreditorResponseHours: null };
    const totals = { opened: 1, resolved: 0, withdrawn: 0, openNow: 1, overdueNow: 0, averageResolutionDays: null, withinDeadline: ratio(null, 0, 0), upheldRate: ratio(null, 0, 0), creditorRequestsSent: 0, creditorRequestsAnswered: 0, averageCreditorResponseHours: null, creditorAwaitingNow: 0, creditorOverdueNow: 0 };
    const report = { meta: meta({ groupBy: 'NONE' }), referenceDate: iso, totals, byType: [], byOrigin: [], byCreditor: [{ key: 'c-1', label: 'Banco Alfa', ...row }], backlog: [] } as unknown as DisputeReport;
    const fixture = await render(DisputesPanel, { '/api/v1/analytics/disputes': report });
    const hrefs = [...el(fixture).querySelectorAll('a.app-drill-link')].map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(['/cobranca/contestacoes', '/cobranca/contestacoes?credor=c-1']);
  });

  it('painel aberto pelo menu (sem filtro na URL) herda o período do último painel', async () => {
    query.next(convertToParamMap({ de: '2026-05-01', ate: '2026-05-31' }));
    permissions.set(['REPORT_VIEW']);
    TestBed.createComponent(RecoveryPanel);
    query.next(convertToParamMap({}));
    const fixture = TestBed.createComponent(RecoveryPanel);
    expect((fixture.componentInstance as unknown as { state: { filter(): { from: string; to: string } } }).state.filter()).toMatchObject({ from: '2026-05-01', to: '2026-05-31' });
  });
});
