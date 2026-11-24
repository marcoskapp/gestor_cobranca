import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NgbConfig, NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { CreditorSession } from '../data/creditor-session';
import { CreditorDisputeDetail, CreditorMe } from '../data/creditor.models';
import { CreditorDisputeDetailPage } from './creditor-dispute-detail';
import { disputeSituationText } from './creditor-disputes';

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'JURIDICO', roleLabel: 'Jurídico', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

function detail(situation: 'AWAITING_YOU' | 'ANSWERED' = 'AWAITING_YOU'): CreditorDisputeDetail {
  return {
    dispute: { id: 'd1', protocol: 'AB12CD34', type: 'ALREADY_PAID', situation, outcome: null, openedAt: '2026-11-10T12:00:00Z',
      disputedAmount: 300, titleCount: 1, requestedDueDate: '2026-11-30', overdue: false, lastResponseAt: null, closedAt: null },
    titles: [{ externalId: 'T-100', installmentNumber: 1, dueDate: '2026-03-11', openBalance: 300 }],
    requests: [{ id: 'r1', sequenceNumber: 1, sentAt: '2026-11-11T12:00:00Z', requestedDueDate: '2026-11-30',
      awaiting: situation === 'AWAITING_YOU', overdue: false, subject: 'Contestação AB12CD34', body: 'Solicitamos o extrato de pagamentos.',
      documents: [{ id: 'e1', fileName: 'comprovante.pdf', size: 9, sha256: 'b'.repeat(64) }] }],
    responses: [],
  };
}

/** Ficha da contestação no portal do credor: resposta só com a permissão; envia posição, resumo e documentos. */
describe('ficha da contestação no portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', children: [{ path: 'contestacoes/:id', component: CreditorDisputeDetailPage }] }])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(NgbConfig).animation = false;
  });

  afterEach(() => TestBed.inject(NgbModal).dismissAll());

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  async function open(): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create('/credor/aurora/contestacoes/d1');
    http.expectOne('/api/creditor/v1/disputes/d1').flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    return { harness, root: harness.routeNativeElement! };
  }

  it('perfil só de consulta lê o pedido e os documentos, sem o botão de responder', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { root } = await open();
    expect(root.textContent).toContain('protocolo AB12CD34');
    expect(root.textContent).toContain('Solicitamos o extrato de pagamentos.');
    expect(root.textContent).toContain('comprovante.pdf');
    expect(root.textContent).toContain('T-100');
    expect([...root.querySelectorAll('button')].some((b) => b.textContent?.trim() === 'Responder')).toBe(false);
    expect(root.textContent).toContain('Gestão de recuperação e Jurídico');
  });

  it('resposta envia o pedido, a posição, o resumo e os documentos', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_DISPUTE_RESPOND']);
    const { harness, root } = await open();
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Responder')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();

    const select = document.querySelector<HTMLSelectElement>('ngb-modal-window select')!;
    select.value = 'ACCEPTS_CLAIM';
    select.dispatchEvent(new Event('change'));
    const note = document.querySelector<HTMLTextAreaElement>('ngb-modal-window textarea')!;
    note.value = 'Pagamento localizado em 05/03.';
    note.dispatchEvent(new Event('input'));
    const input = document.querySelector<HTMLInputElement>('ngb-modal-window input[type="file"]')!;
    const file = new File(['%PDF-1.4'], 'extrato.pdf', { type: 'application/pdf' });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    harness.detectChanges();
    document.querySelector<HTMLFormElement>('ngb-modal-window form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne('/api/creditor/v1/disputes/d1/responses');
    const body = req.request.body as FormData;
    expect(JSON.parse(await (body.get('response') as Blob).text())).toEqual({ requestId: 'r1', position: 'ACCEPTS_CLAIM',
      note: 'Pagamento localizado em 05/03.' });
    expect((body.getAll('files') as File[]).map((f) => f.name)).toEqual(['extrato.pdf']);
    req.flush(detail('ANSWERED'));
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(root.textContent).toContain('Resposta enviada à assessoria');
    expect(root.textContent).toContain('A assessoria analisa a sua resposta');
  });

  it('pedido já respondido por outra pessoa (409): o diálogo fecha e a ficha recarrega', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_DISPUTE_RESPOND']);
    const { harness, root } = await open();
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Responder')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();
    const select = document.querySelector<HTMLSelectElement>('ngb-modal-window select')!;
    select.value = 'CONFIRMS_DEBT';
    select.dispatchEvent(new Event('change'));
    const note = document.querySelector<HTMLTextAreaElement>('ngb-modal-window textarea')!;
    note.value = 'Sem pagamento registrado.';
    note.dispatchEvent(new Event('input'));
    document.querySelector<HTMLFormElement>('ngb-modal-window form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/disputes/d1/responses').flush({ code: 'dispute_response.not_awaiting', detail: 'x' },
      { status: 409, statusText: 'Conflict' });
    const reload = await vi.waitFor(() => {
      harness.detectChanges();
      return http.expectOne('/api/creditor/v1/disputes/d1');
    });
    reload.flush(detail('ANSWERED'));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(document.querySelector('ngb-modal-window')).toBeNull();
    expect(root.textContent).toContain('outra pessoa do seu time já respondeu');
  });

  it('limite de documentos (409 de regra): o diálogo continua aberto com o texto digitado', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_DISPUTE_RESPOND']);
    const { harness, root } = await open();
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Responder')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();
    const select = document.querySelector<HTMLSelectElement>('ngb-modal-window select')!;
    select.value = 'INFORMATION';
    select.dispatchEvent(new Event('change'));
    const note = document.querySelector<HTMLTextAreaElement>('ngb-modal-window textarea')!;
    note.value = 'Segue o contrato.';
    note.dispatchEvent(new Event('input'));
    document.querySelector<HTMLFormElement>('ngb-modal-window form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/disputes/d1/responses').flush({ code: 'dispute.evidence_limit', detail: 'x' },
      { status: 409, statusText: 'Conflict' });
    harness.detectChanges();
    await harness.fixture.whenStable();
    expect(document.querySelector('ngb-modal-window')).not.toBeNull();
    expect(document.querySelector<HTMLTextAreaElement>('ngb-modal-window textarea')!.value).toBe('Segue o contrato.');
    expect(document.querySelector('ngb-modal-window [role="alert"]')?.textContent).toContain('máximo de documentos');
    http.expectNone('/api/creditor/v1/disputes/d1');
  });

  it('texto da situação', () => {
    const d = detail().dispute;
    expect(disputeSituationText(d)).toBe('A assessoria aguarda a sua resposta');
    expect(disputeSituationText({ ...d, overdue: true })).toBe('Prazo pedido pela assessoria vencido');
    expect(disputeSituationText({ ...d, situation: 'CLOSED', outcome: 'WITHDRAWN' })).toBe('Encerrada: o devedor desistiu');
  });
});
