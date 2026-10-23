import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { ConversationDetail, ConversationView, MessageView } from '../data/communication.models';
import { ConversationPanel } from './conversation-panel';

const tick = () => new Promise((r) => setTimeout(r, 0));
const page = <T>(content: T[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

function conversation(overrides: Partial<ConversationView> = {}): ConversationView {
  return {
    id: 'cv-1',
    debtorId: 'd-1',
    creditorId: 'c-1',
    portfolioId: 'pf-1',
    caseId: 'k-1',
    channel: 'WHATSAPP',
    channelAccountId: 'ac-1',
    contactMasked: '(11) 9****-4321',
    status: 'OPEN',
    ownerType: 'QUEUE',
    ownerUserId: null,
    ownerTeamId: null,
    requiredProfile: null,
    ownerSince: null,
    lastInboundAt: '2026-10-23T12:00:00Z',
    lastOutboundAt: null,
    lastMessageAt: '2026-10-23T12:00:00Z',
    awaitingSince: '2026-10-23T12:00:00Z',
    waitingSeconds: 1500,
    unreadCount: 0,
    messageCount: 2,
    sessionWindowOpen: true,
    sessionWindowEndsAt: new Date(Date.now() + 8 * 3600e3).toISOString(),
    closedAt: null,
    closeReason: null,
    createdAt: '2026-10-23T11:00:00Z',
    version: 1,
    ...overrides,
  };
}

function msg(id: string, overrides: Partial<MessageView>): MessageView {
  return {
    id,
    conversationId: 'cv-1',
    channel: 'WHATSAPP',
    direction: 'INBOUND',
    senderType: 'DEBTOR',
    senderId: null,
    templateId: null,
    subject: null,
    content: 'Quero negociar',
    contentPurged: false,
    status: 'RECEIVED',
    statusAt: null,
    failureCode: null,
    failureDetail: null,
    sendAttempts: 0,
    campaignExecutionId: null,
    queuedAt: null,
    sentAt: null,
    deliveredAt: null,
    readAt: null,
    failedAt: null,
    receivedAt: null,
    createdAt: '2026-10-23T12:00:00Z',
    ...overrides,
  };
}

describe('ConversationPanel', () => {
  let http: HttpTestingController;
  let detail: ConversationDetail;
  let messages: MessageView[];
  const announce = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    announce.mockClear();
    detail = { conversation: conversation(), handoffs: [], pendingDrafts: [] };
    messages = [
      msg('m-2', { direction: 'OUTBOUND', senderType: 'ASSISTANT', senderId: 'ap-1', content: 'Posso ajudar?', status: 'DELIVERED', createdAt: '2026-10-23T11:30:00Z' }),
      msg('m-1', { createdAt: '2026-10-23T11:00:00Z', content: 'Oi' }),
    ];
    TestBed.configureTestingModule({
      imports: [ConversationPanel],
      providers: [
        provideLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LiveAnnouncer, useValue: { announce } },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser({ id: 'u-1' })), hasPermission: (p: string) => ['MESSAGE_SEND', 'INBOX_VIEW', 'CASE_VIEW'].includes(p), hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function answer(req: TestRequest): void {
    const url = req.request.url;
    if (url === '/api/v1/communication/conversations/cv-1') req.flush(detail);
    else if (url === '/api/v1/communication/conversations/cv-1/messages') req.flush(page(messages));
    else if (url === '/api/v1/communication/conversations') req.flush(page(req.request.params.get('status') === 'OPEN' ? [detail.conversation] : []));
    else if (url === '/api/v1/communication/templates') req.flush(page([]));
    else if (url === '/api/v1/debtors/d-1') req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro' });
    else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
    else req.flush([]);
  }

  async function settle(fixture: ComponentFixture<ConversationPanel>): Promise<void> {
    for (let i = 0; i < 6; i++) {
      fixture.detectChanges();
      await tick();
      http.match((r) => r.method === 'GET').forEach(answer);
    }
    fixture.detectChanges();
  }

  async function render(): Promise<ComponentFixture<ConversationPanel>> {
    const fixture = TestBed.createComponent(ConversationPanel);
    fixture.componentRef.setInput('conversationId', 'cv-1');
    await settle(fixture);
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('linha do tempo cronológica com canal por texto e assistente identificado', async () => {
    const fixture = await render();
    const items = [...el(fixture).querySelectorAll('.cv-msg')];
    expect(items.map((i) => i.querySelector('.cv-msg__body')?.textContent)).toEqual(['Oi', 'Posso ajudar?']);
    expect(items[0].textContent).toContain('WhatsApp');
    expect(items[0].textContent).toContain('Devedor');
    expect(items[1].textContent).toContain('Assistente');
    expect(items[1].textContent).toContain('Entregue');
    expect(el(fixture).querySelector('.cv-sla')?.textContent).toContain('aguardando há 25 min');
  });

  it('na fila: responder livremente dentro da janela do WhatsApp; assumir pede confirmação', async () => {
    const fixture = await render();
    expect(el(fixture).textContent).toContain('Janela de 24 h do WhatsApp aberta');
    expect(el(fixture).querySelector('app-composer textarea')).not.toBeNull();
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Assumir')!.click();
    const req = http.expectOne('/api/v1/communication/conversations/cv-1/take-over');
    expect(req.request.body).toEqual({ reason: null });
    detail = { ...detail, conversation: conversation({ ownerType: 'USER', ownerUserId: 'u-1' }) };
    req.flush(detail.conversation);
    await settle(fixture);
    expect(el(fixture).textContent).toContain('Com você');
  });

  it('com o assistente: explica por que não dá para responder e mostra rascunhos para revisão', async () => {
    detail = {
      conversation: conversation({ ownerType: 'USER', ownerUserId: 'u-1' }),
      handoffs: [],
      pendingDrafts: [{ id: 'dr-1', conversationId: 'cv-1', mode: 'COPILOT', authorType: 'ASSISTANT', authorId: 'ap-1', content: 'Podemos parcelar em 6x.', rationale: 'Devedor pediu parcelamento', status: 'PENDING', decidedBy: null, decidedAt: null, edited: false, messageId: null, createdAt: '2026-10-23T12:01:00Z' }],
    };
    const fixture = await render();
    expect(el(fixture).textContent).toContain('Sugestões do assistente para revisar');
    expect(el(fixture).textContent).toContain('Por quê: Devedor pediu parcelamento');
    fixture.componentInstance['editDraft'](detail.pendingDrafts[0], 'Podemos parcelar em 5x.');
    fixture.detectChanges();
    button(fixture, 'Enviar editado')!.click();
    const req = http.expectOne('/api/v1/communication/conversations/cv-1/drafts/dr-1/send');
    expect(req.request.body).toEqual({ content: 'Podemos parcelar em 5x.' });
    req.flush(msg('m-3', { direction: 'OUTBOUND', senderType: 'USER', senderId: 'u-1', status: 'QUEUED' }));
    await settle(fixture);
  });

  it('bloqueio do porteiro aparece em linguagem clara', async () => {
    messages = [msg('m-9', { direction: 'OUTBOUND', senderType: 'USER', senderId: 'u-1', status: 'FAILED', failureCode: 'CONTACT_DENIED', failureDetail: 'OUTSIDE_WINDOW' })];
    detail = { ...detail, conversation: conversation({ ownerType: 'ASSISTANT' }) };
    const fixture = await render();
    expect(el(fixture).querySelector('.cv-msg__block')?.textContent).toContain('Não enviada: está fora do horário permitido para contato.');
    expect(el(fixture).textContent).toContain('O assistente está conduzindo esta conversa. Assuma a conversa para responder.');
  });

  it('anuncia nova mensagem do devedor de forma discreta (polite), sem anunciar a carga inicial', async () => {
    const fixture = await render();
    expect(announce).not.toHaveBeenCalled();
    messages = [msg('m-5', { createdAt: '2026-10-23T12:10:00Z', content: 'Ainda está aí?' }), ...messages];
    fixture.componentInstance['refresh']();
    await settle(fixture);
    expect(announce).toHaveBeenCalledWith('Nova mensagem do devedor pelo WhatsApp.', 'polite');
  });
});
