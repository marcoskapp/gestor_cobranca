import { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { button, render, setup } from '../../../../../testing/component-harness';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { UserView } from '../../data/admin.models';
import { UserScopeDialog } from './user-scope-dialog';

const edited = (allCreditors: boolean): UserView =>
  ({ id: 'u-2', name: 'Bruno Lima', allCreditors, creditorIds: allCreditors ? [] : ['c-1'] }) as unknown as UserView;

describe('UserScopeDialog (escopo de credores)', () => {
  async function open(target: UserView, actorAll: boolean) {
    const harness = setup(UserScopeDialog, ['USER_MANAGE', 'CREDITOR_VIEW'], 'u-1', [
      { provide: DIALOG_DATA, useValue: target },
      { provide: DialogRef, useValue: { close: vi.fn() } },
    ]);
    (TestBed.inject(AuthService).user as WritableSignal<AuthenticatedUser>).set(fakeUser({ allCreditors: actorAll }));
    const fixture = await render(UserScopeDialog, harness.http, (url) => (url.includes('/creditors/') ? { id: 'c-1', name: 'Banco A' } : undefined));
    const checkbox = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#usersc-allCreditors');
    return { ...harness, fixture, checkbox };
  }

  it('quem tem todos os credores vê a opção habilitada', async () => {
    const { checkbox } = await open(edited(false), true);
    expect(checkbox).not.toBeNull();
    expect(checkbox!.disabled).toBe(false);
  });

  it('sem todos os credores, a opção não aparece para usuário com escopo restrito', async () => {
    const { checkbox } = await open(edited(false), false);
    expect(checkbox).toBeNull();
  });

  it('sem todos os credores, usuário que já tem todos: opção marcada e desabilitada, e salvar mantém o "todos"', async () => {
    const { checkbox, fixture, http } = await open(edited(true), false);
    expect(checkbox).not.toBeNull();
    expect(checkbox!.checked).toBe(true);
    expect(checkbox!.disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Só quem tem acesso a todos os credores pode alterar esta opção.');

    button(fixture, 'Salvar escopo')!.click();
    const req = http.expectOne((r) => r.method === 'PUT');
    expect(req.request.body).toEqual({ allCreditors: true, creditorIds: [] });
  });
});
