import { TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Harness, setup, tick } from '../../../../testing/component-harness';
import { TEMPLATE_PREVIEW_DEBOUNCE_MS } from '../components/template-fill';
import { ConversationView, TemplateView } from '../data/communication.models';
import { Composer } from './composer';

/** Template da assessoria como o do sandbox ("Saldo e atraso"). */
const TEMPLATE = {
  id: 't-1',
  creditorId: null,
  code: 'COBRANCA_SALDO',
  templateVersion: 1,
  channel: 'WHATSAPP',
  name: 'Saldo e atraso',
  purpose: 'COLLECTION',
  subject: null,
  body: 'Olá, {{nome}}. Em nome de {{credor}}, consta em aberto {{saldo}}, com {{dias}} dias de atraso.',
  variables: [
    { name: 'nome', type: 'FIRST_NAME', required: true, maxLength: null },
    { name: 'credor', type: 'TEXT', required: true, maxLength: null },
    { name: 'saldo', type: 'MONEY', required: true, maxLength: null },
    { name: 'dias', type: 'INTEGER', required: true, maxLength: null },
  ],
  status: 'PUBLISHED',
} as unknown as TemplateView;

/** Conversa fora da janela de 24 h: só template. */
const CONVERSATION = { id: 'cv-1', debtorId: 'd-1', creditorId: 'c-1', channel: 'WHATSAPP', status: 'OPEN', sessionWindowOpen: false, sessionWindowEndsAt: null } as unknown as ConversationView;

/** Prévia como a do servidor: valor informado entra no texto; o que falta aparece como [nome]. */
function serverPreview(values: Record<string, string>): string {
  return TEMPLATE.body.replace(/\{\{(\w+)\}\}/g, (_, k: string) => values[k] ?? `[${k}]`);
}

describe('Composer (envio na conversa)', () => {
  let h: Harness;
  let previews: Record<string, string>[];
  let names: { id: string; name: string }[];
  let reject: string | null;

  function create(permissions = ['MESSAGE_SEND', 'INBOX_VIEW', 'CREDITOR_VIEW']) {
    previews = [];
    names = [{ id: 'd-1', name: 'Iara Queiroz Albuquerque' }];
    reject = null;
    h = setup(Composer, permissions);
  }

  function reply(req: TestRequest): void {
    const url = req.request.url;
    if (url.endsWith('/preview')) {
      const values = req.request.body as Record<string, string>;
      previews.push(values);
      if (reject && values[reject]) {
        return req.flush({ code: 'template.variable_first_name_invalid', detail: `${reject} deve conter apenas o primeiro nome` }, { status: 422, statusText: 'Unprocessable' });
      }
      return req.flush({ subject: null, body: serverPreview(values), parameters: [] });
    }
    if (url.includes('/debtors/names')) return req.flush(names);
    if (url.includes('/creditors/c-1')) return req.flush({ id: 'c-1', legalName: 'Banco Fictício S.A.', tradeName: 'Banco Fictício' });
    if (url.includes('templates')) return req.flush({ content: [TEMPLATE], page: 0, size: 200, totalElements: 1, totalPages: 1 });
    return req.flush({});
  }

  async function settle(f: ComponentFixture<Composer>, rounds = 4): Promise<void> {
    for (let i = 0; i < rounds; i++) {
      f.detectChanges();
      await tick();
      h.http.match(() => true).forEach(reply);
    }
    f.detectChanges();
  }

  /** Espera a pausa da digitação e responde à prévia. */
  async function afterPause(f: ComponentFixture<Composer>): Promise<void> {
    f.detectChanges();
    await new Promise((r) => setTimeout(r, TEMPLATE_PREVIEW_DEBOUNCE_MS + 50));
    await settle(f);
  }

  async function open(): Promise<{ f: ComponentFixture<Composer>; el: HTMLElement }> {
    const f = TestBed.createComponent(Composer);
    f.componentRef.setInput('conversation', CONVERSATION);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = 't-1';
    select.dispatchEvent(new Event('change'));
    await settle(f);
    return { f, el };
  }

  const preview = (el: HTMLElement) => el.querySelector('.tf-preview')?.textContent ?? '';
  const input = (el: HTMLElement, i: number) => el.querySelectorAll<HTMLInputElement>('app-template-fill input')[i];

  function type(target: HTMLInputElement, value: string): void {
    target.value = value;
    target.dispatchEvent(new Event('input'));
  }

  it('ao escolher o template, o primeiro nome e o credor já vêm preenchidos e a prévia já mostra os dois, com o que falta marcado', async () => {
    create();
    const { el } = await open();
    expect(input(el, 0).value).toBe('Iara');
    expect(input(el, 1).value).toBe('Banco Fictício');
    expect(previews).toEqual([{ nome: 'Iara', credor: 'Banco Fictício' }]);
    expect(preview(el)).toBe('Olá, Iara. Em nome de Banco Fictício, consta em aberto [saldo], com [dias] dias de atraso.');
    // valores obrigatórios ainda vazios: não envia
    expect(el.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(true);
  });

  it('a prévia acompanha a digitação dos valores (depois da pausa) e o valor usa a máscara monetária', async () => {
    create();
    const { f, el } = await open();
    const money = el.querySelector<HTMLInputElement>('app-template-fill input[appMoney]')!;
    expect(money).not.toBeNull();
    type(money, '150000');
    type(input(el, 3), '45');
    await afterPause(f);
    // uma prévia só para a pausa (não uma por tecla), com o valor em reais como o servidor recebe no envio
    expect(previews.at(-1)).toEqual({ nome: 'Iara', credor: 'Banco Fictício', saldo: '1500.00', dias: '45' });
    expect(previews.length).toBe(2);
    expect(preview(el)).toBe('Olá, Iara. Em nome de Banco Fictício, consta em aberto 1500.00, com 45 dias de atraso.');
    expect(el.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(false);
  });

  it('valor recusado pelo servidor: mostra o motivo, marca a prévia como anterior e bloqueia o envio', async () => {
    create();
    const { f, el } = await open();
    reject = 'nome';
    type(input(el, 0), 'Iara Queiroz');
    await afterPause(f);
    expect(el.textContent).toContain('nome deve conter apenas o primeiro nome');
    expect(preview(el)).toContain('Olá, Iara.');
    expect(el.textContent).toContain('A prévia acima é a da última versão aceita');
    expect(el.querySelector('.tf-preview--stale')).not.toBeNull();
    // a mensagem não sai diferente do que a prévia mostra
    expect(el.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(true);
    expect(h.notifications.error).toEqual([]);
  });

  it('nome do devedor e do credor não resolvidos: os campos ficam para o operador, sem "Indisponível"', async () => {
    create(['MESSAGE_SEND', 'INBOX_VIEW']);
    names = [];
    const { el } = await open();
    expect(input(el, 0).value).toBe('');
    expect(input(el, 1).value).toBe('');
    expect(previews).toEqual([{}]);
    expect(preview(el)).toContain('Olá, [nome].');
  });
});
