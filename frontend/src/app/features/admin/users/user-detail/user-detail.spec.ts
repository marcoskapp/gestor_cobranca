import { render, setup, text } from '../../../../../testing/component-harness';
import { UserView } from '../../data/admin.models';
import { UserDetail } from './user-detail';

const user: UserView = {
  id: 'u-2',
  name: 'Carla Operadora',
  email: 'carla@teste.com.br',
  status: 'ACTIVE',
  locked: false,
  lockedUntil: null,
  mfaEnabled: true,
  lastLoginAt: null,
  passwordChangedAt: null,
  businessUnitId: null,
  roleIds: [],
  roleCodes: [],
  allCreditors: false,
  creditorIds: ['c-1', 'c-2'],
  createdAt: '2026-01-10T12:00:00Z',
};

describe('UserDetail', () => {
  const requested: string[] = [];
  const respond = (url: string) => {
    requested.push(url);
    if (url === '/api/v1/users/u-2') return user;
    if (url.endsWith('/sessions') || url === '/api/v1/roles' || url === '/api/v1/organization/units') return [];
    if (url.startsWith('/api/v1/creditors/')) {
      const id = url.split('/').pop();
      return { id, legalName: id === 'c-1' ? 'Banco Horizonte S.A.' : 'Financeira Aurora S.A.', tradeName: null };
    }
    return undefined;
  };
  const items = (el: HTMLElement) => [...el.querySelectorAll('[ngbdropdownitem]')].map((b) => b.textContent?.trim());

  beforeEach(() => (requested.length = 0));

  it('com CREDITOR_VIEW, mostra os nomes dos credores do escopo e oferece alterar o escopo', async () => {
    const { http } = setup(UserDetail, ['USER_VIEW', 'USER_MANAGE', 'CREDITOR_VIEW']);
    const fixture = await render(UserDetail, http, respond, { id: 'u-2' });
    expect(text(fixture)).toContain('Banco Horizonte S.A.; Financeira Aurora S.A.');
    expect(items(fixture.nativeElement)).toContain('Alterar escopo de credores');
  });

  it('sem CREDITOR_VIEW (ex.: administrador da plataforma), mostra só a contagem e não oferece alterar o escopo', async () => {
    const { http, notifications } = setup(UserDetail, ['USER_VIEW', 'USER_MANAGE']);
    const fixture = await render(UserDetail, http, respond, { id: 'u-2' });
    expect(text(fixture)).toContain('2 credores');
    expect(items(fixture.nativeElement)).toContain('Alterar perfis');
    expect(items(fixture.nativeElement)).not.toContain('Alterar escopo de credores');
    expect(requested.some((u) => u.startsWith('/api/v1/creditors'))).toBe(false);
    expect(notifications.error).toEqual([]);
  });
});
