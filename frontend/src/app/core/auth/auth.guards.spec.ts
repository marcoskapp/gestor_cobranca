import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthenticatedUser } from './auth.models';
import { authGuard, guestGuard, permissionGuard, stepGuard } from './auth.guards';
import { AuthService, PendingStep } from './auth.service';

@Component({ template: 'ok' })
class Page {}

describe('guards de rota', () => {
  const user = signal<AuthenticatedUser | null>(null);
  const pending = signal<PendingStep | null>(null);
  const authStub = {
    user,
    pendingStep: pending,
    isAuthenticated: () => user() !== null,
    hasAnyPermission: (required: readonly string[]) =>
      required.length === 0 || required.some((p) => (user()?.permissions ?? []).includes(p)),
  };

  beforeEach(() => {
    user.set(null);
    pending.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub },
        provideRouter([
          { path: 'login', component: Page, canActivate: [guestGuard] },
          { path: 'login/mfa', component: Page, canActivate: [stepGuard('MFA_REQUIRED')] },
          { path: 'acesso-negado', component: Page },
          {
            path: '',
            canActivate: [authGuard],
            children: [
              { path: 'inicio', component: Page },
              {
                path: 'admin/usuarios',
                component: Page,
                canActivate: [permissionGuard],
                data: { permissions: ['USER_VIEW'] },
              },
            ],
          },
        ]),
      ],
    });
  });

  let harness: RouterTestingHarness | null = null;

  afterEach(() => (harness = null));

  async function navigate(url: string): Promise<string> {
    harness ??= await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return TestBed.inject(Router).url;
  }

  it('authGuard leva ao login com returnUrl quando não autenticado', async () => {
    expect(await navigate('/admin/usuarios?page=2')).toBe('/login?returnUrl=%2Fadmin%2Fusuarios%3Fpage%3D2');
  });

  it('authGuard libera usuário autenticado', async () => {
    user.set(fakeUser());
    expect(await navigate('/inicio')).toBe('/inicio');
  });

  it('permissionGuard bloqueia sem a permissão e mostra acesso negado', async () => {
    user.set(fakeUser({ permissions: ['CREDITOR_VIEW'] }));
    expect(await navigate('/admin/usuarios')).toBe('/acesso-negado');
  });

  it('permissionGuard libera com a permissão', async () => {
    user.set(fakeUser({ permissions: ['USER_VIEW'] }));
    expect(await navigate('/admin/usuarios')).toBe('/admin/usuarios');
  });

  it('guestGuard redireciona autenticado para o início', () => {
    user.set(fakeUser());
    const result = TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never));
    expect(result instanceof UrlTree && result.toString()).toBe('/');
  });

  it('stepGuard exige o step token da etapa correspondente', async () => {
    expect(await navigate('/login/mfa')).toBe('/login');
    pending.set({ status: 'MFA_ENROLLMENT_REQUIRED', stepToken: 's' });
    expect(await navigate('/login/mfa')).toBe('/login');
    pending.set({ status: 'MFA_REQUIRED', stepToken: 's' });
    expect(await navigate('/login/mfa')).toBe('/login/mfa');
  });
});
