import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, page, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { ProductivityReport, Ratio, RecoveryReport, ReportMeta } from '../data/analytics.models';
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
    query.next(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30', agrupar: 'CREDITOR' }));
    TestBed.configureTestingModule({
      providers: [
        provideLocale(),
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

  it('mudar o agrupamento grava na URL (filtros persistidos)', async () => {
    permissions.set(['REPORT_VIEW', 'PERFORMANCE_VIEW_ALL']);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = await render(RecoveryPanel, { '/api/v1/analytics/recovery': recovery });
    const panel = fixture.componentInstance as unknown as { state: { update(f: unknown): void; filter(): unknown } };
    panel.state.update({ ...(panel.state.filter() as object), groupBy: 'ORIGIN' });
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ agrupar: 'ORIGIN', de: '2026-06-01' }), replaceUrl: true }));
    expect([...el(fixture).querySelectorAll('button')].some((b) => b.textContent?.includes('Exportar'))).toBe(false);
  });

  it('operador vê "Meu desempenho", sem filtro de pessoas, e o aviso de escopo', async () => {
    permissions.set(['PERFORMANCE_VIEW_OWN']);
    query.next(convertToParamMap({ de: '2026-06-01', ate: '2026-06-30' }));
    const fixture = await render(ProductivityPanel, { '/api/v1/analytics/productivity': productivity(['u-1'], true) });
    const all = text(el(fixture));
    expect(all).toContain('Meu desempenho');
    expect(all).toContain('somente você');
    expect(all).toContain('3 min 20 s');
    expect(all).not.toContain('Agrupar por');
    expect(el(fixture).querySelector('app-bar-chart')).toBeNull();
    // painéis do menu interno filtrados por permissão
    const tabs = [...el(fixture).querySelectorAll('nav[aria-label="Painéis"] .nav-link')].map((a) => a.textContent!.trim());
    expect(tabs).toEqual(['Produtividade']);
  });

  it('supervisor vê a equipe com gráfico e agrupamento', async () => {
    permissions.set(['REPORT_VIEW', 'PERFORMANCE_VIEW_TEAM', 'USER_VIEW', 'DASHBOARD_VIEW']);
    const fixture = await render(ProductivityPanel, { '/api/v1/analytics/productivity': productivity(['u-1', 'u-2'], true) });
    const all = text(el(fixture));
    expect(all).toContain('Produtividade da equipe');
    expect(all).toContain('você e as equipes que supervisiona');
    expect(el(fixture).querySelectorAll('app-bar-chart .bc__bar-line')).toHaveLength(2);
  });
});
