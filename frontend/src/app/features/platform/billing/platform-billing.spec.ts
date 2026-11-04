import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { OverviewView, PlatformPositionView, PlatformStatementView, PolicyView } from '../data/platform-billing.models';
import { PlatformBillingOverview } from './platform-billing-overview';
import { tierProblem, tierRange, tiersSummary } from './platform-billing.labels';
import { buildTerms } from './platform-policy-dialog';
import { PlatformTenantBilling } from './platform-tenant-billing';

const BASE = '/api/v1/platform/billing';

const position = (overrides: Partial<PlatformPositionView> = {}): PlatformPositionView => ({
  tenantId: 't-1',
  hasPolicy: true,
  periodStart: '2026-11-01',
  periodEnd: '2026-11-30',
  closingDay: 30,
  cycleDays: 30,
  referenceCycleDays: 30,
  cycleKind: 'REGULAR',
  today: '2026-11-04',
  daysElapsed: 4,
  daysRemaining: 26,
  policyVersion: 1,
  policyRegisteredAt: '2026-10-01T12:00:00Z',
  requests: 160_000,
  billableRequests: 150_000,
  billableMbIn: 12,
  billableMbOut: 30,
  records: 900,
  creditors: 2,
  tokens: 3,
  amountSoFar: 1800,
  currency: 'BRL',
  lines: [
    { metric: 'MONTHLY_FEE', quantity: 1, included: 0, billable: 1, unitPrice: 500, amount: 500, tiers: [] },
    {
      metric: 'REQUESTS', quantity: 150_000, included: 10_000, billable: 140_000, unitPrice: null, amount: 1300,
      tiers: [
        { from: 1, to: 100_000, units: 90_000, unitPrice: 0.01, amount: 900 },
        { from: 100_001, to: null, units: 50_000, unitPrice: 0.008, amount: 400 },
      ],
    },
  ],
  allowances: [{ metric: 'REQUESTS', used: 150_000, included: 10_000, percent: 1500 }],
  breakdown: [
    { kind: 'CREDITOR', key: 'c-1', label: 'Banco Horizonte', creditorId: null, totals: { requests: 100, billableRequests: 90, billableMbIn: 2, billableMbOut: 0, records: 5 } },
    { kind: 'TOKEN', key: 'k-1', label: 'gck_ab12 · ERP', creditorId: 'c-1', totals: { requests: 100, billableRequests: 90, billableMbIn: 2, billableMbOut: 0, records: 5 } },
  ],
  daily: [],
  projection: { method: 'DAILY_AVERAGE', billableRequests: 1_125_000, billableMbIn: 90, billableMbOut: 225, records: 6750, amount: 9000 },
  lastStatement: { id: 's-1', periodStart: '2026-10-01', periodEnd: '2026-10-31', revision: 1, totalAmount: 1200 },
  updatedAt: '2026-11-04T12:00:00Z',
  flushIntervalSeconds: 60,
  calculationMillis: 12,
  ...overrides,
});

const statement: PlatformStatementView = {
  id: 's-1', tenantId: 't-1', periodStart: '2026-10-01', periodEnd: '2026-10-31', ended: true, revision: 1, closingDay: 31,
  cycleDays: 31, referenceCycleDays: 31, cycleKind: 'REGULAR', policyVersion: 1, requests: 10, billableRequests: 10,
  billableMbIn: 1, billableMbOut: 1, records: 0, creditors: 1, tokens: 1, totalAmount: 1200, currency: 'BRL', lines: [],
  breakdown: [], usageHash: 'a'.repeat(64), closedAt: '2026-11-01T05:40:00Z', closedBy: null, reason: null,
};

const policy: PolicyView = {
  tenantId: 't-1', policyId: 'p-1', startDate: '2026-01-01', endDate: null, hasStatements: true,
  versions: [{
    id: 'v-1', version: 1, closingDay: 31, monthlyFee: 500, includedRequests: 10_000, pricePerRequest: null,
    requestTiers: [{ upTo: 100_000, unitPrice: 0.01 }, { upTo: null, unitPrice: 0.008 }], includedMbIn: 0, pricePerMbIn: 1,
    includedMbOut: 0, pricePerMbOut: 0.5, includedRecords: 0, pricePerRecord: 0.02, registeredAt: '2026-01-01T00:00:00Z',
    registeredBy: 'u-1', reason: null, appliesToCurrentCycle: true,
  }],
};

describe('faturamento da plataforma: regras de faixas e condições', () => {
  it('valida faixas crescentes com a última sem limite', () => {
    expect(tierProblem([{ upTo: 10, unitPrice: 1 }, { upTo: null, unitPrice: 1 }])).toBeNull();
    expect(tierProblem([{ upTo: 10, unitPrice: 1 }])).toContain('última faixa');
    expect(tierProblem([{ upTo: 10, unitPrice: 1 }, { upTo: 5, unitPrice: 1 }, { upTo: null, unitPrice: 1 }])).toContain('Faixa 2');
    expect(tierRange({ from: 100_001, to: null })).toBe('100.001 em diante');
    expect(tiersSummary([{ upTo: 100_000, unitPrice: 0.01 }, { upTo: null, unitPrice: 0.008 }])).toContain('acima de 100.000');
  });

  it('monta as condições: preço único sem faixas e, com faixas, sem preço único e a última sem limite', () => {
    const base = {
      closingDay: 10, monthlyFee: 500, includedRequests: 10_000, pricePerRequest: 0.01, includedMbIn: 0, pricePerMbIn: 1,
      includedMbOut: 0, pricePerMbOut: 0.5, includedRecords: 0, pricePerRecord: 0.02,
      tiers: [{ upTo: 100_000, unitPrice: 0.01 }, { upTo: 999, unitPrice: 0.008 }],
    };
    expect(buildTerms({ ...base, tiered: false })).toMatchObject({ pricePerRequest: 0.01, requestTiers: [] });
    expect(buildTerms({ ...base, tiered: true })).toMatchObject({
      pricePerRequest: null,
      requestTiers: [{ upTo: 100_000, unitPrice: 0.01 }, { upTo: null, unitPrice: 0.008 }],
    });
  });
});

describe('telas do faturamento da plataforma', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const confirmations: ConfirmDialogData[] = [];

  beforeEach(() => {
    confirmations.length = 0;
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasPermission: (p: string) => permissions().includes(p), hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)) } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() } },
        { provide: ConfirmService, useValue: { confirm: (data: ConfirmDialogData) => { confirmations.push(data); return of('conferência'); } } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 't-1' }), queryParamMap: convertToParamMap({ nome: 'Horizonte' }) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function settle<T>(fixture: ComponentFixture<T>): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  const page = (content: PlatformStatementView[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

  /** Responde às três cargas da tela da assessoria (posição, política e extratos). */
  async function renderTenant(): Promise<ComponentFixture<PlatformTenantBilling>> {
    const fixture = TestBed.createComponent(PlatformTenantBilling);
    fixture.detectChanges();
    await settle(fixture);
    http.expectOne(`${BASE}/tenants/t-1/position`).flush(position());
    http.expectOne(`${BASE}/tenants/t-1/policy`).flush(policy);
    http.expectOne((r) => r.url === `${BASE}/tenants/t-1/statements`).flush(page([statement]));
    await settle(fixture);
    return fixture;
  }

  it('consolidado: receita, projeção, assessorias sem política e ranking com link para a assessoria', async () => {
    const overview: OverviewView = {
      today: '2026-11-04',
      totals: { tenants: 2, withPolicy: 1, withoutPolicy: 1, amountSoFar: 1800, projectedAmount: 9000, lastClosedAmount: 1200, billableRequests: 155_000, billableMbIn: 12, billableMbOut: 30, records: 900 },
      tenants: [
        { tenantId: 't-1', slug: 'horizonte', tradeName: 'Horizonte', hasPolicy: true, periodStart: '2026-11-01', periodEnd: '2026-11-30', daysElapsed: 4, cycleDays: 30, policyVersion: 1, billableRequests: 150_000, billableMbIn: 12, billableMbOut: 30, records: 900, tokens: 3, amountSoFar: 1800, projectedAmount: 9000, lastStatement: null, rank: 1 },
        { tenantId: 't-2', slug: 'aurora', tradeName: 'Aurora', hasPolicy: false, periodStart: '2026-11-01', periodEnd: '2026-11-30', daysElapsed: 4, cycleDays: 30, policyVersion: null, billableRequests: 5_000, billableMbIn: 0, billableMbOut: 0, records: 0, tokens: 1, amountSoFar: 0, projectedAmount: 0, lastStatement: null, rank: 2 },
      ],
      calculationMillis: 20,
    };
    const fixture = TestBed.createComponent(PlatformBillingOverview);
    fixture.detectChanges();
    await settle(fixture);
    http.expectOne(`${BASE}/overview`).flush(overview);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent ?? '';
    expect(text).toContain('Receita até agora');
    expect(text).toContain('Assessorias sem política');
    expect(text).toContain('sem política');
    expect(el.querySelectorAll('app-kpi-card').length).toBe(5);
    const rows = el.querySelectorAll('app-data-table tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Horizonte');
    expect(rows[1].textContent).toContain('Cadastrar política');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toContain('/plataforma/faturamento/t-1');
  });

  it('assessoria: posição com faixas, política e extratos; só quem gerencia vê os botões de alteração', async () => {
    permissions.set(['PLATFORM_BILLING_VIEW']);
    const fixture = await renderTenant();
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent ?? '';
    expect(text).toContain('Faturamento da plataforma — Horizonte');
    expect(text).toContain('Faixa 100.001 em diante');
    expect(text).toContain('Banco Horizonte');
    expect(text).toContain('ciclo atual');
    expect(text).not.toContain('Nova versão');
    expect(text).not.toContain('Nova revisão');
  });

  it('nova revisão pede motivo e envia o início do ciclo', async () => {
    permissions.set(['PLATFORM_BILLING_VIEW', 'PLATFORM_BILLING_MANAGE']);
    const fixture = await renderTenant();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Nova versão');
    const revise = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Nova revisão'))!;
    revise.click();
    expect(confirmations[0].reason).toBe('required');
    const req = http.expectOne(`${BASE}/tenants/t-1/statements`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ periodStart: '2026-10-01', reason: 'conferência' });
    req.flush({ ...statement, revision: 2, reason: 'conferência' });
    await settle(fixture);
    http.expectOne(`${BASE}/tenants/t-1/policy`).flush(policy);
    http.expectOne(`${BASE}/tenants/t-1/position`).flush(position());
    await settle(fixture);
    http.match((r) => r.url === `${BASE}/tenants/t-1/statements`).forEach((r) => r.flush(page([])));
  });
});
