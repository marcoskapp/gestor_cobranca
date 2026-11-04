import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { PlatformChargesPage } from './platform-charges-page';

const BASE = '/api/v1/public-api/platform-billing';

describe('PlatformChargesPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasPermission: () => false, hasAnyPermission: () => false } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('mostra a cobrança da plataforma somente leitura, sem política cadastrada', async () => {
    const fixture = TestBed.createComponent(PlatformChargesPage);
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        fixture.detectChanges();
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    };
    fixture.detectChanges();
    await settle();
    http.expectOne(`${BASE}/position`).flush({
      tenantId: 't-1', hasPolicy: false, periodStart: '2026-11-01', periodEnd: '2026-11-30', closingDay: 31, cycleDays: 30,
      referenceCycleDays: 30, cycleKind: 'NO_POLICY', today: '2026-11-04', daysElapsed: 4, daysRemaining: 26,
      policyVersion: null, policyRegisteredAt: null, requests: 10, billableRequests: 8, billableMbIn: 1, billableMbOut: 1,
      records: 0, creditors: 1, tokens: 1, amountSoFar: 0, currency: 'BRL', lines: [], allowances: [], breakdown: [],
      daily: [], projection: { method: 'DAILY_AVERAGE', billableRequests: 60, billableMbIn: 8, billableMbOut: 8, records: 0, amount: 0 },
      lastStatement: null, updatedAt: null, flushIntervalSeconds: 60, calculationMillis: 3,
    });
    http.expectOne(`${BASE}/policy`).flush({ tenantId: 't-1', policyId: null, startDate: null, endDate: null, versions: [], hasStatements: false });
    http.expectOne((r) => r.url === `${BASE}/statements`).flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await settle();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Cobrança da plataforma');
    expect(text).toContain('Sem política da plataforma');
    expect(text).toContain('sem política da plataforma: o consumo aparece só como informação');
    expect(text).not.toContain('Fechar ciclo');
  });
});
