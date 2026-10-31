import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { FormDialogData, FormDialogService } from '../components/form-dialog';
import { MatterSheet, MatterView } from '../data/legal.models';
import { MatterDetail } from './matter-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

function matter(overrides: Partial<MatterView> = {}): MatterView {
  return {
    id: 'm-1',
    collectionCaseId: 'k-1',
    debtorId: 'd-1',
    creditorId: 'c-1',
    portfolioId: 'pf-1',
    origin: 'RULE',
    eligibilityRuleId: 'r-1',
    referralNote: null,
    titleIds: ['t-1'],
    flags: ['PRESCRIPTION_NEAR'],
    stage: 'ELIGIBLE',
    resumeStage: null,
    stageReason: null,
    stageChangedAt: '2026-10-20T12:00:00Z',
    nextStages: ['UNDER_REVIEW', 'RETURNED_TO_CREDITOR', 'CLOSED'],
    primaryRoute: null,
    responsibleType: 'NONE',
    responsibleUserId: null,
    lawFirmId: null,
    debtAmount: 18450.9,
    expectedCosts: null,
    estimatedRecoverable: null,
    authorizationId: null,
    authorizedBudget: null,
    expensesTotal: 0,
    recoveredAmount: 0,
    agreementId: null,
    collectionHold: false,
    referredAt: '2026-10-20T12:00:00Z',
    closedAt: null,
    closureReason: null,
    version: 0,
    ...overrides,
  };
}

function sheet(m: MatterView): MatterSheet {
  return {
    matter: m,
    debtor: { id: 'd-1', name: 'Carlos Eduardo Lima', maskedDocument: '***.274.306-**', personType: 'INDIVIDUAL' },
    titles: [{ titleId: 't-1', externalId: 'CCB-7781', installmentNumber: null, dueDate: '2026-02-10', openBalance: 18450.9, status: 'OPEN', daysOverdue: 255 }],
    viability: null,
    authorizations: [],
    measures: [],
    lawsuits: [],
    deadlines: [
      { id: 'dl-1', matterId: 'm-1', lawsuitId: null, movementId: null, measureId: null, kind: 'RESPONSE', description: 'Manifestar sobre a defesa', courtCode: null, startDate: null, startEvent: null, termDays: 15, countingMode: 'BUSINESS_DAYS', fixedDate: null, dueDate: '2020-01-10', responsibleUserId: null, status: 'MISSED', completedOn: null, statusReason: null, lastAlertLevel: 0, businessDaysRemaining: null, createdAt: '', version: 0 },
    ],
    expenses: [],
    documents: [],
    history: [
      { id: 'h-1', fromStage: null, toStage: 'ELIGIBLE', reason: 'Regra Atraso > 180 dias', closureReason: null, cause: 'RULE', occurredAt: '2026-10-20T12:00:00Z', actorId: null },
    ],
    collaterals: [
      { collateralId: 'k-1', contractId: 'ct-1', type: 'VEHICLE_FIDUCIARY_ALIENATION', description: 'Onix 2019', assetSummary: 'veículo ABC-1D23', status: 'ACTIVE', estimatedValue: 32000, lastAppraisalDate: '2026-09-01', titleIds: ['t-1'] },
    ],
    hearings: [
      { id: 'hr-1', lawsuitId: 'l-1', matterId: 'm-1', type: 'CONCILIATION', scheduledAt: '2026-11-05T13:00:00Z', mode: 'VIDEO', location: null, link: null, participantUserIds: [], externalParticipants: null, status: 'SCHEDULED', outcome: null, resultNotes: null, rescheduleCount: 0, version: 0 },
    ],
  };
}

describe('MatterDetail', () => {
  let http: HttpTestingController;
  let permissions: string[];
  let roles: string[];
  let external: boolean;
  let data: MatterSheet;

  beforeEach(() => {
    requested.length = 0;
    permissions = ['CREDITOR_VIEW', 'LEGAL_VIEW', 'LEGAL_MANAGE', 'LEGAL_AUTHORIZE', 'LEGAL_EXPENSE_MANAGE', 'CASE_VIEW'];
    roles = ['JURIDICO'];
    external = false;
    data = sheet(matter());
    TestBed.configureTestingModule({
      imports: [MatterDetail],
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ id: 'u-1', roles })),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const requested: string[] = [];

  function answer(req: TestRequest): void {
    const url = req.request.url;
    requested.push(url);
    if (url === '/api/v1/legal/matters/m-1/sheet') req.flush(data);
    else if (url === '/api/v1/legal/me') req.flush({ external, lawFirmId: external ? 'f-1' : null, lawFirmName: external ? 'Silva Advogados' : null, firmActive: true });
    else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
    else req.flush([]);
  }

  async function render(tab?: string): Promise<ComponentFixture<MatterDetail>> {
    (TestBed.inject(AuthService).user as WritableSignal<AuthenticatedUser | null>).set(fakeUser({ id: 'u-1', roles }));
    const fixture = TestBed.createComponent(MatterDetail);
    fixture.componentRef.setInput('id', 'm-1');
    if (tab) {
      fixture.componentRef.setInput('aba', tab);
    }
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      http.match((r) => r.method === 'GET').forEach(answer);
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

  it('resumo com fase, sinalização de prescrição, títulos e linha do tempo', async () => {
    const fixture = await render();
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Carlos Eduardo Lima');
    expect(text).toContain('Indicado');
    expect(text).toContain('Prescrição próxima');
    expect(text).toContain('CCB-7781');
    expect(text).toContain('Regra Atraso > 180 dias');
    expect(el(fixture).querySelector('[data-tour="matter-stage"]')).not.toBeNull();
  });

  it('aceitar a indicação exige LEGAL_AUTHORIZE: sem ela, a transição não aparece', async () => {
    let fixture = await render();
    expect(fixture.componentInstance['transitions']()).toEqual(['UNDER_REVIEW', 'RETURNED_TO_CREDITOR', 'CLOSED']);
    fixture.destroy();
    permissions = permissions.filter((p) => p !== 'LEGAL_AUTHORIZE');
    fixture = await render();
    expect(fixture.componentInstance['transitions']()).toEqual(['RETURNED_TO_CREDITOR', 'CLOSED']);
  });

  it('encerrar pede motivo e motivo de encerramento, e envia a transição', async () => {
    const fixture = await render();
    const forms = TestBed.inject(FormDialogService);
    const open = vi.spyOn(forms, 'open').mockReturnValue(EMPTY);
    fixture.componentInstance['transition']('CLOSED');
    const dialog = open.mock.calls[0][0] as FormDialogData;
    expect(dialog.warning).toContain('Prazos pendentes são cancelados');
    expect(dialog.confirmationText).toBe('ENCERRAR');
    const closure = dialog.fields.find((f) => f.key === 'closureReason')!;
    expect(closure.required).toBe(true);
    expect(closure.showIf!({})).toBe(true);
    dialog.submit({ reason: 'Pago', closureReason: 'PAID', agreementId: null }).subscribe();
    const req = http.expectOne('/api/v1/legal/matters/m-1/transitions');
    expect(req.request.body).toEqual({ stage: 'CLOSED', reason: 'Pago', closureReason: 'PAID', agreementId: null });
    req.flush(matter({ stage: 'CLOSED' }));
  });

  it('prazos: aba aberta pelo parâmetro, prazo perdido em destaque', async () => {
    const fixture = await render('prazos');
    await tick();
    fixture.detectChanges();
    expect(fixture.componentInstance['tab']()).toBe(5);
    const card = el(fixture).querySelector('.md-deadline');
    expect(card?.classList).toContain('md-deadline--late');
    expect(card?.textContent).toContain('Prazo perdido');
  });

  it('garantias dos títulos e audiências de todos os processos no resumo', async () => {
    const fixture = await render();
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Garantias (1)');
    expect(text).toContain('veículo ABC-1D23');
    expect(text).toContain('1 de 1');
    expect(text).toContain('Audiências (1)');
    expect(text).toContain('Videoconferência');
  });

  it('visão restrita vem do vínculo com escritório (GET /legal/me), não do código do perfil', async () => {
    roles = ['PERFIL_PERSONALIZADO'];
    external = true;
    permissions = ['LEGAL_VIEW', 'LEGAL_MANAGE', 'LEGAL_EXPENSE_MANAGE'];
    const fixture = await render();
    expect(el(fixture).textContent).toContain('Caso atribuído ao seu escritório');
  });

  it('advogado parceiro: aviso de visão restrita e sem link para o caso de cobrança', async () => {
    roles = ['ADVOGADO_PARCEIRO'];
    external = true;
    permissions = ['LEGAL_VIEW', 'LEGAL_MANAGE', 'LEGAL_EXPENSE_MANAGE'];
    const fixture = await render();
    expect(el(fixture).textContent).toContain('Caso atribuído ao seu escritório');
    expect(el(fixture).textContent).not.toContain('Caso de cobrança');
    // sem CREDITOR_VIEW nem permissão de nomes de usuários: nenhuma consulta de credor ou de usuários (seria 403); a ficha diz "Credor do caso"
    expect(requested.filter((u) => u.startsWith('/api/v1/creditors') || u.startsWith('/api/v1/users'))).toEqual([]);
    expect(el(fixture).textContent).toContain('Credor do caso');
  });
});
