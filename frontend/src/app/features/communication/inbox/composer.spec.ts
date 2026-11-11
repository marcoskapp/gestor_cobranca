import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideAppLocale } from '../../../core/config/app-locale';
import { ConversationView, TemplateView } from '../data/communication.models';
import { Composer } from './composer';

const tick = () => new Promise((r) => setTimeout(r, 0));

const TEMPLATE = {
  id: 't-1',
  creditorId: null,
  code: 'COBRANCA_ABERTURA',
  templateVersion: 1,
  channel: 'WHATSAPP',
  name: 'Abertura',
  purpose: 'COLLECTION',
  subject: null,
  body: 'Olá, {{nome}}! Podemos conversar?',
  variables: [{ name: 'nome', type: 'FIRST_NAME', required: true, maxLength: null }],
  status: 'PUBLISHED',
} as unknown as TemplateView;

/** Conversa fora da janela de 24 h: só template. */
const CONVERSATION = { id: 'cv-1', debtorId: 'd-1', creditorId: 'c-1', channel: 'WHATSAPP', status: 'OPEN', sessionWindowOpen: false, sessionWindowEndsAt: null } as unknown as ConversationView;

describe('Composer (envio na conversa)', () => {
  let http: HttpTestingController;
  let previews: Record<string, string>[];
  let names: { id: string; name: string }[];

  beforeEach(() => {
    previews = [];
    names = [{ id: 'd-1', name: 'Maria Aparecida Souza' }];
    TestBed.configureTestingModule({ imports: [Composer], providers: [provideAppLocale(), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function reply(req: TestRequest): void {
    const url = req.request.url;
    if (url.endsWith('/preview')) {
      previews.push(req.request.body as Record<string, string>);
      return req.flush({ subject: null, body: `Olá, ${(req.request.body as Record<string, string>)['nome']}! Podemos conversar?`, parameters: [] });
    }
    if (url.includes('names')) return req.flush(names);
    if (url.includes('templates')) return req.flush({ content: [TEMPLATE], page: 0, size: 200, totalElements: 1, totalPages: 1 });
    return req.flush({});
  }

  async function settle(f: ComponentFixture<Composer>): Promise<void> {
    for (let i = 0; i < 4; i++) {
      f.detectChanges();
      await tick();
      http.match(() => true).forEach(reply);
    }
    f.detectChanges();
  }

  it('ao escolher o template, o primeiro nome do devedor da conversa já vem preenchido e a prévia, montada', async () => {
    const f = TestBed.createComponent(Composer);
    f.componentRef.setInput('conversation', CONVERSATION);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = 't-1';
    select.dispatchEvent(new Event('change'));
    await settle(f);
    expect(el.querySelector<HTMLInputElement>('input')!.value).toBe('Maria');
    expect(previews).toEqual([{ nome: 'Maria' }]);
    expect(el.querySelector('.cp-preview')!.textContent).toContain('Olá, Maria! Podemos conversar?');
    expect(el.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(false);
  });

  it('nome do devedor não resolvido: o campo fica para o operador, sem "Indisponível" nem prévia', async () => {
    names = [];
    const f = TestBed.createComponent(Composer);
    f.componentRef.setInput('conversation', CONVERSATION);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = 't-1';
    select.dispatchEvent(new Event('change'));
    await settle(f);
    expect(el.querySelector<HTMLInputElement>('input')!.value).toBe('');
    expect(previews).toEqual([]);
  });
});
