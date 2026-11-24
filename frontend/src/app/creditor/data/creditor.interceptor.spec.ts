import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { CreditorLoginResponse } from './creditor.models';
import { CreditorSession } from './creditor-session';
import { creditorInterceptor } from './creditor.interceptor';

const ME = { userId: 'u1', name: 'Ana', email: 'ana@b.test', role: 'FINANCEIRO', roleLabel: 'Financeiro', permissions: [],
  creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };

function grant(token: string): CreditorLoginResponse {
  return { status: 'AUTHENTICATED', accessToken: token, accessTokenExpiresAt: new Date(Date.now() + 600_000).toISOString(),
    stepToken: null, me: ME };
}

const settle = () => new Promise((resolve) => setTimeout(resolve));

describe('creditorInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let session: CreditorSession;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([creditorInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    session = TestBed.inject(CreditorSession);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('anexa o token do credor só às chamadas autenticadas do portal do credor', () => {
    session.start(grant('tok-credor'));
    client.get('/api/creditor/v1/me').subscribe();
    client.post('/api/creditor/v1/auth/login', {}).subscribe();
    client.get('/api/creditor/v1/sites/aurora').subscribe();
    client.get('/api/v1/creditors').subscribe();
    client.get('/api/portal/v1/me/session').subscribe();
    expect(http.expectOne('/api/creditor/v1/me').request.headers.get('Authorization')).toBe('Bearer tok-credor');
    expect(http.expectOne('/api/creditor/v1/auth/login').request.headers.has('Authorization')).toBe(false);
    expect(http.expectOne('/api/creditor/v1/sites/aurora').request.headers.has('Authorization')).toBe(false);
    expect(http.expectOne('/api/v1/creditors').request.headers.has('Authorization')).toBe(false);
    expect(http.expectOne('/api/portal/v1/me/session').request.headers.has('Authorization')).toBe(false);
  });

  it('401 renova uma vez pelo cookie e repete; sem sessão, volta à entrada', async () => {
    session.start(grant('velho'));
    const results: unknown[] = [];
    client.get('/api/creditor/v1/me').subscribe((r) => results.push(r));
    http.expectOne('/api/creditor/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/creditor/v1/auth/refresh').flush(grant('novo'));
    await settle();
    const retry = http.expectOne('/api/creditor/v1/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer novo');
    retry.flush(ME);
    expect(results).toHaveLength(1);

    client.get('/api/creditor/v1/me').subscribe({ error: () => undefined });
    http.expectOne('/api/creditor/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/creditor/v1/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();
    expect(session.token()).toBeNull();
    expect(session.endedReason()).toBe('expired');
    expect(router.navigate).toHaveBeenCalledWith(['/credor', 'aurora', 'entrar'], { replaceUrl: true });
  });

  it('falha passageira na renovação (503) mantém a sessão local e não sai', async () => {
    session.start(grant('velho'));
    client.get('/api/creditor/v1/me').subscribe({ error: () => undefined });
    http.expectOne('/api/creditor/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/creditor/v1/auth/refresh').flush(null, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(session.token()).toBe('velho');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('dois 401 ao mesmo tempo renovam uma vez só', async () => {
    session.start(grant('velho'));
    client.get('/api/creditor/v1/me').subscribe();
    client.get('/api/creditor/v1/me').subscribe();
    const first = http.match('/api/creditor/v1/me');
    first.forEach((r) => r.flush(null, { status: 401, statusText: 'Unauthorized' }));
    http.expectOne('/api/creditor/v1/auth/refresh').flush(grant('novo'));
    await settle();
    http.match('/api/creditor/v1/me').forEach((r) => {
      expect(r.request.headers.get('Authorization')).toBe('Bearer novo');
      r.flush(ME);
    });
  });
});
