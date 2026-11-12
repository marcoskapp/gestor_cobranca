import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NgbOffcanvas } from '@ng-bootstrap/ng-bootstrap';
import { of } from 'rxjs';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { CloseAgreementDialog } from '../../negotiation/case/close-agreement-dialog';
import { proposal } from '../../../../testing/negotiation-fixtures';
import { AttendanceService } from './attendance.service';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { breakSuspensionInfo, reviewItem } from '../../../../testing/payment-review-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { provideAppLocale } from '../../../core/config/app-locale';
import { KeyboardShortcuts } from '../../../core/keyboard/keyboard-shortcuts.service';
import { NotificationService } from '../../../core/notification/notification.service';
import { OnboardingService } from '../../../core/onboarding/onboarding.service';
import { ThemeService } from '../../../core/preferences/theme.service';
import { DEFAULT_UI_STATE, UiState } from '../../../core/preferences/ui-state.models';
import { UiStateStore } from '../../../core/preferences/ui-state.store';
import { CaseSheet, CaseView, ContactAvailability, TabulationView } from '../data/collection.models';
import { addDays, zonedNow } from '../data/collection.rules';
import { NameMap } from '../../../shared/data/name-map';
import { CaseSheetPanel, allowedSheetTab, tabFromSlug } from './case-sheet-panel';
import { CollectionStation } from './collection-station';
import { DENSITY_INVITE_ID } from './density-invite';

export function caseView(overrides: Partial<CaseView> = {}): CaseView {
  return {
    id: 'k-1',
    debtorId: 'd-1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    status: 'IN_NEGOTIATION',
    resumeStatus: null,
    statusReason: null,
    openedAt: '2026-09-01T12:00:00Z',
    closedAt: null,
    closeReason: null,
    openBalance: 1250.4,
    eligibleBalance: 1250.4,
    openTitles: 1,
    suspendedTitles: 0,
    disputedTitles: 0,
    oldestDueDate: '2026-08-10',
    daysOverdue: 74,
    eligible: true,
    eligibilityReasons: [],
    contactableChannels: ['PHONE', 'EMAIL'],
    eligibilityEvaluatedAt: null,
    queueId: 'q-1',
    ownerType: 'USER',
    assigneeUserId: 'u-1',
    assigneeTeamId: null,
    assignedAt: null,
    nextActionAt: null,
    lastInteractionAt: null,
    lastEffectiveContactAt: null,
    interactionCount: 2,
    effectiveContactCount: 1,
    score: null,
    version: 3,
    ...overrides,
  };
}

export function sheet(overrides: Partial<CaseSheet> = {}): CaseSheet {
  return {
    collectionCase: caseView(),
    debtor: {
      id: 'd-1',
      name: 'Ana Paula Ribeiro',
      maskedDocument: '***.982.247-**',
      personType: 'INDIVIDUAL',
      status: 'ACTIVE',
      contacts: [{ contactId: 'ct-1', type: 'PHONE', maskedValue: '(11) 9****-4321', label: 'MOBILE', whatsapp: true, status: 'VALID', priority: 1, receipt: null }],
    },
    titles: [
      { titleId: 't-1', externalId: 'CARTAO-4471', installmentNumber: null, dueDate: '2026-08-10', principalAmount: 1000, openBalance: 1250.4, status: 'OPEN', disputeBlocked: false, daysOverdue: 74 },
    ],
    activeRestrictions: [],
    openDisputes: [],
    pendingPromise: null,
    openTasks: [],
    recentInteractions: [],
    otherCases: [],
    ...overrides,
  };
}

const tabulations: TabulationView[] = [
  { id: 'tb-1', code: 'CPC_NEGOCIANDO', name: 'Em negociação', effectiveContact: true, promise: false, callback: false, requiresNote: true, followUp: 'NONE', disputeType: null, displayOrder: 1, active: true, version: 0 },
  { id: 'tb-2', code: 'PROMESSA', name: 'Promessa de pagamento', effectiveContact: true, promise: true, callback: false, requiresNote: false, followUp: 'NONE', disputeType: null, displayOrder: 2, active: true, version: 0 },
  { id: 'tb-3', code: 'ANTIGA', name: 'Desativada', effectiveContact: false, promise: false, callback: false, requiresNote: false, followUp: 'NONE', disputeType: null, displayOrder: 3, active: false, version: 0 },
];

const availability: ContactAvailability = {
  caseId: 'k-1',
  evaluatedAt: '2026-10-23T13:00:00Z',
  caseEligible: true,
  caseReasons: [],
  channels: [
    { channel: 'PHONE', allowed: true, reasons: [], nextAllowedAt: null, contactId: null },
    { channel: 'WHATSAPP', allowed: false, reasons: ['OUTSIDE_WINDOW'], nextAllowedAt: '2026-10-24T11:00:00Z', contactId: null },
    { channel: 'SMS', allowed: false, reasons: ['RESTRICTED_CHANNEL'], nextAllowedAt: null, contactId: null },
    { channel: 'EMAIL', allowed: true, reasons: [], nextAllowedAt: null, contactId: null },
  ],
};

const page = <T>(content: T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });

describe('CollectionStation', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const ui = signal<UiState>({ ...DEFAULT_UI_STATE });
  const notifications = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
  const recordTour = vi.fn();

  beforeEach(() => {
    permissions.set(['CASE_VIEW', 'CASE_WORK', 'INTERACTION_REGISTER', 'TASK_MANAGE', 'NEGOTIATION_SIMULATE']);
    ui.set({ ...DEFAULT_UI_STATE });
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [CollectionStation],
      providers: [
                provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ id: 'u-1' })),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
        { provide: UiStateStore, useValue: { state: ui, ready: signal(true), recordTour } },
        { provide: OnboardingService, useValue: { hint: signal(null) } },
        { provide: ThemeService, useValue: { setDensity: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  let reviewStatus: object = {};

  /** Responde a todas as requisições pendentes com fixtures, por URL. */
  function respond(current: CaseSheet | null): void {
    for (let i = 0; i < 4; i++) {
      const pending = http.match(() => true);
      if (pending.length === 0) {
        return;
      }
      for (const req of pending) {
        reply(req, current);
      }
    }
  }

  function reply(req: TestRequest, current: CaseSheet | null): void {
    const url = req.request.url;
    if (url === '/api/v1/collection/queues') return req.flush([{ id: 'q-1', name: 'Atraso 61-90', active: true }]);
    if (url === '/api/v1/collection/my-cases') return req.flush(page([caseView()]));
    if (url === '/api/v1/collection/tabulations') return req.flush(tabulations);
    if (url === '/api/v1/collection/settings') return req.flush({ promiseToleranceDays: 2, promiseMaxDays: 30, callbackToleranceMinutes: 30, disputeResponseDays: 10, customized: false, updatedAt: null });
    if (url === '/api/v1/organization/tenant') return req.flush({ contactWindowStart: '08:00:00', contactWindowEnd: '20:00:00', contactOnSaturday: false, contactOnSunday: false });
    if (url.endsWith('/sheet')) return req.flush(current);
    if (url.endsWith('/contact-availability')) return req.flush(availability);
    if (url === '/api/v1/debtors/names') {
      // nomes em lote: uma chamada para todos os devedores da lista
      return req.flush((req.request.params.get('ids') ?? '').split(',').map((id) => ({ id, name: 'Ana Paula Ribeiro', status: 'ACTIVE' })));
    }
    if (url.startsWith('/api/v1/debtors/')) return req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: null });
    if (url.startsWith('/api/v1/creditors/')) return req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
    if (url.startsWith('/api/v1/portfolios/')) return req.flush({ id: 'p-1', name: 'Cartões setembro' });
    if (url === '/api/v1/payment/review-status/debtors/d-1') return req.flush(reviewStatus);
    if (url === '/api/v1/users/display-names') return req.flush([{ id: 'u-2', name: 'Carla Operadora' }]);
    if (url === '/api/v1/communication/conversations') return req.flush(page([]));
    if (url === '/api/v1/negotiation/proposals') return req.flush(page([]));
    if (url === '/api/v1/negotiation/agreements') return req.flush(page([]));
    if (url === '/api/v1/negotiation/authority/me') return req.flush({ profile: { canFormalize: true } });
    if (url.startsWith('/api/v1/compliance/vulnerability/debtors/')) return req.flush({ debtorId: 'd-1', vulnerable: false, categories: [], paymentCapacity: null, pendingSuggestion: null, markers: [], canRegister: false, canManage: false });
    throw new Error(`requisição inesperada: ${req.request.method} ${url}`);
  }

  async function render(caso: string | null, current: CaseSheet | null = sheet()): Promise<ComponentFixture<CollectionStation>> {
    const fixture = TestBed.createComponent(CollectionStation);
    if (caso) {
      fixture.componentRef.setInput('id', caso);
    }
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(current);
    }
    fixture.detectChanges();
    return fixture;
  }

  const text = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';
  const press = (key: string) => document.dispatchEvent(new KeyboardEvent('keydown', { key, altKey: true, bubbles: true }));

  it('três zonas: fila do operador, ficha do caso e painel de ação com a janela de contato', async () => {
    const fixture = await render('k-1');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[aria-label="Meus casos"]')?.textContent).toContain('Ana Paula Ribeiro');
    expect(el.querySelector('[aria-label="Ficha do caso"]')?.textContent).toContain('***.982.247-**');
    const window = el.querySelector('app-contact-window')!;
    const panel = window.textContent ?? '';
    // decisão do servidor por canal numa linha de chips; o detalhe só dos canais não liberados
    expect(panel).toContain('Posso contatar?');
    const chips = [...window.querySelectorAll('.cw__chip')].map((c) => c.getAttribute('aria-label'));
    expect(chips).toEqual(['Telefone: Permitido', 'WhatsApp: Aguardar', 'SMS: Restrito', 'E-mail: Permitido']);
    expect(window.querySelector('.cw__restricted')?.textContent).toContain('SMS');
    expect(panel).toContain('a partir de');
    // registro de interação no topo da coluna de ações (antes, ficava abaixo da dobra)
    const actions = el.querySelector('[aria-label="Ações do atendimento"]')!;
    expect(actions.querySelector('app-interaction-panel')).not.toBeNull();
    expect(actions.querySelector('.ip__submit')).not.toBeNull();
    // tabulação desativada não aparece no formulário
    expect(text(fixture)).toContain('Registrar interação');
  });

  it('ficha do caso avisa de forma mínima o pagamento informado aguardando aprovação e o recusado', async () => {
    permissions.update((p) => [...p, 'AGREEMENT_VIEW']);
    reviewStatus = {
      creditorId: 'c-1',
      debtorId: 'd-1',
      pendingCount: 1,
      pendingTotal: 1350,
      pending: [reviewItem()],
      rejected: [reviewItem({ paymentId: 'pg-3', status: 'REJECTED', reviewedBy: 'u-2', reviewedAt: '2026-10-26T10:00:00Z', reasonRestricted: true })],
      rejectedSince: '2026-09-26T00:00:00Z',
      breakSuspension: breakSuspensionInfo(),
    };
    const fixture = await render('k-1');
    // o aviso resolve os nomes de quem informou/recusou depois de receber a situação
    for (let i = 0; i < 2; i++) {
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet());
      fixture.detectChanges();
    }
    const sheetEl = (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Ficha do caso"]')!;
    const alert = sheetEl.querySelector('app-payment-review-alert')!;
    const t = alert.textContent!.replace(/\s+/g, ' ');
    expect(t).toContain('Pagamento de R$ 1.350,00 informado em 25/10/2026');
    expect(t).toContain('aguardando aprovação de outra pessoa');
    expect(t).toContain('Só baixa depois da aprovação.');
    expect(t).toContain('Se a regra de quebra for atingida, a quebra fica suspensa até a revisão (no máximo 7 dias).');
    expect(t).toContain('foi recusado em 26/10/2026');
    expect(alert.querySelector('.pra--compact')).not.toBeNull();
    expect(alert.querySelector('a[href="/negociacao/acordos/a-1"]')).not.toBeNull();
  });

  it('aba Acordos (Alt + 8): oculta e sem atalho sem AGREEMENT_VIEW', async () => {
    const fixture = await render('k-1');
    expect(fixture.componentInstance['tab']()).toBe(0);
    press('8');
    fixture.detectChanges();
    expect(fixture.componentInstance['tab']()).toBe(0);
    expect([...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-tour="case-tabs"] .dropdown-item')].map((b) => b.textContent)).not.toContain('Acordos');
    expect(allowedSheetTab(7, { conversations: false, agreements: true })).toBe(7);
    expect(allowedSheetTab(7, { conversations: true, agreements: false })).toBe(0);
    expect(allowedSheetTab(6, { conversations: false, agreements: true })).toBe(0);
    expect(tabFromSlug('acordos')).toBe(7);
  });

  it('Mensagem e aba Conversas: aparecem só com as permissões de comunicação', async () => {
    let fixture = await render('k-1');
    expect([...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-tour="case-actions"] button')].map((b) => b.textContent?.trim())).not.toContain('Mensagem');
    expect(text(fixture)).not.toContain('Conversas');
    // sem INBOX_VIEW o atalho da aba oculta não faz nada
    press('7');
    fixture.detectChanges();
    expect(fixture.componentInstance['tab']()).toBe(0);
    fixture.destroy();

    permissions.update((p) => [...p, 'MESSAGE_SEND', 'INBOX_VIEW']);
    fixture = await render('k-1');
    const actions = [...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-tour="case-actions"] button')].map((b) => b.textContent?.trim());
    expect(actions).toContain('Mensagem');
    press('7');
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet());
    }
    fixture.detectChanges();
    const active = (fixture.nativeElement as HTMLElement).querySelector('[data-tour="case-tabs"] .nav-link.active');
    expect(active?.textContent).toContain('Conversas');
    expect(text(fixture)).toContain('Nenhuma conversa neste caso.');
  });

  it('ficha reaproveitada entre casos: a revelação do documento que chega depois da troca é descartada', async () => {
    // a estação recria a ficha ao trocar de caso; o painel também se protege quando é reaproveitado
    permissions.update((p) => [...p, 'DEBTOR_SENSITIVE_VIEW']);
    const names: NameMap = { name: () => 'Horizonte', names: signal(new Map()) };
    const fixture = TestBed.createComponent(CaseSheetPanel);
    fixture.componentRef.setInput('sheet', sheet());
    fixture.componentRef.setInput('creditors', names);
    fixture.componentRef.setInput('portfolios', names);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet());
    }
    ((fixture.nativeElement as HTMLElement).querySelector('.app-document__reveal') as HTMLButtonElement).click();
    const pending = http.expectOne({ method: 'GET', url: '/api/v1/debtors/d-1/document' });

    fixture.componentRef.setInput(
      'sheet',
      sheet({
        collectionCase: caseView({ id: 'k-2', debtorId: 'd-2' }),
        debtor: { ...sheet().debtor, id: 'd-2', name: 'Bruno Costa Lima', maskedDocument: '***.111.444-**', contacts: [] },
      }),
    );
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet());
    }
    pending.flush({ debtorId: 'd-1', documentType: 'CPF', document: '529.982.247-25' });
    fixture.detectChanges();
    expect(text(fixture)).toContain('***.111.444-**');
    expect(text(fixture)).not.toContain('529.982.247-25');
  });

  it('registra os atalhos do grupo "Estação de cobrança" e os remove ao sair', async () => {
    const fixture = await render('k-1');
    const keys = TestBed.inject(KeyboardShortcuts);
    const ids = keys.shortcuts().filter((s) => s.group === 'Estação de cobrança').map((s) => s.keys);
    expect(ids).toEqual(expect.arrayContaining(['alt+n', 'alt+r', 'alt+a', 'alt+s', 'alt+w', 'alt+1', 'alt+5', 'alt+6', 'alt+7']));
    press('3');
    fixture.detectChanges();
    expect(fixture.componentInstance['tab']()).toBe(2);
    // aba ativa da ficha (ngbNav) acompanha o atalho e mostra o conteúdo do histórico
    const active = (fixture.nativeElement as HTMLElement).querySelector('[data-tour="case-tabs"] .nav-link.active');
    expect(active?.textContent).toContain('Histórico');
    expect(active?.getAttribute('aria-selected')).toBe('true');
    expect(text(fixture)).toContain('Nenhuma interação registrada.');
    fixture.destroy();
    expect(keys.shortcuts().some((s) => s.group === 'Estação de cobrança')).toBe(false);
  });

  it('próximo caso (Alt + N): sem caso disponível avisa; com caso, abre a ficha', async () => {
    const fixture = await render(null);
    expect(text(fixture)).toContain('Nenhum caso aberto.');
    press('n');
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/collection/next-case').flush(null, { status: 204, statusText: 'No Content' });
    expect(notifications.info).toHaveBeenCalledWith('Nenhum caso disponível agora nas suas filas.');

    press('n');
    http.expectOne('/api/v1/collection/next-case').flush(caseView({ id: 'k-2' }));
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet({ collectionCase: caseView({ id: 'k-2' }) }));
    }
    fixture.detectChanges();
    expect(fixture.componentInstance['selectedId']()).toBe('k-2');
    expect(text(fixture)).toContain('CARTAO-4471');
  });

  it('interação: observação obrigatória pela tabulação, depois envia com o caso e o início do atendimento', async () => {
    const fixture = await render('k-1');
    const panel = fixture.debugElement.query((d) => d.name === 'app-interaction-panel').componentInstance as {
      form: { patchValue(v: object): void };
    };
    panel.form.patchValue({ tabulationId: 'tb-1' });
    fixture.detectChanges();
    const submit = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('app-interaction-panel button[type="submit"]')!;
    submit.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('A tabulação "Em negociação" exige observação.');

    panel.form.patchValue({ note: 'Devedor pediu proposta por WhatsApp' });
    fixture.detectChanges();
    submit.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/collection/cases/k-1/interactions');
    expect(req.request.body).toMatchObject({
      channel: 'PHONE',
      direction: 'OUTBOUND',
      tabulationId: 'tb-1',
      note: 'Devedor pediu proposta por WhatsApp',
      contactId: 'ct-1',
      promise: null,
      callback: null,
    });
    expect(req.request.body.startedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    req.flush({ interaction: {}, duplicate: false, promise: null, callback: null, collectionCase: caseView() });
    expect(notifications.success).toHaveBeenCalledWith('Interação registrada.');
    respond(sheet());
  });

  it('promessa: valor e data (LocalDate ISO do calendário) vão na interação', async () => {
    const fixture = await render('k-1');
    const panel = fixture.debugElement.query((d) => d.name === 'app-interaction-panel').componentInstance as {
      form: { patchValue(v: object): void };
    };
    panel.form.patchValue({ tabulationId: 'tb-2' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-interaction-panel input[placeholder="dd/mm/aaaa"]')).not.toBeNull();
    const due = addDays(zonedNow().date, 3);
    panel.form.patchValue({ promiseAmount: 300, promiseDate: due });
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('app-interaction-panel button[type="submit"]')!.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/collection/cases/k-1/interactions');
    expect(req.request.body).toMatchObject({ tabulationId: 'tb-2', promise: { amount: 300, dueDate: due } });
    req.flush({ interaction: {}, duplicate: false, promise: { amount: 300 }, callback: null, collectionCase: caseView() });
    respond(sheet());
  });

  it('caso de outro operador fica somente leitura para quem não tem CASE_ASSIGN', async () => {
    const fixture = await render('k-1', sheet({ collectionCase: caseView({ assigneeUserId: 'u-9' }) }));
    expect(text(fixture)).toContain('Caso de outro operador');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-interaction-panel')).toBeNull();
  });

  it('quem só consulta o caso (CASE_VIEW, ex.: financeiro) não vê nem consulta a janela de contato (CASE_WORK)', async () => {
    permissions.set(['CASE_VIEW']);
    const urls: string[] = [];
    const fixture = TestBed.createComponent(CollectionStation);
    fixture.componentRef.setInput('id', 'k-1');
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      for (const req of http.match(() => true)) {
        urls.push(req.request.url);
        reply(req, sheet());
      }
    }
    fixture.detectChanges();
    expect(urls.some((u) => u.endsWith('/sheet'))).toBe(true);
    expect(urls.some((u) => u.endsWith('/contact-availability'))).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('app-contact-window')).toBeNull();
  });

  it('atendimento preservado: sair da estação e voltar mantém o início, o rascunho e a aba do caso', async () => {
    let fixture = await render('k-1');
    const started = fixture.componentInstance['startedAt']();
    expect(started).toBeInstanceOf(Date);
    const panel = () =>
      fixture.debugElement.query((d) => d.name === 'app-interaction-panel').componentInstance as { form: { patchValue(v: object): void; getRawValue(): { note: string; channel: string } } };
    panel().form.patchValue({ channel: 'WHATSAPP', note: 'Pediu 2 dias para confirmar' });
    press('3');
    fixture.detectChanges();
    fixture.destroy();

    fixture = await render('k-1');
    expect(fixture.componentInstance['startedAt']()).toBe(started);
    expect(panel().form.getRawValue()).toMatchObject({ channel: 'WHATSAPP', note: 'Pediu 2 dias para confirmar' });
    expect(fixture.componentInstance['tab']()).toBe(2);
    // nada disso vai para o armazenamento do navegador (a observação pode ter dado pessoal)
    expect(JSON.stringify({ ...localStorage })).not.toContain('Pediu 2 dias');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('Pediu 2 dias');
    TestBed.inject(AttendanceService).clear();
  });

  it('aba na URL: ?aba= abre a aba e a troca de aba grava na URL', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const fixture = TestBed.createComponent(CollectionStation);
    fixture.componentRef.setInput('id', 'k-1');
    fixture.componentRef.setInput('aba', 'negociacao');
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet());
    }
    expect(fixture.componentInstance['tab']()).toBe(5);
    press('2');
    fixture.detectChanges();
    // gravação de estado de tela (shared/data/url-state): sai na tarefa seguinte, com replaceUrl
    await new Promise((r) => setTimeout(r, 0));
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { aba: 'contatos' }, queryParamsHandling: 'merge', replaceUrl: true, state: { urlState: true } }));
    TestBed.inject(AttendanceService).clear();
  });

  it('abas que não cabem ficam em "Mais N ▾"; com uma delas ativa, o botão mostra o nome curto dela e "+N"', async () => {
    const fixture = await render('k-1');
    const tabs = (fixture.nativeElement as HTMLElement).querySelector('[data-tour="case-tabs"]')!;
    const visible = [...tabs.querySelectorAll(':scope > li:not(.d-none) > .nav-link')].map((b) =>
      b.classList.contains('sp-more') ? b.querySelector('.sp-more__label')?.textContent?.trim() : b.textContent?.trim(),
    );
    expect(visible).toEqual(['Títulos (1)', 'Contatos (1)', 'Histórico (2)', 'Negociação', 'Mais']);
    const more = tabs.querySelector<HTMLElement>('.sp-more')!;
    const hidden = Number(more.querySelector('.sp-more__count')?.textContent?.trim());
    expect(hidden).toBeGreaterThan(1);
    expect(more.querySelector('.sp-more__chevron')).not.toBeNull();
    expect(more.getAttribute('aria-label')).toBe(`Mais abas (${hidden})`);
    press('4');
    fixture.detectChanges();
    expect(more.querySelector('.sp-more__label')?.textContent?.trim()).toBe('Agenda');
    expect(more.querySelector('.sp-more__count')?.textContent?.trim()).toBe(`+${hidden - 1}`);
    expect(more.getAttribute('aria-label')).toContain('aberta: Agenda e promessas');
    TestBed.inject(AttendanceService).clear();
  });

  it('Simular abre o simulador no painel lateral, sem sair da estação', async () => {
    const open = vi.spyOn(TestBed.inject(NgbOffcanvas), 'open').mockReturnValue({ result: new Promise(() => undefined), dismiss: vi.fn() } as never);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const fixture = await render('k-1');
    press('s');
    expect(open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ position: 'end', panelClass: 'app-station-panel' }));
    expect(navigate).not.toHaveBeenCalledWith(['/negociacao/simulador'], expect.anything());
    fixture.destroy();
    TestBed.inject(AttendanceService).clear();
  });

  it('"Ficha do devedor" só aparece com DEBTOR_VIEW', async () => {
    const button = (f: ComponentFixture<unknown>) =>
      [...(f.nativeElement as HTMLElement).querySelectorAll('.sp-head button')].find((b) => b.textContent?.includes('Ficha do devedor'));
    let fixture = await render('k-1');
    expect(button(fixture)).toBeUndefined();
    fixture.destroy();
    permissions.update((p) => [...p, 'DEBTOR_VIEW']);
    fixture = await render('k-1');
    expect(button(fixture)).toBeDefined();
    TestBed.inject(AttendanceService).clear();
  });

  it('o simulador abre com o canal escolhido no registro, mesmo trocado depois de abrir o caso', async () => {
    vi.spyOn(TestBed.inject(NgbOffcanvas), 'open').mockReturnValue({ result: new Promise(() => undefined), dismiss: vi.fn() } as never);
    const fixture = await render('k-1');
    const panel = fixture.debugElement.query((d) => d.name === 'app-interaction-panel').componentInstance as { form: { patchValue(v: object): void } };
    expect(fixture.componentInstance['simulatorChannel']()).toBeUndefined();
    panel.form.patchValue({ channel: 'WHATSAPP' });
    press('s');
    expect(fixture.componentInstance['simulatorChannel']()).toBe('WHATSAPP');
    panel.form.patchValue({ channel: 'EMAIL' });
    press('s');
    expect(fixture.componentInstance['simulatorChannel']()).toBe('EMAIL');
    fixture.destroy();
    TestBed.inject(AttendanceService).clear();
  });

  it('alçada que chega depois da troca de caso não abre o diálogo sobre o outro caso', async () => {
    permissions.update((p) => [...p, 'AGREEMENT_FORMALIZE']);
    const dialogOpen = vi.spyOn(TestBed.inject(DialogService), 'open');
    const fixture = await render('k-1');
    fixture.componentInstance['closeAgreement'](proposal({ status: 'DRAFT', caseId: 'k-1' }));
    const authority = http.expectOne((r) => r.url === '/api/v1/negotiation/authority/me');
    // o operador abre outro caso antes da resposta
    fixture.componentInstance['selectedId'].set('k-2');
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      respond(sheet({ collectionCase: caseView({ id: 'k-2' }) }));
    }
    authority.flush({ profile: { canFormalize: true } });
    expect(dialogOpen).not.toHaveBeenCalled();
    respond(sheet({ collectionCase: caseView({ id: 'k-2' }) }));
    TestBed.inject(AttendanceService).clear();
  });

  it('proposta criada no painel dentro da alçada segue para o aceite com o canal do atendimento', async () => {
    permissions.update((p) => [...p, 'AGREEMENT_FORMALIZE']);
    const dialogOpen = vi.spyOn(TestBed.inject(DialogService), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    const fixture = await render('k-1');
    const panel = fixture.debugElement.query((d) => d.name === 'app-interaction-panel').componentInstance as { form: { patchValue(v: object): void } };
    panel.form.patchValue({ channel: 'WHATSAPP' });
    fixture.componentInstance['onProposed'](proposal({ status: 'DRAFT', caseId: 'k-1' }));
    respond(sheet());
    expect(dialogOpen).toHaveBeenCalledWith(
      CloseAgreementDialog,
      expect.objectContaining({ data: expect.objectContaining({ channel: 'WHATSAPP', canFormalize: true }) }),
    );
    expect(fixture.componentInstance['tab']()).toBe(5);
    respond(sheet());
    TestBed.inject(AttendanceService).clear();
  });

  it('sugere a densidade compacta uma vez, sem impor', async () => {
    const original = window.matchMedia;
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: true }) });
    const fixture = await render(null);
    expect(text(fixture)).toContain('Mais casos por tela?');
    ui.set({ ...DEFAULT_UI_STATE, tours: { [DENSITY_INVITE_ID]: { version: 1, outcome: 'DISMISSED', recordedAt: 'x' } } });
    fixture.detectChanges();
    expect(text(fixture)).not.toContain('Mais casos por tela?');
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: original });
  });
});
