import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { PageResponse } from '../../../../shared/models/page';
import { RoleView, UserView } from '../../data/admin.models';
import { UserList } from './user-list';

function user(overrides: Partial<UserView>): UserView {
  return {
    id: 'u-1',
    name: 'Ana Souza',
    email: 'ana@teste.com.br',
    status: 'ACTIVE',
    locked: false,
    lockedUntil: null,
    mfaEnabled: true,
    lastLoginAt: '2026-10-20T13:45:00Z',
    passwordChangedAt: null,
    businessUnitId: null,
    roleIds: ['r-1'],
    roleCodes: ['OPERADOR'],
    allCreditors: true,
    creditorIds: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

const roles: RoleView[] = [
  { id: 'r-1', code: 'OPERADOR', name: 'Operador', description: null, systemRole: true, active: true, permissions: ['CASE_WORK'] },
];

function page(content: UserView[], total = content.length, index = 0, size = 20): PageResponse<UserView> {
  return { content, page: index, size, totalElements: total, totalPages: Math.ceil(total / size) };
}

describe('UserList', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>(['USER_VIEW']);

  beforeEach(() => {
    permissions.set(['USER_VIEW']);
    TestBed.configureTestingModule({
      imports: [UserList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function usersRequest(): TestRequest {
    return http.expectOne((r) => r.url === '/api/v1/users');
  }

  /** Os resources mantêm a aplicação "instável" até as requisições responderem: não aguarde antes do flush. */
  function render() {
    const fixture = TestBed.createComponent(UserList);
    fixture.detectChanges();
    http.expectOne('/api/v1/roles').flush(roles);
    return fixture;
  }

  it('carrega a primeira página e exibe os usuários com perfis e situação', async () => {
    const fixture = render();
    const req = usersRequest();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(page([user({}), user({ id: 'u-2', name: 'Bruno Lima', status: 'PENDING_ACTIVATION', locked: true, mfaEnabled: false })], 2));
    await fixture.whenStable();

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Ana Souza');
    expect(rows[0].textContent).toContain('Operador');
    expect(rows[0].textContent).toContain('Ativo');
    expect(rows[1].textContent).toContain('Aguardando ativação');
    expect(rows[1].textContent).toContain('Bloqueado');
  });

  it('esconde "Novo usuário" sem USER_MANAGE e mostra com a permissão', async () => {
    const fixture = render();
    usersRequest().flush(page([]));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('Novo usuário');

    permissions.set(['USER_VIEW', 'USER_MANAGE']);
    await fixture.whenStable();
    expect(el.textContent).toContain('Novo usuário');
  });

  it('mostra estado vazio', async () => {
    const fixture = render();
    usersRequest().flush(page([]));
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nenhum usuário encontrado');
  });

  it('filtra no servidor e volta para a primeira página', async () => {
    const fixture = render();
    usersRequest().flush(page([user({})], 45));
    fixture.detectChanges();

    // vai para a página 2 e então filtra: a consulta deve voltar à página 0
    const component = fixture.componentInstance as unknown as { query: { setPage(p: { page: number; size: number }): void } };
    component.query.setPage({ page: 2, size: 20 });
    fixture.detectChanges();
    expect(usersRequest().request.params.get('page')).toBe('2');

    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[formcontrolname="q"]');
    input!.value = 'ana';
    input!.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350)); // debounce do filtro
    fixture.detectChanges();

    const req = usersRequest();
    expect(req.request.params.get('q')).toBe('ana');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([user({})], 1));
  });

  it('em falha oferece tentar novamente', async () => {
    const fixture = render();
    usersRequest().flush({ code: 'internal_error', requestId: 'r-1' }, { status: 500, statusText: 'Error' });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Não foi possível carregar os dados.');

    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Tentar novamente'))?.click();
    fixture.detectChanges();
    usersRequest().flush(page([user({})]));
    await fixture.whenStable();
    expect(el.querySelectorAll('tr.mat-mdc-row').length).toBe(1);
  });
});
