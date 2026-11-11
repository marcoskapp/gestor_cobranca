import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, UrlTree, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { buildBreadcrumbs } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { activeNavRoute, menuRouteOf } from '../../../core/layout/navigation';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { COLLECTION_ROUTES, legacyCaseRedirect } from '../collection.routes';
import { CaseSheet } from '../data/collection.models';
import { CaseConsult } from './case-consult';

function sheet(): CaseSheet {
  return {
    collectionCase: {
      id: 'k-1', debtorId: 'd-1', creditorId: 'c-1', portfolioId: 'p-1', status: 'IN_NEGOTIATION', resumeStatus: null, statusReason: null,
      openedAt: '2026-09-01T12:00:00Z', closedAt: null, closeReason: null, openBalance: 1250.4, eligibleBalance: 1250.4, openTitles: 1,
      suspendedTitles: 0, disputedTitles: 0, oldestDueDate: '2026-08-10', daysOverdue: 74, eligible: true, eligibilityReasons: [],
      contactableChannels: ['PHONE'], eligibilityEvaluatedAt: null, queueId: 'q-1', ownerType: 'USER', assigneeUserId: 'u-2',
      assigneeTeamId: null, assignedAt: null, nextActionAt: null, lastInteractionAt: null, lastEffectiveContactAt: null,
      interactionCount: 1, effectiveContactCount: 1, score: null, version: 3,
    },
    debtor: { id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**', personType: 'INDIVIDUAL', status: 'ACTIVE', contacts: [] },
    titles: [{ titleId: 't-1', externalId: 'CARTAO-4471', installmentNumber: null, dueDate: '2026-08-10', principalAmount: 1000, openBalance: 1250.4, status: 'OPEN', disputeBlocked: false, daysOverdue: 74 }],
    activeRestrictions: [],
    openDisputes: [],
    pendingPromise: null,
    openTasks: [],
    recentInteractions: [
      { id: 'i-1', caseId: 'k-1', channel: 'PHONE', direction: 'OUTBOUND', outcomeCode: 'CPC', effectiveContact: true, note: null, startedAt: '2026-11-07T17:00:00Z', endedAt: null, durationSeconds: 120, operatorId: 'u-2', actorType: 'USER' },
    ] as CaseSheet['recentInteractions'],
    otherCases: [],
  };
}

describe('CaseConsult (ficha do caso para quem não opera)', () => {
  let http: HttpTestingController;
  let permissions: string[];
  const dialog = { open: vi.fn() };

  beforeEach(() => {
    permissions = ['CASE_VIEW', 'DEBTOR_VIEW'];
    evaluable = ['u-2'];
    dialog.open.mockReset().mockReturnValue({ afterClosed: () => of(undefined) });
    TestBed.configureTestingModule({
      imports: [CaseConsult],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DialogService, useValue: dialog },
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ id: 'u-1' })),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  let evaluable: string[];

  function reply(req: TestRequest): void {
    const url = req.request.url;
    if (url.endsWith('/sheet')) return req.flush(sheet());
    if (url === '/api/v1/collection/tabulations') return req.flush([]);
    if (url.startsWith('/api/v1/creditors/')) return req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
    if (url.startsWith('/api/v1/portfolios/')) return req.flush({ id: 'p-1', name: 'Cartões setembro' });
    if (url === '/api/v1/users/display-names') return req.flush([{ id: 'u-2', name: 'Carla Operadora' }]);
    // escopo de quem avalia: a operadora u-2 é da equipe; "Avaliar" só aparece para quem está nele
    if (url === '/api/v1/incentive/quality/evaluable-users') return req.flush({ all: false, userIds: evaluable });
    return req.flush(url.includes('/negotiation/') || url.includes('/communication/') ? { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } : {});
  }

  async function render(): Promise<ComponentFixture<CaseConsult>> {
    const fixture = TestBed.createComponent(CaseConsult);
    fixture.componentRef.setInput('id', 'k-1');
    for (let i = 0; i < 5; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      http.match(() => true).forEach(reply);
    }
    fixture.detectChanges();
    return fixture;
  }

  const text = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';

  it('dados e abas do caso, sem fila "Meus casos", cronômetro nem registro de interação', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(fixture)).toContain('Caso · Ana Paula Ribeiro');
    expect(text(fixture)).toContain('CARTAO-4471');
    expect(text(fixture)).not.toContain('Meus casos');
    expect(text(fixture)).not.toContain('Estação de cobrança');
    expect(el.querySelector('[role="timer"]')).toBeNull();
    expect(el.querySelector('app-interaction-panel')).toBeNull();
    expect(el.querySelector('app-contact-window')).toBeNull();
    // ações do perfil: só o que pode fazer (CASE_VIEW + DEBTOR_VIEW: ficha do devedor no cabeçalho da ficha; nada no "Mais ações")
    expect([...el.querySelectorAll('button')].some((b) => b.textContent?.includes('Ficha do devedor'))).toBe(true);
    expect(el.querySelector('app-more-actions button')).toBeNull();
    // sem permissão de avaliar, o histórico não oferece "Avaliar"
    expect(el.querySelector('[data-testid="interaction-evaluate"]')).toBeNull();
  });

  it('com permissão de avaliar, o histórico oferece "Avaliar" para o atendimento de outra pessoa', async () => {
    permissions = [...permissions, 'QUALITY_EVALUATE', 'CASE_MANAGE'];
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    const historyTab = [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Histórico'))!;
    historyTab.click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="interaction-evaluate"]')).not.toBeNull();
    // CASE_MANAGE: ações de situação no "Mais ações"
    expect(el.querySelector('app-more-actions button')).not.toBeNull();
  });

  it('atendimento de quem está fora do escopo de quem avalia (ex.: o gestor, para o supervisor) fica sem "Avaliar"', async () => {
    permissions = [...permissions, 'QUALITY_EVALUATE'];
    evaluable = ['u-9'];
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Histórico'))!.click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="interaction-evaluate"]')).toBeNull();
  });

  it('endereço único: o antigo /cobranca/fila?caso= redireciona para /cobranca/casos/:id, com a aba', () => {
    const route = (params: Record<string, string>) => ({ queryParams: params, queryParamMap: { get: (k: string) => params[k] ?? null } }) as unknown as ActivatedRouteSnapshot;
    const router = TestBed.inject(Router);
    const redirect = TestBed.runInInjectionContext(() => legacyCaseRedirect(route({ caso: 'k-1', aba: 'historico', origem: 'x' }), {} as never)) as UrlTree;
    expect(router.serializeUrl(redirect)).toBe('/cobranca/casos/k-1?aba=historico&origem=x');
    expect(COLLECTION_ROUTES.find((r) => r.path === 'fila')?.canActivate?.[0]).toBe(legacyCaseRedirect);
    expect(TestBed.runInInjectionContext(() => legacyCaseRedirect(route({}), {} as never))).toBe(true);
  });

  it('quem opera: o mesmo endereço marca a estação no menu e na trilha; quem consulta, Casos', () => {
    const variants = COLLECTION_ROUTES.filter((r) => r.path === 'casos/:id');
    expect(variants).toHaveLength(2);
    expect(variants[0].canMatch).toHaveLength(1);
    expect(variants[0].data?.['menuRoute']).toBe('/cobranca/fila');
    expect(variants[1].canMatch).toBeUndefined();

    const snapshot = { routeConfig: { data: {} }, firstChild: { routeConfig: variants[0], firstChild: null } } as unknown as ActivatedRouteSnapshot;
    const menu = menuRouteOf(snapshot);
    expect(menu).toBe('/cobranca/fila');
    expect(activeNavRoute(menu ?? '', ['/cobranca/fila', '/cobranca/casos'])).toBe('/cobranca/fila');
    expect(buildBreadcrumbs('/cobranca/casos/k-1', 'Caso de cobrança', menu).map((c) => c.label)).toEqual(['Início', 'Cobrança', 'Estação de cobrança', 'Caso de cobrança']);
    expect(buildBreadcrumbs('/cobranca/casos/k-1', 'Caso de cobrança').map((c) => c.label)).toEqual(['Início', 'Cobrança', 'Casos', 'Caso de cobrança']);
  });
});
