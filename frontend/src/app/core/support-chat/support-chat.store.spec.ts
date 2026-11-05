import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../auth/auth.models';
import { AuthService } from '../auth/auth.service';
import { SupportAnswer, SupportChatStatus, SupportMessage } from './support-chat.models';
import { SupportChatStore } from './support-chat.store';

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
    cards: [
      {
        ref: 'd-1',
        kind: 'DEBTOR',
        title: 'Devedor Fictício',
        subtitle: '***.456.789-**',
        status: null,
        fields: [],
        route: '/devedores/d-1',
        queryParams: {},
      },
    ],
    actions: [],
    ticket: null,
    outcome: 'ANSWERED',
    feedback: null,
    purged: false,
    createdAt: '2026-10-26T12:00:00Z',
    ...overrides,
  };
}

function answer(conversationId = 'c-1'): SupportAnswer {
  return { conversationId, question: message({ role: 'USER', text: 'pergunta', outcome: null, cards: [] }), answer: message() };
}

describe('SupportChatStore', () => {
  let http: HttpTestingController;
  let store: SupportChatStore;
  const user = signal<AuthenticatedUser | null>(null);

  beforeEach(() => {
    user.set(fakeUser());
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: { user } }],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(SupportChatStore);
    TestBed.tick();
  });

  afterEach(() => http.verify());

  async function load(): Promise<void> {
    const pending = store.loadStatus();
    http.expectOne('/api/v1/helpdesk/status').flush(STATUS);
    await pending;
  }

  async function ask(): Promise<void> {
    const pending = store.send('Quem é este devedor?', 'collection.queue');
    http.expectOne('/api/v1/helpdesk/messages').flush(answer());
    await pending;
  }

  it('mantém a conversa enquanto o mesmo usuário segue conectado', async () => {
    await load();
    await ask();
    TestBed.tick();
    expect(store.messages()).toHaveLength(2);
    expect(store.conversationId()).toBe('c-1');
    expect(store.availability()).toBe('available');
  });

  it('descarta conversa, cartões e situação no logout', async () => {
    await load();
    await ask();
    user.set(null);
    TestBed.tick();
    expect(store.messages()).toEqual([]);
    expect(store.conversationId()).toBeNull();
    expect(store.status()).toBeNull();
    expect(store.availability()).toBe('unknown');
  });

  it('descarta a conversa na troca de usuário (inclusive de outra assessoria)', async () => {
    await load();
    await ask();
    user.set(fakeUser({ id: 'u-2', tenantId: 't-2', tenantSlug: 'outra-assessoria' }));
    TestBed.tick();
    expect(store.messages()).toEqual([]);
    expect(store.conversationId()).toBeNull();
  });

  it('ignora a resposta que chega depois do fim da sessão', async () => {
    await load();
    const pending = store.send('Quem é este devedor?', 'collection.queue');
    const req = http.expectOne('/api/v1/helpdesk/messages');
    user.set(null);
    TestBed.tick();
    user.set(fakeUser());
    TestBed.tick();
    req.flush(answer());
    expect(await pending).toBeNull();
    expect(store.messages()).toEqual([]);
    expect(store.conversationId()).toBeNull();
    expect(store.sending()).toBe(false);
  });
});
