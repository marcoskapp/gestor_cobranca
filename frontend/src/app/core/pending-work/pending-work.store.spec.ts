import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../auth/auth.models';
import { AuthService } from '../auth/auth.service';
import { pendingItem, pendingSummary } from '../../../testing/pending-work-fixtures';
import { PENDING_WORK_POLL_MS, PendingWorkStore } from './pending-work.store';

const URL = '/api/v1/me/pending-work';

describe('PendingWorkStore', () => {
  let http: HttpTestingController;
  let store: PendingWorkStore;
  const user = signal<AuthenticatedUser | null>(null);
  const announced: string[] = [];
  let visibility: DocumentVisibilityState = 'visible';

  beforeEach(() => {
    vi.useFakeTimers();
    announced.length = 0;
    visibility = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    user.set(fakeUser());
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user } },
        { provide: LiveAnnouncer, useValue: { announce: (m: string) => announced.push(m) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PendingWorkStore);
    TestBed.tick();
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('uma consulta compartilhada entre consumidores, com atualização a cada 60 s', () => {
    const disconnectA = store.connect();
    const disconnectB = store.connect();
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    expect(store.status()).toBe('ready');
    expect(store.total()).toBe(2);

    vi.advanceTimersByTime(PENDING_WORK_POLL_MS);
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));

    disconnectA();
    disconnectB();
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS * 3);
    http.expectNone(URL);
  });

  it('pausa com a aba oculta e recarrega ao voltar quando os dados estão velhos', () => {
    store.connect();
    http.expectOne(URL).flush(pendingSummary());
    visibility = 'hidden';
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS * 2);
    http.expectNone(URL);

    visibility = 'visible';
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectOne(URL).flush(pendingSummary());
  });

  it('anuncia de forma polida só quando as contagens mudam', () => {
    store.connect();
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    expect(announced).toEqual([]);
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS);
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    expect(announced).toEqual([]);
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS);
    http.expectOne(URL).flush(pendingSummary([pendingItem({ count: 5, overdue: 1, severity: 'OVERDUE' })]));
    expect(announced).toEqual(['Suas pendências: 5 itens, 1 vencido.']);
  });

  it('falha na primeira carga mostra erro; depois mantém a última resposta boa', () => {
    store.connect();
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Indisponível' });
    expect(store.status()).toBe('error');
    store.refresh();
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS);
    http.expectOne(URL).flush(null, { status: 500, statusText: 'Erro' });
    expect(store.status()).toBe('ready');
    expect(store.total()).toBe(2);
  });

  it('descarta o estado ao sair da sessão', () => {
    store.connect();
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    user.set(null);
    TestBed.tick();
    expect(store.summary()).toBeNull();
    expect(store.status()).toBe('idle');
    vi.advanceTimersByTime(PENDING_WORK_POLL_MS);
    http.expectNone(URL);
  });
});
