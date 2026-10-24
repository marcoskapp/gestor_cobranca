import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { AuthService } from '../../core/auth/auth.service';
import { portalInterceptor } from './portal.interceptor';
import { PortalSession } from './portal-session';

describe('portalInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let session: PortalSession;
  const auth = { accessToken: () => 'token-interno', isTokenExpiring: () => false, refresh: vi.fn(), endSession: vi.fn() };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, portalInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    session = TestBed.inject(PortalSession);
  });

  afterEach(() => http.verify());

  it('anexa só o token do portal nas chamadas do devedor e nunca o token interno', async () => {
    session.start('banco-a', { accessToken: 'token-portal', expiresAt: new Date(Date.now() + 600_000).toISOString(), sessionId: 's1' });
    const call = firstValueFrom(client.get('/api/portal/v1/me/debts'));
    const req = http.expectOne('/api/portal/v1/me/debts');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-portal');
    req.flush({});
    await call;

    const site = firstValueFrom(client.get('/api/portal/v1/sites/banco-a'));
    const pub = http.expectOne('/api/portal/v1/sites/banco-a');
    expect(pub.request.headers.has('Authorization')).toBe(false);
    pub.flush({});
    await site;

    const internal = firstValueFrom(client.get('/api/v1/creditors'));
    const intReq = http.expectOne('/api/v1/creditors');
    expect(intReq.request.headers.get('Authorization')).toBe('Bearer token-interno');
    intReq.flush([]);
    await internal;
  });

  it('401 encerra a sessão do portal e volta à entrada', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    session.start('banco-a', { accessToken: 't', expiresAt: new Date(Date.now() + 600_000).toISOString(), sessionId: 's1' });
    const call = firstValueFrom(client.get('/api/portal/v1/me/session'));
    http.expectOne('/api/portal/v1/me/session').flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(call).rejects.toBeTruthy();
    expect(session.token()).toBeNull();
    expect(session.endedReason()).toBe('expired');
    expect(navigate).toHaveBeenCalledWith(['/portal', 'banco-a']);
  });

  it('renova o token perto do vencimento antes da chamada', async () => {
    session.start('banco-a', { accessToken: 'velho', expiresAt: new Date(Date.now() + 30_000).toISOString(), sessionId: 's1' });
    const call = firstValueFrom(client.get('/api/portal/v1/me/agreements'));
    const refresh = http.expectOne('/api/portal/v1/me/session/refresh');
    expect(refresh.request.headers.get('Authorization')).toBe('Bearer velho');
    refresh.flush({ accessToken: 'novo', expiresAt: new Date(Date.now() + 600_000).toISOString(), sessionId: 's1' });
    const req = http.expectOne('/api/portal/v1/me/agreements');
    expect(req.request.headers.get('Authorization')).toBe('Bearer novo');
    req.flush([]);
    await call;
  });
});
