import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { provideAppLocale } from './core/config/app-locale';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeUser } from '../testing/auth-fixtures';
import { routes } from './app.routes';
import { AuthenticatedUser } from './core/auth/auth.models';
import { AuthService } from './core/auth/auth.service';

/** Garante que a árvore de rotas leva cada endereço à moldura correta. */
describe('rotas da aplicação', () => {
  const user = signal<AuthenticatedUser | null>(null);

  beforeEach(() => {
    user.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user,
            pendingStep: signal(null),
            isAuthenticated: () => user() !== null,
            hasPermission: (p: string) => (user()?.permissions ?? []).includes(p),
            hasAnyPermission: (ps: readonly string[]) =>
              ps.length === 0 || ps.some((p) => (user()?.permissions ?? []).includes(p)),
            rememberedTenant: () => '',
            logout: vi.fn(),
          },
        },
      ],
    });
  });

  it('"/" autenticado abre o shell com a página inicial', async () => {
    user.set(fakeUser());
    const harness = await RouterTestingHarness.create('/');
    expect(TestBed.inject(Router).url).toBe('/');
    expect(harness.routeNativeElement?.textContent).toContain('Acesso rápido');
    expect(document.querySelector('app-shell')).not.toBeNull();
  }, 20_000); // primeiro teste do arquivo: inclui o carregamento sob demanda do shell

  it('"/" sem sessão vai ao login', async () => {
    await RouterTestingHarness.create('/');
    expect(TestBed.inject(Router).url).toBe('/login?returnUrl=%2F');
  });

  it('links de e-mail abrem as telas públicas mesmo sem sessão', async () => {
    const harness = await RouterTestingHarness.create('/ativar-conta?token=abc');
    expect(document.querySelector('app-auth-layout')).not.toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('Ativar conta');
    await harness.navigateByUrl('/redefinir-senha?token=abc');
    expect(harness.routeNativeElement?.textContent).toContain('Redefinir senha');
  });

  it('rota desconhecida autenticada mostra página não encontrada', async () => {
    user.set(fakeUser());
    const harness = await RouterTestingHarness.create('/nao-existe');
    expect(harness.routeNativeElement?.textContent).toContain('Página não encontrada');
  });
  it('devedores e compliance exigem permissão: sem ela, acesso negado', async () => {
    user.set(fakeUser({ permissions: ['USER_VIEW'] }));
    const harness = await RouterTestingHarness.create('/devedores');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
    await harness.navigateByUrl('/compliance/retencao');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
    expect(harness.routeNativeElement?.textContent).toContain('Acesso negado');
  }, 20_000);

  it('com DEBTOR_VIEW abre a busca de devedores; títulos aceitam também CASE_VIEW', async () => {
    user.set(fakeUser({ permissions: ['DEBTOR_VIEW'] }));
    const harness = await RouterTestingHarness.create('/devedores');
    expect(TestBed.inject(Router).url).toBe('/devedores');
    expect(harness.routeNativeElement?.textContent).toContain('Devedores');
    user.set(fakeUser({ permissions: ['CASE_VIEW'] }));
    await harness.navigateByUrl('/titulos');
    expect(TestBed.inject(Router).url).toBe('/titulos');
  }, 20_000);

  it('estação, gestão da cobrança e negociação exigem as permissões dos controllers', async () => {
    user.set(fakeUser({ permissions: ['CASE_VIEW'] }));
    const harness = await RouterTestingHarness.create('/cobranca/fila');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
    await harness.navigateByUrl('/cobranca/tabulacoes');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
    await harness.navigateByUrl('/negociacao/alcadas');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
    user.set(fakeUser({ permissions: ['POLICY_VIEW'] }));
    await harness.navigateByUrl('/negociacao/simulador');
    expect(TestBed.inject(Router).url).toBe('/acesso-negado');
  }, 20_000);

  it('"/cobranca" leva à estação de cobrança para quem trabalha casos', async () => {
    user.set(fakeUser({ permissions: ['CASE_WORK', 'CASE_VIEW'] }));
    const harness = await RouterTestingHarness.create('/cobranca');
    expect(TestBed.inject(Router).url).toBe('/cobranca/fila');
    expect(harness.routeNativeElement?.textContent).toContain('Estação de cobrança');
  }, 20_000);
});
