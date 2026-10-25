import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { button, render, setup, text } from '../../../../../testing/component-harness';
import { UserForm } from './user-form';

const roles = [
  { id: 'r-1', code: 'ADMIN', name: 'Administrador', active: true },
  { id: 'r-2', code: 'NEGOCIADOR', name: 'Negociador', active: true },
  { id: 'r-3', code: 'ANTIGO', name: 'Antigo', active: false },
];

describe('UserForm (criação)', () => {
  function create() {
    const close = vi.fn();
    const harness = setup(UserForm, ['USER_MANAGE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { user: null } },
      { provide: DialogRef, useValue: { close } },
    ]);
    return { ...harness, close };
  }

  it('perfis ativos como caixas de seleção; exige ao menos um e envia os escolhidos', async () => {
    const { http } = create();
    const fixture = await render(UserForm, http, (url) => (url.endsWith('/roles') ? roles : url.endsWith('s') ? [] : undefined));
    const el = fixture.nativeElement as HTMLElement;
    const boxes = [...el.querySelectorAll<HTMLInputElement>('input[id^="user-form-role-"]')];
    expect(boxes.map((b) => b.id)).toEqual(['user-form-role-r-1', 'user-form-role-r-2']);

    const name = el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    name.value = 'Ana Souza';
    name.dispatchEvent(new Event('input'));
    const email = el.querySelector<HTMLInputElement>('input[formcontrolname="email"]')!;
    email.value = 'ana@alfa.com.br';
    email.dispatchEvent(new Event('input'));
    button(fixture, 'Criar usuário')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Selecione ao menos um perfil.');
    http.expectNone((r) => r.method === 'POST');

    boxes[1].click();
    fixture.detectChanges();
    expect(text(fixture)).not.toContain('Selecione ao menos um perfil.');
    button(fixture, 'Criar usuário')!.click();
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toMatchObject({ name: 'Ana Souza', email: 'ana@alfa.com.br', roleIds: ['r-2'], allCreditors: false });
  });
});
