import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authenticated, fakeJwt } from '../../../testing/auth-fixtures';
import { publicRequestContext } from '../http/http-context';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { UserActivity } from './user-activity';

const REFRESH = '/api/v1/auth/refresh';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;
  let router: Router;

  async function signIn(token = fakeJwt(600, 1)): Promise<void> {
    const result = firstValueFrom(auth.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
    http.expectOne('/api/v1/auth/login').flush(authenticated({ accessToken: token }));
    await result;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: UserActivity, useValue: { lastActivityAt: () => 0 } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('adiciona Bearer somente às chamadas da própria API', async () => {
    await signIn();
    client.get('/api/v1/users').subscribe();
    client.get('https://externo.example.com/api/v1/users').subscribe();
    client.get('/assets/config.json').subscribe();

    expect(http.expectOne('/api/v1/users').request.headers.get('Authorization')).toBe(`Bearer ${auth.accessToken()}`);
    expect(http.expectOne('https://externo.example.com/api/v1/users').request.headers.has('Authorization')).toBe(false);
    expect(http.expectOne('/assets/config.json').request.headers.has('Authorization')).toBe(false);
  });

  it('não envia o token para endpoints públicos de autenticação', async () => {
    await signIn();
    client.post('/api/v1/auth/password/forgot', {}, { context: publicRequestContext() }).subscribe();
    expect(http.expectOne('/api/v1/auth/password/forgot').request.headers.has('Authorization')).toBe(false);
  });

  it('em 401 faz um único refresh para requisições concorrentes e repete cada uma', async () => {
    await signIn();
    const oldToken = auth.accessToken();
    const a = firstValueFrom(client.get<{ v: string }>('/api/v1/users'));
    const b = firstValueFrom(client.get<{ v: string }>('/api/v1/roles'));

    http.expectOne('/api/v1/users').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/roles').flush(null, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();

    const refreshes = http.match(REFRESH);
    expect(refreshes.length).toBe(1);
    const newToken = fakeJwt(600, 2);
    refreshes[0].flush(authenticated({ accessToken: newToken }));
    await Promise.resolve();

    const retriedUsers = http.expectOne('/api/v1/users');
    const retriedRoles = http.expectOne('/api/v1/roles');
    expect(retriedUsers.request.headers.get('Authorization')).toBe(`Bearer ${newToken}`);
    expect(retriedRoles.request.headers.get('Authorization')).toBe(`Bearer ${newToken}`);
    expect(newToken).not.toBe(oldToken);
    retriedUsers.flush({ v: 'u' });
    retriedRoles.flush({ v: 'r' });

    expect(await a).toEqual({ v: 'u' });
    expect(await b).toEqual({ v: 'r' });
  });

  it('se o refresh falhar, limpa a sessão e vai ao login preservando a rota de retorno', async () => {
    await signIn();
    vi.spyOn(router, 'url', 'get').mockReturnValue('/admin/usuarios/42');
    const call = firstValueFrom(client.get('/api/v1/users/42'));
    http.expectOne('/api/v1/users/42').flush(null, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    http.expectOne(REFRESH).flush({ code: 'auth.invalid_token' }, { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toMatchObject({ status: 401 });
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { motivo: 'expirada', returnUrl: '/admin/usuarios/42' },
    });
  });

  it('não repete indefinidamente: 401 após a renovação encerra a sessão', async () => {
    await signIn();
    vi.spyOn(router, 'url', 'get').mockReturnValue('/');
    const call = firstValueFrom(client.get('/api/v1/users'));
    http.expectOne('/api/v1/users').flush(null, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    http.expectOne(REFRESH).flush(authenticated({ accessToken: fakeJwt(600, 3) }));
    await Promise.resolve();
    http.expectOne('/api/v1/users').flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toMatchObject({ status: 401 });
    http.expectNone(REFRESH);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('403 não dispara refresh', async () => {
    await signIn();
    const call = firstValueFrom(client.get('/api/v1/users'));
    http.expectOne('/api/v1/users').flush({ code: 'access.denied' }, { status: 403, statusText: 'Forbidden' });
    await expect(call).rejects.toMatchObject({ status: 403 });
    http.expectNone(REFRESH);
    expect(auth.isAuthenticated()).toBe(true);
  });
});
