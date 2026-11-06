import { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
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

  async function fillAndPick(fixture: Awaited<ReturnType<typeof render>>) {
    const el = fixture.nativeElement as HTMLElement;
    const name = el.querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    name.value = 'Bruno Lima';
    name.dispatchEvent(new Event('input'));
    const email = el.querySelector<HTMLInputElement>('input[formcontrolname="email"]')!;
    email.value = 'bruno@alfa.com.br';
    email.dispatchEvent(new Event('input'));
    el.querySelector<HTMLInputElement>('#user-form-role-r-2')!.click();
    fixture.detectChanges();
  }

  it('com CREDITOR_VIEW mostra o seletor de credores', async () => {
    const harness = setup(UserForm, ['USER_MANAGE', 'CREDITOR_VIEW'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { user: null } },
      { provide: DialogRef, useValue: { close: vi.fn() } },
    ]);
    const fixture = await render(UserForm, harness.http, (url) => (url.endsWith('/roles') ? roles : url.endsWith('s') ? [] : undefined));
    expect(text(fixture)).toContain('Credores permitidos');
    expect(text(fixture)).not.toContain('O usuário será criado sem credores');
  });

  it('sem CREDITOR_VIEW, o seletor some e /creditors não é consultado; desmarcado, cria sem credores', async () => {
    const { http } = create();
    const fixture = await render(UserForm, http, (url) => (url.endsWith('/roles') ? roles : url.endsWith('s') ? [] : undefined));
    const el = fixture.nativeElement as HTMLElement;
    expect(text(fixture)).not.toContain('Credores permitidos');
    expect(el.querySelector('app-entity-picker')).toBeNull();
    expect(el.querySelector('#user-form-allCreditors'), 'quem tem todos os credores vê a opção').not.toBeNull();
    expect(text(fixture)).toContain('O usuário será criado sem credores');
    http.expectNone((r) => r.url.includes('/creditors'));

    await fillAndPick(fixture);
    button(fixture, 'Criar usuário')!.click();
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toMatchObject({ allCreditors: false, creditorIds: [] });
  });

  it('sem CREDITOR_VIEW, marcar "todos os credores" esconde o aviso e cria com todos', async () => {
    const { http } = create();
    const fixture = await render(UserForm, http, (url) => (url.endsWith('/roles') ? roles : url.endsWith('s') ? [] : undefined));
    await fillAndPick(fixture);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#user-form-allCreditors')!.click();
    fixture.detectChanges();
    expect(text(fixture)).not.toContain('O usuário será criado sem credores');
    button(fixture, 'Criar usuário')!.click();
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toMatchObject({ allCreditors: true, creditorIds: [] });
  });

  it('quem não tem todos os credores não vê a opção de concedê-los', async () => {
    const harness = setup(UserForm, ['USER_MANAGE', 'CREDITOR_VIEW'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { user: null } },
      { provide: DialogRef, useValue: { close: vi.fn() } },
    ]);
    (TestBed.inject(AuthService).user as WritableSignal<AuthenticatedUser>).set(fakeUser({ permissions: ['USER_MANAGE', 'CREDITOR_VIEW'], allCreditors: false }));
    const fixture = await render(UserForm, harness.http, (url) => (url.endsWith('/roles') ? roles : url.endsWith('s') ? [] : undefined));
    expect((fixture.nativeElement as HTMLElement).querySelector('#user-form-allCreditors')).toBeNull();
    expect(text(fixture)).toContain('Credores permitidos');
  });
});
