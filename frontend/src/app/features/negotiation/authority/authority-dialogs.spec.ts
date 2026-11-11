import { firstValueFrom } from 'rxjs';
import { page, render, setup, tick } from '../../../../testing/component-harness';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { AssignAuthorityDialog } from './authority-dialogs';

const ASSIGNABLE = '/api/v1/negotiation/authority-assignments/assignable-users';

async function open() {
  const { http } = setup(AssignAuthorityDialog, ['AUTHORITY_MANAGE'], 'u-1', [
    { provide: DIALOG_DATA, useValue: { profiles: [] } },
    { provide: DialogRef, useValue: { close: vi.fn(), titleId: 'aa-title' } },
  ]);
  const fixture = await render(AssignAuthorityDialog, http, () => undefined);
  const dialog = fixture.componentInstance;
  return { http, fixture, users: dialog['users'] as EntitySource, form: dialog['form'], problem: dialog['problem'] };
}

describe('AssignAuthorityDialog (quem pode receber alçada)', () => {
  it('busca na lista restrita, pelo credor escolhido, e mostra só o nome', async () => {
    const { http, users, form } = await open();
    form.controls.creditor.setValue(['c-a']);
    const result = firstValueFrom(users.search('ana'));
    const req = http.expectOne((r) => r.url === ASSIGNABLE);
    expect(req.request.params.get('creditorId')).toBe('c-a');
    expect(req.request.params.get('q')).toBe('ana');
    req.flush(page([{ id: 'u-2', name: 'Ana Lima' }]));
    expect(await result).toEqual([{ value: 'u-2', label: 'Ana Lima' }]);
  });

  it('atribuição geral: sem credor na busca', async () => {
    const { http, users } = await open();
    const result = firstValueFrom(users.search(''));
    const req = http.expectOne((r) => r.url === ASSIGNABLE);
    expect(req.request.params.has('creditorId')).toBe(false);
    req.flush(page([]));
    expect(await result).toEqual([]);
  });

  it('trocar para um credor sem acesso da pessoa escolhida limpa a escolha e explica', async () => {
    const { http, form, problem } = await open();
    form.controls.user.setValue(['u-2']);
    form.controls.creditor.setValue(['c-b']);
    await tick();
    // o seletor também resolve o nome escolhido; a conferência pelo novo credor é a que leva o credor
    const [req] = http.match((r) => r.url === ASSIGNABLE && r.params.get('creditorId') === 'c-b');
    expect(req.request.params.get('ids')).toBe('u-2');
    req.flush(page([]));
    expect(form.controls.user.value).toEqual([]);
    expect(problem()).toContain('não tem acesso a esse credor');
  });
});
