import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { button, render, setup, text } from '../../../../../testing/component-harness';
import { RoleForm } from './role-form';

const catalog = (grantable: (code: string) => boolean) => [
  { code: 'USER_VIEW', description: 'Consultar usuários', grantable: grantable('USER_VIEW') },
  { code: 'ROLE_MANAGE', description: 'Gerenciar perfis', grantable: grantable('ROLE_MANAGE') },
  { code: 'PAYMENT_REVERSE', description: 'Estornar pagamentos', grantable: grantable('PAYMENT_REVERSE') },
];

describe('RoleForm (permissões que podem ser concedidas)', () => {
  function create() {
    return setup(RoleForm, ['ROLE_MANAGE', 'USER_VIEW'], 'u-1', [
      { provide: MAT_DIALOG_DATA, useValue: null },
      { provide: MatDialogRef, useValue: { close: vi.fn() } },
    ]);
  }

  it('administrador com tudo: "Marcar todas" seleciona o catálogo inteiro, sem aviso de bloqueio', async () => {
    const { http } = create();
    const fixture = await render(RoleForm, http, (url) => (url.endsWith('/roles/permissions') ? catalog(() => true) : undefined));
    expect(text(fixture)).not.toContain('Você só pode conceder');
    button(fixture, 'Marcar todas')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('(3 selecionadas)');
  });

  it('quem não tem uma permissão vê o aviso, a permissão bloqueada e marca só o que pode conceder', async () => {
    const { http } = create();
    const fixture = await render(RoleForm, http, (url) =>
      url.endsWith('/roles/permissions') ? catalog((c) => c !== 'PAYMENT_REVERSE') : undefined,
    );
    const t = text(fixture);
    expect(t).toContain('Você só pode conceder permissões que o seu próprio perfil possui');
    expect(t).toContain('fora do seu perfil');
    button(fixture, 'Marcar todas que posso conceder')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('(2 selecionadas)');
  });
});
