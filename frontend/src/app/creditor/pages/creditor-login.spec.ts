import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CreditorLogin } from './creditor-login';

/** Entrada em etapas: código errado continua no código; etapa vencida recomeça pelo e-mail e senha. */
describe('entrada no portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', children: [{ path: 'entrar', component: CreditorLogin }] }])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function type(root: HTMLElement, selector: string, value: string): void {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('código errado mantém a etapa do código; etapa vencida volta ao e-mail e senha', async () => {
    const harness = await RouterTestingHarness.create('/credor/aurora/entrar');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const root = harness.routeNativeElement!;
    type(root, 'input[type="email"]', 'ana@banco-a.test');
    type(root, 'input[type="password"]', 'Senha-forte-123!');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/auth/login').flush({ status: 'MFA_REQUIRED', accessToken: null, accessTokenExpiresAt: null,
      stepToken: 'etapa', me: null });
    harness.detectChanges();
    expect(root.textContent).toContain('Código do autenticador');

    type(root, 'input[autocomplete="one-time-code"]', '123456');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/auth/mfa').flush({ code: 'auth.invalid_credentials', detail: 'x' },
      { status: 401, statusText: 'Unauthorized' });
    harness.detectChanges();
    expect(root.textContent).toContain('Código inválido');
    expect(root.querySelector('input[autocomplete="one-time-code"]')).not.toBeNull();

    type(root, 'input[autocomplete="one-time-code"]', '654321');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/auth/mfa').flush({ code: 'auth.invalid_token', detail: 'x' },
      { status: 401, statusText: 'Unauthorized' });
    harness.detectChanges();
    expect(root.textContent).toContain('A etapa expirou');
    expect(root.querySelector('input[type="email"]')).not.toBeNull();
  });
});
