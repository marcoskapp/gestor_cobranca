import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
import { DataSubjectRequestView, ResponseDraftView } from '../../data/compliance.models';
import { DraftUse, ResponseDraftCard } from './response-draft-card';

const BASE = '/api/v1/compliance/data-subject-requests';

const request: DataSubjectRequestView = {
  id: 'q-7',
  type: 'INFORMATION_SHARING',
  channel: 'EMAIL',
  status: 'IN_PROGRESS',
  debtorId: 'd-1',
  requesterName: 'Maria Titular',
  maskedDocument: null,
  maskedContact: null,
  details: null,
  receivedAt: '2026-11-01T12:00:00Z',
  dueAt: '2026-11-16T12:00:00Z',
  overdue: false,
  assignedTo: null,
  identityVerifiedAt: '2026-11-02T12:00:00Z',
  identityVerifiedBy: null,
  executedAt: null,
  completedAt: null,
  responseSummary: null,
  hasResultFile: false,
  resultSha256: null,
};

const draft: ResponseDraftView = {
  requestId: 'q-7',
  type: 'INFORMATION_SHARING',
  generatedAt: '2026-11-11T12:00:00Z',
  processing: true,
  dataCategories: [],
  recipients: [
    {
      type: 'CREDITOR',
      key: 'debt.creditor:c-2',
      recipientId: 'c-2',
      name: 'Loja Exemplo',
      purposes: ['cobrança da dívida'],
      firstAt: '2026-10-01T12:00:00Z',
      lastAt: '2026-10-01T12:00:00Z',
      count: 1,
      countLabel: '1 título',
      links: [{ route: '/organizacao/credores/c-2', queryParams: {}, anyPermission: ['ORGANIZATION_AREA_VIEW'], allPermissions: ['CREDITOR_VIEW'], label: 'Abrir o credor' }],
      legalBases: [],
      processor: null,
    },
    {
      type: 'PROVIDER',
      key: 'telephony.twilio',
      recipientId: null,
      name: 'Twilio',
      purposes: ['ligações telefônicas'],
      firstAt: '2026-10-02T12:00:00Z',
      lastAt: '2026-10-05T12:00:00Z',
      count: 2,
      countLabel: '2 chamadas',
      links: [{ route: '/telefonia/chamadas', queryParams: { devedor: 'd-1' }, anyPermission: ['TELEPHONY_SUPERVISE'], allPermissions: ['DEBTOR_VIEW'], label: 'Ver as chamadas do devedor' }],
      legalBases: [],
      processor: null,
    },
  ],
  alerts: [
    { code: 'CREDITOR_WITHOUT_LEGAL_BASIS', severity: 'WARNING', message: 'Loja Exemplo não tem base legal vigente registrada.', recipientKey: 'debt.creditor:c-2', recipientId: 'c-2' },
    { code: 'PROCESSOR_UNREGISTERED', severity: 'WARNING', message: 'Twilio não está no cadastro de operadores.', recipientKey: 'telephony.twilio', recipientId: null },
  ],
  unavailable: [],
  paragraphs: [
    { text: 'Olá, Maria.', recipientKeys: [] },
    { text: 'Prestadores de serviço:\n- Twilio: ligações telefônicas.', recipientKeys: ['telephony.twilio'] },
  ],
  text: 'Olá, Maria.\n\nPrestadores de serviço:\n- Twilio: ligações telefônicas.',
  textSha256: 'b'.repeat(64),
  textMaxLength: 4000,
};

describe('ResponseDraftCard', () => {
  let http: HttpTestingController;
  let permissions: string[];
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ResponseDraftCard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user,
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(r: DataSubjectRequestView = request): Promise<ComponentFixture<ResponseDraftCard>> {
    const fixture = TestBed.createComponent(ResponseDraftCard);
    fixture.componentRef.setInput('request', r);
    fixture.detectChanges();
    return fixture;
  }

  const buttonWith = (fixture: ComponentFixture<ResponseDraftCard>, label: string) =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes(label));

  /** Pede a montagem (nada é consultado ao abrir) e responde. */
  async function loaded(fixture: ComponentFixture<ResponseDraftCard>, body: ResponseDraftView = draft): Promise<HTMLElement> {
    buttonWith(fixture, 'Montar resposta sugerida')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/q-7/response-draft`).flush(body);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lista os destinatários por grupo, com alertas e atalhos, e mostra só as evidências que o perfil abre', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE', 'TELEPHONY_SUPERVISE', 'DEBTOR_VIEW'];
    const el = await loaded(await render());
    const groups = [...el.querySelectorAll('.rd-group th')].map((t) => t.textContent?.trim());
    expect(groups).toEqual(['Credores', 'Prestadores de serviço (operadores)']);
    const hrefs = [...el.querySelectorAll('table a')].map((a) => a.getAttribute('href'));
    // ficha do credor oculta (sem CREDITOR_VIEW); chamadas filtradas pelo devedor visíveis
    expect(hrefs).toContain('/telefonia/chamadas?devedor=d-1');
    expect(hrefs).not.toContain('/organizacao/credores/c-2');
    expect(el.textContent).toContain('Sem base legal vigente');
    expect(el.textContent).toContain('Sem cadastro de operador');
    const configLinks = [...el.querySelectorAll('.rd-alerts a')].map((a) => a.getAttribute('href'));
    expect(configLinks).toEqual(['/compliance/bases-legais?credor=c-2', '/compliance/operadores?chave=telephony.twilio']);
    // explicável: cada parágrafo mostra de onde veio
    expect(el.querySelector('.rd-source')?.textContent).toContain('Twilio');
  });

  it('"Usar como resposta" entrega o texto e o resumo do rascunho', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE'];
    const fixture = await render();
    const el = await loaded(fixture);
    let used: DraftUse | undefined;
    fixture.componentInstance.use.subscribe((u) => (used = u));
    const button = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Usar como resposta'))!;
    button.click();
    expect(used).toEqual({ text: draft.text, sha256: draft.textSha256 });
  });

  it('o próprio componente é o cartão: espaçamento padrão entre cartões, região rotulada e âncora do tour', async () => {
    const host = (await render()).nativeElement as HTMLElement;
    expect(host.classList).toContain('app-card');
    expect(host.getAttribute('role')).toBe('region');
    expect(host.getAttribute('aria-labelledby')).toBe('resposta-sugerida');
    expect(host.querySelector('#resposta-sugerida')).not.toBeNull();
    expect(host.getAttribute('data-tour')).toBe('dsr-draft');
    // um cartão interno não receberia a margem entre cartões vizinhos (o problema que motivou a mudança)
    expect(host.querySelector('section.app-card')).toBeNull();
  });

  it('antes de iniciar o atendimento explica o próximo passo, sem botão que não funciona', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE'];
    const el = await loaded(await render({ ...request, status: 'IDENTITY_VERIFIED' }));
    expect([...el.querySelectorAll('button')].some((b) => b.textContent?.includes('Usar como resposta'))).toBe(false);
    expect(el.textContent).toContain('Inicie o atendimento');
  });

  it('ao abrir a solicitação não monta o rascunho (leitura de dado pessoal só quando a pessoa pede)', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE'];
    const fixture = await render();
    await fixture.whenStable();
    http.expectNone(`${BASE}/q-7/response-draft`);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Resposta sugerida ainda não montada.');
    expect(el.textContent).toContain('registrada na trilha de acesso');
    expect(buttonWith(fixture, 'Usar como resposta')).toBeUndefined();

    // "Montar de novo" é outra consulta, também explícita
    await loaded(fixture);
    buttonWith(fixture, 'Montar de novo')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/q-7/response-draft`).flush(draft);

    // outra solicitação volta ao estado inicial, sem consultar
    fixture.componentRef.setInput('request', { ...request, id: 'q-8' });
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectNone(`${BASE}/q-8/response-draft`);
    expect(buttonWith(fixture, 'Montar resposta sugerida')).toBeDefined();
  });

  it('sem identidade confirmada não consulta o servidor', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE'];
    const fixture = await render({ ...request, status: 'RECEIVED', identityVerifiedAt: null });
    http.expectNone(`${BASE}/q-7/response-draft`);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Confirme a identidade');
  });

  it('falha ao montar: estado de erro com "Tentar novamente"', async () => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE'];
    const fixture = await render();
    buttonWith(fixture, 'Montar resposta sugerida')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/q-7/response-draft`).flush({}, { status: 500, statusText: 'erro' });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Não foi possível montar a resposta sugerida.');
    expect([...el.querySelectorAll('button')].some((b) => b.textContent?.includes('Tentar novamente'))).toBe(true);
    buttonWith(fixture, 'Tentar novamente')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/q-7/response-draft`).flush(draft);
  });
});
