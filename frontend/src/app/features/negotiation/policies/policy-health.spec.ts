import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { PolicyHealth, rangeProblem } from './policy-health';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));
const segment = { portfolioId: 'p-1', delayBucket: 'D361_PLUS', agreements: 30, matureAgreements: 20, brokenEarly: 8, earlyBreakRate: 0.4, agreedAmount: 30000, paidAmount: 12000, paidOverAgreed: 0.4, averageEnvelopeUsage: 92.5, highEnvelopeShare: 0.7, portalSimulations: 0, portalWithoutOffer: 0, portalWithoutOfferShare: null, cannotAfford: 0 };
const HEALTH = { creditorId: 'c-1', from: '2026-05-15', to: '2026-11-11', valueBand: null, asOf: '2026-11-11T12:00:00Z', total: segment, segments: [segment], suggestions: [{ kind: 'HIGH_EARLY_BREAK', portfolioId: 'p-1', delayBucket: 'D361_PLUS' }], minAgreements: 20, minMature: 10, minPortal: 20 };

describe('PolicyHealth (saúde das políticas)', () => {
  let http: HttpTestingController;
  let permissions: string[];
  let requested: string[];

  function setup(): void {
    requested = [];
    TestBed.configureTestingModule({
      imports: [PolicyHealth],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { user: signal(fakeUser()), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions.includes(p)) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  function reply(req: TestRequest): void {
    requested.push(req.request.url);
    if (req.request.url.endsWith('/analytics/policy-health')) return req.flush(HEALTH);
    if (req.request.url.includes('/negotiation/policies')) return req.flush({ content: [{ id: 'pol-1' }], page: 0, size: 1, totalElements: 1, totalPages: 1 });
    if (req.request.url.includes('names') || req.request.url.includes('portfolios')) return req.flush([]);
    return req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  }

  async function render(): Promise<ComponentFixture<PolicyHealth>> {
    const f = TestBed.createComponent(PolicyHealth);
    f.componentInstance['filters'].patchValue({ creditor: ['c-1'] });
    for (let i = 0; i < 4; i++) {
      f.detectChanges();
      await tick(300);
      http.match(() => true).forEach(reply);
    }
    f.detectChanges();
    return f;
  }

  it('sugestão com a evidência e os caminhos para a política e os acordos, para quem os abre', async () => {
    permissions = ['POLICY_MANAGE', 'POLICY_AREA_VIEW', 'POLICY_VIEW', 'AGREEMENT_VIEW'];
    setup();
    const el = (await render()).nativeElement as HTMLElement;
    const card = el.querySelector('[data-testid="policy-suggestion"]')!;
    expect(card.textContent).toContain('Quebra alta nos primeiros 60 dias');
    expect(card.querySelector('a[href="/negociacao/politicas/pol-1"]')).not.toBeNull();
    expect(card.textContent).toContain('Ver acordos do credor');
    // a linha com sugestão é marcada além da cor (leitor de tela)
    expect(el.querySelector('tbody .visually-hidden')!.textContent).toContain('Com sugestão de revisão');
  });

  it('só com REPORT_VIEW: sem links nem consultas a políticas e acordos (telas sem permissão ficam ocultas)', async () => {
    permissions = ['REPORT_VIEW'];
    setup();
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('Políticas de cobrança');
    expect(el.textContent).not.toContain('Abrir a política');
    expect(el.textContent).not.toContain('Ver acordos');
    expect(requested.some((u) => u.includes('/negotiation/policies'))).toBe(false);
  });

  it('período inválido não vai ao servidor', () => {
    expect(rangeProblem({ from: '2026-06-01', to: '2026-05-01' })).toContain('anterior');
    expect(rangeProblem({ from: '2025-01-01', to: '2026-11-01' })).toContain('400 dias');
    expect(rangeProblem({ from: '2026-01-01', to: '2026-06-01' })).toBeNull();
  });
});
