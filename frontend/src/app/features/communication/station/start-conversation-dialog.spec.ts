import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { ConversationView, MessageView, TemplateView } from '../data/communication.models';
import { StartContact, StartConversationDialog } from './start-conversation-dialog';

const contacts: StartContact[] = [
  { contactId: 'ct-1', type: 'PHONE', maskedValue: '(11) 9****-1234', label: 'MOBILE', whatsapp: true, status: 'VALID', priority: 1 },
  { contactId: 'ct-2', type: 'PHONE', maskedValue: '(11) 3****-5678', label: 'HOME', whatsapp: false, status: 'VALID', priority: 2 },
  { contactId: 'ct-3', type: 'EMAIL', maskedValue: 'a***@exemplo.com.br', label: 'OTHER', whatsapp: false, status: 'VALID', priority: 1 },
];

const template = {
  id: 't-1',
  creditorId: null,
  code: 'COBRANCA',
  channel: 'WHATSAPP',
  name: 'Cobrança',
  subject: null,
  body: 'Olá {{nome}}, há um débito de {{valor}}.',
  variables: [
    { name: 'nome', type: 'FIRST_NAME', required: true },
    { name: 'valor', type: 'MONEY', required: true },
  ],
  status: 'PUBLISHED',
} as unknown as TemplateView;

const openConversation = { id: 'cv-9', channel: 'WHATSAPP', contactMasked: '(11) 9****-1234', status: 'OPEN', ownerType: 'USER', ownerUserId: 'u-1', requiredProfile: null, sessionWindowOpen: true } as ConversationView;

function message(o: Partial<MessageView>): MessageView {
  return { id: 'm-1', conversationId: 'cv-1', channel: 'WHATSAPP', status: 'QUEUED', failureCode: null, failureDetail: null, ...o } as MessageView;
}

describe('StartConversationDialog (mensagem pela estação)', () => {
  const close = vi.fn();

  function create() {
    return setup(StartConversationDialog, ['MESSAGE_SEND', 'INBOX_VIEW'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { caseId: 'k-1', debtorId: 'd-1', creditorId: 'c-1', debtorName: 'Ana Paula Ribeiro', contacts } },
      { provide: DialogRef, useValue: { close } },
    ]);
  }

  const respond =
    (opts: { allowed?: boolean; open?: ConversationView[] } = {}) =>
    (url: string) => {
      if (url.endsWith('/cases/k-1/contact-availability')) {
        const allowed = opts.allowed ?? true;
        return { caseId: 'k-1', evaluatedAt: '', caseEligible: true, caseReasons: [], channels: [{ channel: 'WHATSAPP', allowed, reasons: allowed ? [] : ['RESTRICTED_CHANNEL'], nextAllowedAt: null, contactId: 'ct-1' }] };
      }
      if (url.endsWith('/communication/conversations')) {
        return page(opts.open ?? []);
      }
      if (url.endsWith('/communication/templates')) {
        return page([template]);
      }
      return undefined;
    };

  async function pickTemplate(fixture: Awaited<ReturnType<typeof render>>, http: ReturnType<typeof create>['http']) {
    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select')!;
    select.value = 't-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/templates/t-1/preview')).flush({ subject: null, body: 'Olá Ana, há um débito de {{valor}}.', parameters: [] });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function typeMoney(fixture: Awaited<ReturnType<typeof render>>, digits: string) {
    const money = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[appMoney]')!;
    money.value = digits;
    money.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => vi.clearAllMocks());

  it('oferece os canais com contato utilizável e, no WhatsApp, só os telefones marcados como WhatsApp', async () => {
    const { http } = create();
    const fixture = await render(StartConversationDialog, http, respond());
    const t = text(fixture);
    expect(t).toContain('WhatsApp');
    expect(t).toContain('SMS');
    expect(t).toContain('E-mail');
    expect(t).toContain('(11) 9****-1234');
    expect(t).not.toContain('(11) 3****-5678');
    expect(t).toContain('Permitido');
    expect(t).toContain('use um template aprovado');
    // consulta das conversas abertas do caso no canal
    http.verify();
  });

  it('envia o template com o caso, o contato e o primeiro nome já preenchido; fecha com a mensagem', async () => {
    const { http } = create();
    const fixture = await render(StartConversationDialog, http, respond());
    await pickTemplate(fixture, http);
    const name = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input:not([type=radio]):not([appMoney])');
    expect(name?.value).toBe('Ana');
    expect(text(fixture)).toContain('Olá Ana');
    // valor obrigatório ainda vazio: não envia
    expect(button(fixture, 'Enviar')!.disabled).toBe(true);
    await typeMoney(fixture, '50000');
    button(fixture, 'Enviar')!.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/communication/messages'));
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(req.request.body).toMatchObject({
      conversationId: null,
      debtorId: 'd-1',
      creditorId: 'c-1',
      caseId: 'k-1',
      channel: 'WHATSAPP',
      contactId: 'ct-1',
      templateCode: 'COBRANCA',
      text: null,
    });
    expect(req.request.body.variables.nome).toBe('Ana');
    expect(req.request.body.variables.valor).toBe('500.00');
    req.flush(message({}));
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'm-1' }));
  });

  it('negado pelo porteiro no envio: mostra o motivo e não fecha', async () => {
    const { http } = create();
    const fixture = await render(StartConversationDialog, http, respond());
    await pickTemplate(fixture, http);
    await typeMoney(fixture, '10000');
    button(fixture, 'Enviar')!.click();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/communication/messages')).flush(
      message({ status: 'FAILED', failureCode: 'CONTACT_DENIED', failureDetail: 'RESTRICTED_CHANNEL' }),
    );
    fixture.detectChanges();
    expect(text(fixture)).toContain('não permitiu o envio agora');
    expect(close).not.toHaveBeenCalled();
  });

  it('contato restrito fica desabilitado e o envio não habilita', async () => {
    const { http } = create();
    const fixture = await render(StartConversationDialog, http, respond({ allowed: false }));
    expect(text(fixture)).toContain('Restrito');
    const radio = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#sc-contact-ct-1')!;
    expect(radio.disabled).toBe(true);
    expect(button(fixture, 'Enviar')!.disabled).toBe(true);
  });

  it('conversa aberta do caso no canal: avisa e leva à caixa de entrada', async () => {
    const { http } = create();
    const fixture = await render(StartConversationDialog, http, respond({ open: [openConversation] }));
    expect(text(fixture)).toContain('Conversa aberta com (11) 9****-1234 · Com você');
    expect(text(fixture)).toContain('responda por lá com texto livre');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Abrir conversa')!.click();
    expect(close).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/conversas', 'cv-9']);
  });

  it('sem INBOX_VIEW: não consulta as conversas abertas nem oferece "Abrir conversa" (troca de canal inclusive)', async () => {
    const { http } = setup(StartConversationDialog, ['MESSAGE_SEND'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { caseId: 'k-1', debtorId: 'd-1', creditorId: 'c-1', debtorName: 'Ana Paula Ribeiro', contacts } },
      { provide: DialogRef, useValue: { close } },
    ]);
    const urls: string[] = [];
    const answer = respond({ open: [openConversation] });
    const fixture = await render(StartConversationDialog, http, (url) => {
      urls.push(url);
      return answer(url);
    });
    expect(text(fixture)).toContain('use um template aprovado');
    expect(button(fixture, 'Abrir conversa')).toBeUndefined();

    const email = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#sc-channel-EMAIL')!;
    expect(email).not.toBeNull();
    email.dispatchEvent(new Event('change'));
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      for (const req of http.match((r) => r.method === 'GET')) {
        urls.push(req.request.url);
        req.flush(answer(req.request.url) ?? {});
      }
    }
    expect(urls.some((u) => u.endsWith('/communication/conversations'))).toBe(false);
    expect(urls.some((u) => u.endsWith('/communication/templates'))).toBe(true);
    http.verify();
  });

  it('sem contato utilizável: explica e não oferece envio', async () => {
    const { http } = setup(StartConversationDialog, ['MESSAGE_SEND'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { caseId: 'k-1', debtorId: 'd-1', creditorId: 'c-1', debtorName: 'Ana', contacts: [contacts[1]] } },
      { provide: DialogRef, useValue: { close } },
    ]);
    const fixture = await render(StartConversationDialog, http, respond());
    expect(text(fixture)).toContain('não tem WhatsApp, celular ou e-mail válido');
    expect(button(fixture, 'Enviar')!.disabled).toBe(true);
  });
});
