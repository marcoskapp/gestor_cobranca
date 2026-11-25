import { render, setup } from '../../../../testing/component-harness';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { QueueForm } from './queue-form';

describe('QueueForm — atalho para o prazo do selo "Novo"', () => {
  const providers = [
    { provide: DIALOG_DATA, useValue: null },
    { provide: DialogRef, useValue: { close: () => undefined } },
  ];
  const settingsLink = (el: HTMLElement) => el.querySelector('a[href="/organizacao/configuracoes#contatos-recebidos"]');

  it('com DEBTOR_MANAGE (a permissão da seção), a chave "Só devedores com contato novo" leva à seção Contatos recebidos', async () => {
    const { http } = setup(QueueForm, ['QUEUE_MANAGE', 'DEBTOR_MANAGE'], 'u-1', providers);
    const fixture = await render(QueueForm, http, () => []);
    const el = fixture.nativeElement as HTMLElement;
    expect(settingsLink(el)?.textContent).toContain('Ajustar o prazo do selo “Novo”');
    // o atalho fica fora da descrição da chave lida pelo leitor de tela
    expect(el.querySelector('#queue-new-contact-hint')?.textContent).not.toContain('Ajustar');
  });

  it('sem DEBTOR_MANAGE, o atalho não aparece', async () => {
    const { http } = setup(QueueForm, ['QUEUE_MANAGE'], 'u-1', providers);
    const fixture = await render(QueueForm, http, () => []);
    expect(settingsLink(fixture.nativeElement as HTMLElement)).toBeNull();
  });
});
