import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthService } from '../auth/auth.service';
import { AuthenticatedUser } from '../auth/auth.models';
import { DEFAULT_UI_STATE, UiState } from './ui-state.models';
import { UiStateStore } from './ui-state.store';

const URL = '/api/v1/me/ui-state';

function server(overrides: Partial<UiState> = {}): UiState {
  return { ...DEFAULT_UI_STATE, ...overrides };
}

describe('UiStateStore', () => {
  let http: HttpTestingController;
  let store: UiStateStore;
  const user = signal<AuthenticatedUser | null>(null);

  beforeEach(() => {
    localStorage.clear();
    user.set(null);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: { user } }],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(UiStateStore);
    TestBed.tick();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  function login(state: UiState = server()): void {
    user.set(fakeUser());
    TestBed.tick();
    http.expectOne({ method: 'GET', url: URL }).flush(state);
  }

  it('anônimo: usa a aparência guardada no navegador e não chama o servidor', () => {
    store.update({ theme: 'DARK' });
    expect(store.state().theme).toBe('DARK');
    expect(store.status()).toBe('anonymous');
    expect(JSON.parse(localStorage.getItem('gc.ui.appearance') as string)).toEqual({ theme: 'DARK', density: 'COMFORTABLE' });
  });

  it('carrega o estado do servidor ao autenticar', () => {
    login(server({ theme: 'DARK', screens: { 'admin.users': { version: 1, seenAt: 'x' } } }));
    expect(store.ready()).toBe(true);
    expect(store.state().theme).toBe('DARK');
    expect(store.state().screens['admin.users'].version).toBe(1);
  });

  it('agrupa alterações seguidas em um único PATCH', async () => {
    vi.useFakeTimers();
    login();
    store.update({ theme: 'DARK' });
    store.markScreenSeen('admin.users', 1);
    store.setChecklistItem('admin.creditor', true);
    expect(store.state().theme).toBe('DARK'); // otimista

    await vi.advanceTimersByTimeAsync(1000);
    const req = http.expectOne({ method: 'PATCH', url: URL });
    expect(req.request.body).toEqual({
      theme: 'DARK',
      screens: [{ screenKey: 'admin.users', version: 1 }],
      checklist: [{ itemId: 'admin.creditor', done: true }],
    });
    req.flush(server({ theme: 'DARK', screens: { 'admin.users': { version: 1, seenAt: 's' } } }));
  });

  it('mantém o que foi alterado durante o envio ao receber a resposta', async () => {
    vi.useFakeTimers();
    login();
    store.update({ theme: 'DARK' });
    await vi.advanceTimersByTimeAsync(1000);
    const first = http.expectOne({ method: 'PATCH', url: URL });

    store.update({ density: 'COMPACT' });
    first.flush(server({ theme: 'DARK' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(store.state().density).toBe('COMPACT');

    await vi.advanceTimersByTimeAsync(1000);
    const second = http.expectOne({ method: 'PATCH', url: URL });
    expect(second.request.body).toEqual({ density: 'COMPACT' });
    second.flush(server({ theme: 'DARK', density: 'COMPACT' }));
  });

  it('não reenvia telas já vistas na mesma versão', async () => {
    vi.useFakeTimers();
    login(server({ screens: { 'admin.users': { version: 2, seenAt: 'x' } } }));
    store.markScreenSeen('admin.users', 2);
    store.markScreenSeen('admin.users', 1);
    await vi.advanceTimersByTimeAsync(1000);
    http.expectNone({ method: 'PATCH', url: URL });
  });

  it('flush envia imediatamente (usado antes do logout)', async () => {
    login();
    store.recordTour('app.welcome', 1, 'COMPLETED');
    const done = store.flush();
    http.expectOne({ method: 'PATCH', url: URL }).flush(server());
    await done;
  });

  it('falha do servidor não desfaz a alteração local e tenta de novo', async () => {
    vi.useFakeTimers();
    login();
    store.update({ hintsEnabled: false });
    await vi.advanceTimersByTimeAsync(1000);
    http.expectOne({ method: 'PATCH', url: URL }).flush(null, { status: 503, statusText: 'Unavailable' });
    await vi.advanceTimersByTimeAsync(0);
    expect(store.state().hintsEnabled).toBe(false);

    await vi.advanceTimersByTimeAsync(15_000);
    const retry = http.expectOne({ method: 'PATCH', url: URL });
    expect(retry.request.body).toEqual({ hintsEnabled: false });
    retry.flush(server({ hintsEnabled: false }));
  });

  it('ao trocar de usuário descarta o estado anterior', () => {
    login(server({ theme: 'DARK', tours: { 'app.welcome': { version: 1, outcome: 'COMPLETED', recordedAt: 'x' } } }));
    user.set(null);
    TestBed.tick();
    expect(store.status()).toBe('anonymous');
    expect(store.state().tours).toEqual({});
  });
});
