import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NgbActiveOffcanvas } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../auth/auth.service';
import { HelpService } from '../layout/help/help.service';
import { findScreen } from '../onboarding/content/screens';
import { OnboardingService } from '../onboarding/onboarding.service';
import { SupportChatPanel } from './support-chat-panel';
import { SupportAnswer, SupportChatStatus, SupportMessage, SupportTicket } from './support-chat.models';

@Component({ template: '' })
class Blank {}

const STATUS: SupportChatStatus = {
  available: true,
  ticketsAvailable: true,
  ticketsUnavailableReason: null,
  questionsToday: 0,
  questionsPerDay: 150,
  knowledgeVersion: 'abc',
};

function message(overrides: Partial<SupportMessage> = {}): SupportMessage {
  return {
    id: 'm-' + Math.random().toString(36).slice(2),
    role: 'ASSISTANT',
    text: 'Resposta',
    cards: [],
    actions: [],
    ticket: null,
    outcome: 'ANSWERED',
    feedback: null,
    purged: false,
    createdAt: '2026-10-26T12:00:00Z',
    ...overrides,
  };
}

function ticket(overrides: Partial<SupportTicket> = {}): SupportTicket {
  return {
    id: 'tk-1',
    status: 'DRAFT',
    title: '[Suporte] Tela trava',
    body: '## Resumo\n\nA tela trava com [nome].\n\n> O retorno será dado por e-mail para maria@teste.com.br.',
    labels: ['suporte'],
    repository: 'marcoskapp/gestor_cobranca',
    issueNumber: null,
    issueUrl: null,
    errorCode: null,
    createdAt: '2026-10-26T12:00:00Z',
    expiresAt: '2026-10-27T12:00:00Z',
    ...overrides,
  };
}

function answer(a: Partial<SupportMessage>, conversationId = 'c-1'): SupportAnswer {
  return { conversationId, question: message({ role: 'USER', text: 'pergunta', outcome: null }), answer: message(a) };
}

describe('SupportChatPanel', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>(['CASE_WORK']);
  const screen = signal(findScreen('/cobranca/fila'));
  const startScreenTour = vi.fn().mockResolvedValue(true);
  const openScreenManual = vi.fn().mockResolvedValue(undefined);
  const offcanvas = { close: vi.fn(), dismiss: vi.fn() };

  beforeEach(() => {
    startScreenTour.mockClear();
    openScreenManual.mockClear();
    offcanvas.close.mockClear();
    TestBed.configureTestingModule({
      imports: [SupportChatPanel],
      providers: [
        provideRouter([{ path: '**', component: Blank }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasAnyPermission: (ps: readonly string[]) => ps.length === 0 || ps.some((p) => permissions().includes(p)) } },
        { provide: OnboardingService, useValue: { screen, hasScreenHelp: () => true, startScreenTour } },
        { provide: HelpService, useValue: { openScreenManual } },
        { provide: NgbActiveOffcanvas, useValue: offcanvas },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(status: SupportChatStatus = STATUS): Promise<{ fixture: ComponentFixture<SupportChatPanel>; el: HTMLElement }> {
    const fixture = TestBed.createComponent(SupportChatPanel);
    fixture.detectChanges();
    http.expectOne('/api/v1/helpdesk/status').flush(status);
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  async function send(fixture: ComponentFixture<SupportChatPanel>, text: string, reply: SupportAnswer): Promise<void> {
    const el = fixture.nativeElement as HTMLElement;
    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    const req = http.expectOne('/api/v1/helpdesk/messages');
    expect(req.request.body).toEqual({ conversationId: null, screen: 'collection.queue', text });
    req.flush(reply);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('envia só a chave da tela, mostra a resposta, anuncia em região polida e devolve o foco', async () => {
    const { fixture, el } = await render();
    expect(el.querySelector('[role="log"]')).not.toBeNull();
    await send(fixture, 'Como uso esta tela?', answer({ text: 'Use o Próximo caso.' }));
    expect(el.querySelector('.chat__msg--bot .chat__text')?.textContent).toContain('Use o Próximo caso.');
    const live = el.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(live.textContent).toContain('Resposta do suporte: Use o Próximo caso.');
    expect(document.activeElement).toBe(el.querySelector('textarea'));
  });

  it('cartões com referência e link; ações de tela respeitam a permissão; tour e manual rápido da tela atual', async () => {
    const { fixture, el } = await render();
    await send(
      fixture,
      'devedor',
      answer({
        cards: [
          { ref: 'D1', kind: 'DEBTOR', title: 'Maria Souza', subtitle: '***.982.247-**', status: 'ACTIVE', fields: [], route: '/devedores/x', queryParams: {} },
        ],
        actions: [
          { kind: 'OPEN_SCREEN', screen: 'collection.queue', label: 'Estação de cobrança', route: '/cobranca/fila' },
          { kind: 'OPEN_SCREEN', screen: 'admin.users', label: 'Usuários', route: '/admin/usuarios' },
          { kind: 'START_TOUR', screen: 'collection.queue', label: 'Estação de cobrança', route: null },
          { kind: 'ABOUT_SCREEN', screen: 'collection.queue', label: 'Estação de cobrança', route: null },
        ],
      }),
    );
    expect(el.querySelector('.chat__ref')?.textContent?.trim()).toBe('D1');
    expect(el.querySelector('.chat__card-link')?.getAttribute('href')).toBe('/devedores/x');
    const links = [...el.querySelectorAll('.chat__actions a')].map((a) => a.textContent?.trim());
    expect(links).toEqual(['Abrir: Estação de cobrança']);
    const buttons = [...el.querySelectorAll<HTMLButtonElement>('.chat__actions button')];
    buttons[0].click();
    expect(offcanvas.close).toHaveBeenCalled();
    expect(startScreenTour).toHaveBeenCalled();
    buttons[1].click();
    expect(openScreenManual).toHaveBeenCalled();
  });

  it('chamado: mostra o texto exato antes de enviar e só abre com a confirmação', async () => {
    const { fixture, el } = await render();
    await send(fixture, 'abrir chamado', answer({ outcome: 'UNANSWERED', ticket: ticket() }));
    const body = el.querySelector('.chat__ticket-body') as HTMLElement;
    expect(body.textContent).toContain('O retorno será dado por e-mail');
    http.expectNone('/api/v1/helpdesk/tickets/tk-1/confirm');
    const confirm = [...el.querySelectorAll<HTMLButtonElement>('.chat__ticket-actions button')].find((b) =>
      b.textContent?.includes('Enviar chamado'),
    )!;
    confirm.click();
    http.expectOne('/api/v1/helpdesk/tickets/tk-1/confirm').flush(ticket({ status: 'OPENED', issueNumber: 21, issueUrl: 'https://github.com/x/21' }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('.chat__ticket .alert-success')?.textContent).toContain('Chamado nº 21 aberto');
    expect(el.querySelector('.chat__ticket a')?.getAttribute('rel')).toContain('noopener');
  });

  it('erro de abertura fica no chamado e permite tentar de novo', async () => {
    const { fixture, el } = await render();
    await send(fixture, 'abrir chamado', answer({ ticket: ticket() }));
    (el.querySelector('.chat__ticket-actions .btn-primary') as HTMLButtonElement).click();
    http
      .expectOne('/api/v1/helpdesk/tickets/tk-1/confirm')
      .flush({ code: 'helpdesk.ticket.repository_not_private', detail: 'O repositório de chamados está público.' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('.chat__ticket [role="alert"]')?.textContent).toContain('repositório de chamados está público');
    expect((el.querySelector('.chat__ticket-actions .btn-primary') as HTMLButtonElement).disabled).toBe(false);
  });

  it('avaliação com aria-pressed e motivo opcional na negativa', async () => {
    const { fixture, el } = await render();
    await send(fixture, 'oi', answer({ id: 'a-1' }));
    const [up, down] = [...el.querySelectorAll<HTMLButtonElement>('.chat__rate')];
    expect(up.getAttribute('aria-pressed')).toBe('false');
    down.click();
    const req = http.expectOne('/api/v1/helpdesk/messages/a-1/feedback');
    expect(req.request.body).toEqual({ rating: 'DOWN', reason: null });
    req.flush(message({ id: 'a-1', feedback: 'DOWN' }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(down.getAttribute('aria-pressed')).toBe('true');
    const reasons = [...el.querySelectorAll<HTMLButtonElement>('.chat__reasons button')];
    expect(reasons.map((b) => b.textContent?.trim())).toContain('Incompleta');
    reasons.find((b) => b.textContent?.includes('Incompleta'))!.click();
    expect(http.expectOne('/api/v1/helpdesk/messages/a-1/feedback').request.body).toEqual({ rating: 'DOWN', reason: 'INCOMPLETE' });
  });

  it('limite e indisponibilidade mostram a orientação do servidor sem quebrar o painel', async () => {
    const { fixture, el } = await render();
    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'oi';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    http
      .expectOne('/api/v1/helpdesk/messages')
      .flush({ code: 'helpdesk.limit_daily', detail: 'Você atingiu o limite de uso do chat de hoje.' }, { status: 429, statusText: 'Too Many' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('.chat__composer [role="alert"]')?.textContent).toContain('limite de uso do chat de hoje');
    expect((el.querySelector('textarea') as HTMLTextAreaElement).value).toBe('oi');
  });

  it('estado indisponível sem caixa de pergunta', async () => {
    const { el } = await render({ ...STATUS, available: false });
    expect(el.textContent).toContain('O chat de suporte não está disponível');
    expect(el.querySelector('textarea')).toBeNull();
  });
});
