import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authenticated, step } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { UserActivity } from '../../../core/auth/user-activity';
import { Login } from './login';

describe('Login', () => {
  let http: HttpTestingController;
  let router: Router;

  async function render(inputs: Record<string, string> = {}) {
    const fixture = TestBed.createComponent(Login);
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    await fixture.whenStable();
    return fixture;
  }

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(selector);
    if (!input) {
      throw new Error(`campo ${selector} não encontrado`);
    }
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: UserActivity, useValue: { lastActivityAt: () => 0 } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('pré-preenche a assessoria lembrada', async () => {
    localStorage.setItem('gc.tenant', 'minha-assessoria');
    const fixture = await render();
    const tenant = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[formcontrolname="tenant"]');
    expect(tenant?.value).toBe('minha-assessoria');
  });

  it('não envia formulário inválido e mostra os erros', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    await fixture.whenStable();
    http.expectNone('/api/v1/auth/login');
    expect(el.textContent).toContain('Campo obrigatório.');
  });

  it('autentica e segue para a rota de retorno', async () => {
    const fixture = await render({ returnUrl: '/admin/usuarios' });
    const el = fixture.nativeElement as HTMLElement;
    type(el, 'input[formcontrolname="tenant"]', 'assessoria-teste');
    type(el, 'input[formcontrolname="email"]', 'maria@teste.com.br');
    type(el, 'input[type="password"]', 'Segredo#2026x');
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();

    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ tenant: 'assessoria-teste', email: 'maria@teste.com.br', password: 'Segredo#2026x' });
    req.flush(authenticated());
    await fixture.whenStable();

    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/admin/usuarios');
  });

  it('direciona para a verificação de MFA', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    type(el, 'input[formcontrolname="tenant"]', 'assessoria-teste');
    type(el, 'input[formcontrolname="email"]', 'maria@teste.com.br');
    type(el, 'input[type="password"]', 'Segredo#2026x');
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    http.expectOne('/api/v1/auth/login').flush(step('MFA_REQUIRED'));
    await fixture.whenStable();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login/mfa');
  });

  it('em credenciais inválidas mostra mensagem neutra e limpa a senha', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    type(el, 'input[formcontrolname="tenant"]', 'assessoria-teste');
    type(el, 'input[formcontrolname="email"]', 'maria@teste.com.br');
    type(el, 'input[type="password"]', 'errada');
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'auth.invalid_credentials', detail: 'Credenciais inválidas' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    const alert = el.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Credenciais inválidas');
    expect(el.querySelector<HTMLInputElement>('input[type="password"]')?.value).toBe('');
  });

  it('mostra aviso de sessão expirada', async () => {
    const fixture = await render({ motivo: 'expirada' });
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="status"]')?.textContent).toContain('Sua sessão expirou');
  });
});
