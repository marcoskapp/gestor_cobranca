import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authenticated, fakeJwt, step } from '../../../testing/auth-fixtures';
import { AuthService, RefreshOutcome } from './auth.service';
import { CrossTabCoordinator } from './cross-tab';
import { TenantMemory } from './tenant-memory';
import { UserActivity } from './user-activity';

const LOGIN = '/api/v1/auth/login';
const REFRESH = '/api/v1/auth/refresh';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;
  let lastActivity: number;

  beforeEach(() => {
    lastActivity = 0;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: UserActivity, useValue: { lastActivityAt: () => lastActivity } },
      ],
    });
    // Web Locks não existe em jsdom: o coordenador executa a operação diretamente
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    localStorage.clear();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  describe('login', () => {
    it('autentica, mantém o token só em memória e lembra apenas o slug do tenant', async () => {
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      const result = firstValueFrom(service.login({ tenant: ' Assessoria-Teste ', email: 'maria@teste.com.br', password: 'x' }));

      const req = http.expectOne(LOGIN);
      expect(req.request.body).toEqual({ tenant: 'assessoria-teste', email: 'maria@teste.com.br', password: 'x' });
      expect(req.request.withCredentials).toBe(true);
      req.flush(authenticated());

      expect(await result).toBe('AUTHENTICATED');
      expect(service.isAuthenticated()).toBe(true);
      expect(service.accessToken()).toBeTruthy();
      expect(service.user()?.name).toBe('Maria Operadora');
      // único valor persistido: o identificador da assessoria
      expect(setItem).toHaveBeenCalledTimes(1);
      expect(setItem).toHaveBeenCalledWith('gc.tenant', 'assessoria-teste');
      expect(JSON.stringify({ ...localStorage })).not.toContain(service.accessToken() as string);
      expect(sessionStorage.length).toBe(0);
    });

    it.each([
      ['MFA_REQUIRED', '/login/mfa'],
      ['MFA_ENROLLMENT_REQUIRED', '/login/mfa/cadastro'],
      ['PASSWORD_CHANGE_REQUIRED', '/login/senha-expirada'],
    ] as const)('em %s guarda o step token em memória e direciona para %s', async (status, route) => {
      const result = firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush(step(status, 'step-abc'));

      expect(await result).toBe(status);
      expect(service.isAuthenticated()).toBe(false);
      expect(service.accessToken()).toBeNull();
      expect(service.pendingStep()).toEqual({ status, stepToken: 'step-abc' });

      await service.continueFlow(status);
      expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith(route);
      expect(sessionStorage.length).toBe(0);
      expect(Object.values({ ...localStorage })).not.toContain('step-abc');
    });

    it('envia o step token na verificação de MFA e conclui a autenticação', async () => {
      firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush(step('MFA_REQUIRED', 'step-mfa'));

      const result = firstValueFrom(service.verifyMfa('123456'));
      const req = http.expectOne('/api/v1/auth/mfa/verify');
      expect(req.request.body).toEqual({ stepToken: 'step-mfa', code: '123456' });
      req.flush(authenticated());

      expect(await result).toBe('AUTHENTICATED');
      expect(service.pendingStep()).toBeNull();
      expect(service.isAuthenticated()).toBe(true);
    });

    it('recusa etapa que não corresponde ao step token pendente', async () => {
      await expect(firstValueFrom(service.verifyMfa('123456'))).rejects.toThrow();
      http.expectNone('/api/v1/auth/mfa/verify');
    });

    it('troca de senha expirada limpa a etapa e exige novo login', async () => {
      firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush(step('PASSWORD_CHANGE_REQUIRED', 'step-pwd'));

      const done = firstValueFrom(service.changeExpiredPassword('NovaSenha#2026'));
      const req = http.expectOne('/api/v1/auth/password/expired');
      expect(req.request.body).toEqual({ stepToken: 'step-pwd', newPassword: 'NovaSenha#2026' });
      req.flush(null, { status: 204, statusText: 'No Content' });
      await done;

      expect(service.pendingStep()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
    });

    it('após autenticar vai para a rota de retorno segura', async () => {
      service.setReturnUrl('/admin/usuarios?page=2');
      firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush(authenticated());
      await service.continueFlow('AUTHENTICATED');
      expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/admin/usuarios?page=2');
    });

    it('ignora rota de retorno externa', async () => {
      service.setReturnUrl('//malicioso.example/roubo');
      await service.continueFlow('AUTHENTICATED');
      expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/');
    });
  });

  describe('refresh', () => {
    it('chamadas concorrentes compartilham uma única requisição de refresh', async () => {
      const outcomes: Promise<RefreshOutcome>[] = [
        firstValueFrom(service.refresh()),
        firstValueFrom(service.refresh()),
        firstValueFrom(service.refresh()),
      ];
      await Promise.resolve();
      const requests = http.match(REFRESH);
      expect(requests.length).toBe(1);
      expect(requests[0].request.withCredentials).toBe(true);
      expect(requests[0].request.headers.has('Authorization')).toBe(false);
      requests[0].flush(authenticated());

      expect(await Promise.all(outcomes)).toEqual(['renewed', 'renewed', 'renewed']);
      expect(service.isAuthenticated()).toBe(true);

      // terminada a anterior, uma nova renovação gera nova requisição
      const next = firstValueFrom(service.refresh());
      await Promise.resolve();
      http.expectOne(REFRESH).flush(authenticated());
      expect(await next).toBe('renewed');
    });

    it('refresh serializado entre abas pela Web Locks API', async () => {
      const coordinator = TestBed.inject(CrossTabCoordinator);
      const lock = vi.spyOn(coordinator, 'withRefreshLock');
      const outcome = firstValueFrom(service.refresh());
      await Promise.resolve();
      http.expectOne(REFRESH).flush(authenticated());
      await outcome;
      expect(lock).toHaveBeenCalledTimes(1);
    });

    it('401 no refresh encerra a sessão local', async () => {
      const outcome = firstValueFrom(service.refresh());
      await Promise.resolve();
      http.expectOne(REFRESH).flush({ code: 'auth.invalid_token' }, { status: 401, statusText: 'Unauthorized' });
      expect(await outcome).toBe('ended');
      expect(service.isAuthenticated()).toBe(false);
    });

    it('falha de rede no refresh não encerra a sessão', async () => {
      const outcome = firstValueFrom(service.refresh());
      await Promise.resolve();
      http.expectOne(REFRESH).error(new ProgressEvent('error'), { status: 0 });
      expect(await outcome).toBe('unavailable');
    });

    it('restaura a sessão ao iniciar a aplicação (refresh silencioso)', async () => {
      const restore = service.restoreSession();
      await Promise.resolve();
      http.expectOne(REFRESH).flush(authenticated());
      await restore;
      expect(service.isAuthenticated()).toBe(true);
    });

    it('sem cookie de refresh, a inicialização segue sem sessão', async () => {
      const restore = service.restoreSession();
      await Promise.resolve();
      http.expectOne(REFRESH).flush({ code: 'auth.invalid_token' }, { status: 401, statusText: 'Unauthorized' });
      await expect(restore).resolves.toBeUndefined();
      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('renovação proativa', () => {
    beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] }));

    async function loginWith(lifetimeSeconds: number): Promise<void> {
      const result = firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush(authenticated({ accessToken: fakeJwt(lifetimeSeconds) }, lifetimeSeconds));
      await result;
    }

    it('renova cerca de 60 s antes da expiração quando houve atividade', async () => {
      await loginWith(600);
      lastActivity = Date.now() + 1000;

      await vi.advanceTimersByTimeAsync(539_000);
      http.expectNone(REFRESH);

      await vi.advanceTimersByTimeAsync(1_000);
      const req = http.expectOne(REFRESH);
      req.flush(authenticated());
      expect(service.isAuthenticated()).toBe(true);
    });

    it('não renova sessão ociosa (respeita o tempo de inatividade do servidor)', async () => {
      await loginWith(600);
      lastActivity = 0;
      await vi.advanceTimersByTimeAsync(600_000);
      http.expectNone(REFRESH);
      expect(service.isTokenExpiring()).toBe(true);
    });

    it('usa a vida útil do token (exp - iat), imune ao relógio local adiantado', async () => {
      // relógio do computador 2 h à frente: expiresAt do servidor já pareceria vencido
      vi.setSystemTime(Date.now() + 2 * 3600_000);
      const result = firstValueFrom(service.login({ tenant: 'assessoria-teste', email: 'a@b.co', password: 'x' }));
      http.expectOne(LOGIN).flush({
        ...authenticated({ accessToken: fakeJwt(600) }),
        accessTokenExpiresAt: new Date(Date.now() - 3600_000).toISOString(),
      });
      await result;
      lastActivity = Date.now() + 1;
      await vi.advanceTimersByTimeAsync(530_000);
      http.expectNone(REFRESH);
      await vi.advanceTimersByTimeAsync(10_000);
      http.expectOne(REFRESH).flush(authenticated());
    });

    it('logout cancela a renovação agendada e avisa as outras abas', async () => {
      await loginWith(600);
      const broadcast = vi.spyOn(TestBed.inject(CrossTabCoordinator), 'broadcast');
      const done = firstValueFrom(service.logout(), { defaultValue: undefined });
      const req = http.expectOne('/api/v1/me/logout');
      expect(req.request.withCredentials).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await done;

      expect(service.isAuthenticated()).toBe(false);
      expect(service.accessToken()).toBeNull();
      expect(broadcast).toHaveBeenCalledWith({ type: 'logout' });
      lastActivity = Date.now() + 1;
      await vi.advanceTimersByTimeAsync(700_000);
      http.expectNone(REFRESH);
    });
  });

  it('TenantMemory rejeita valores que não são slug', () => {
    const memory = TestBed.inject(TenantMemory);
    memory.remember('<script>');
    expect(memory.read()).toBe('');
    localStorage.setItem('gc.tenant', 'Valor Inválido');
    expect(memory.read()).toBe('');
  });
});
