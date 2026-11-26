import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NgbConfig, NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { FileDownloadService } from '../../shared/data/file-download';
import { CreditorSession } from '../data/creditor-session';
import { CreditorMe, CreditorStatementDetail } from '../data/creditor.models';
import { CreditorStatementDetailPage } from './creditor-statement-detail';
import { directionText } from './creditor-statements';

const HASH = 'a'.repeat(64);

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'FINANCEIRO', roleLabel: 'Financeiro', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

function detail(status: 'SENT' | 'ACKNOWLEDGED' = 'SENT'): CreditorStatementDetail {
  return {
    statement: { id: 's1', sequenceNumber: 3, periodStart: '2026-10-01', periodEnd: '2026-10-31', status, recovered: 1000,
      netTransfer: 820, direction: 'AGENCY_TO_CREDITOR', sentAt: '2026-11-05T12:00:00Z', respondedAt: null,
      responseDueOn: '2026-11-15', responseOverdue: false, transferred: 0, outstanding: 820 },
    totals: { receivedByAgency: 1000, receivedByCreditor: 0, reversed: 0, recovered: 1000, agencyHonorarium: 0, commission: 160,
      fixedFees: 20, expenses: 0, adjustments: 0, netTransfer: 820 },
    files: [{ id: 'f1', format: 'PDF', fileName: 'demonstrativo-3.pdf', size: 1200, sha256: HASH, createdAt: '2026-11-05T11:00:00Z' }],
    transferred: 0, outstanding: 820, responseNote: null, respondedInPortal: false,
  };
}

/** Ficha do demonstrativo no portal do credor: resposta só com a permissão; o aceite leva o código e os hashes vistos. */
describe('ficha do demonstrativo no portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', children: [{ path: 'prestacao-de-contas/:id', component: CreditorStatementDetailPage }] }])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(NgbConfig).animation = false;
  });

  afterEach(() => TestBed.inject(NgbModal).dismissAll());

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  it('perfil só de consulta vê os valores e os arquivos, sem os botões de resposta', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const harness = await RouterTestingHarness.create('/credor/aurora/prestacao-de-contas/s1');
    http.expectOne('/api/creditor/v1/statements/s1').flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    expect(root.textContent).toContain('Demonstrativo nº 3');
    expect(root.textContent).toContain('demonstrativo-3.pdf');
    expect(root.textContent).toContain('A assessoria repassa a você');
    expect([...root.querySelectorAll('button')].some((b) => b.textContent?.trim() === 'Aceitar')).toBe(false);
    // prazo de resposta (só lembrete) e o medidor do que falta pagar
    expect(root.textContent).toContain('responda até 15/11/2026');
    expect(root.querySelector('[role="progressbar"]')!.getAttribute('aria-label')).toBe('0% do líquido já pago');
  });

  it('prazo vencido: o aviso diz a data, e o demonstrativo continua aguardando a resposta', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const harness = await RouterTestingHarness.create('/credor/aurora/prestacao-de-contas/s1');
    const late = detail();
    late.statement = { ...late.statement, responseOverdue: true };
    late.transferred = 410;
    late.outstanding = 410;
    http.expectOne('/api/creditor/v1/statements/s1').flush(late);
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    expect(root.textContent).toContain('Prazo vencido em 15/11/2026.');
    expect(root.textContent).toContain('Você ainda pode responder');
    expect(root.querySelector('[role="progressbar"]')!.getAttribute('aria-label')).toBe('50% do líquido já pago');
  });

  it('aceite envia o código do autenticador e o SHA-256 dos arquivos mostrados', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_STATEMENT_RESPOND']);
    const harness = await RouterTestingHarness.create('/credor/aurora/prestacao-de-contas/s1');
    http.expectOne('/api/creditor/v1/statements/s1').flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Aceitar')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();

    const code = document.querySelector<HTMLInputElement>('ngb-modal-window input[autocomplete="one-time-code"]')!;
    code.value = '12a3456';
    code.dispatchEvent(new Event('input'));
    harness.detectChanges();
    expect(code.value).toBe('123456');
    document.querySelector<HTMLFormElement>('ngb-modal-window form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/creditor/v1/statements/s1/acknowledge');
    expect(req.request.body).toEqual({ code: '123456', note: null, fileSha256: [HASH] });
    req.flush({ ...detail('ACKNOWLEDGED'), respondedInPortal: true });
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(root.textContent).toContain('Demonstrativo aceito');
  });

  it('arquivos mudaram (409): o diálogo fecha, a ficha recarrega e avisa', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_STATEMENT_RESPOND']);
    const harness = await RouterTestingHarness.create('/credor/aurora/prestacao-de-contas/s1');
    http.expectOne('/api/creditor/v1/statements/s1').flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Aceitar')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();
    const code = document.querySelector<HTMLInputElement>('ngb-modal-window input[autocomplete="one-time-code"]')!;
    code.value = '123456';
    code.dispatchEvent(new Event('input'));
    document.querySelector<HTMLFormElement>('ngb-modal-window form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/creditor/v1/statements/s1/acknowledge').flush({ code: 'statement.files_changed', detail: 'x' },
      { status: 409, statusText: 'Conflict' });
    // a ficha pede o demonstrativo de novo (a recarga fica pendente até a resposta)
    const reload = await vi.waitFor(() => {
      harness.detectChanges();
      return http.expectOne('/api/creditor/v1/statements/s1');
    });
    reload.flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(document.querySelector('ngb-modal-window')).toBeNull();
    expect(root.textContent).toContain('Os arquivos deste demonstrativo mudaram');
  });

  it('arquivo que não confere com o código de integridade não é salvo', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const save = vi.spyOn(TestBed.inject(FileDownloadService), 'saveBlob').mockImplementation(() => undefined);
    const harness = await RouterTestingHarness.create('/credor/aurora/prestacao-de-contas/s1');
    http.expectOne('/api/creditor/v1/statements/s1').flush(detail());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const root = harness.routeNativeElement!;
    [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Baixar'))!.click();
    http.expectOne('/api/creditor/v1/statements/s1/files/f1/content').flush(new Blob(['conteúdo alterado']));
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(root.textContent).toContain('não foi salvo');
    });
    expect(save).not.toHaveBeenCalled();
  });

  it('texto de quem paga quem', () => {
    expect(directionText('AGENCY_TO_CREDITOR')).toBe('A assessoria repassa a você');
    expect(directionText('CREDITOR_TO_AGENCY', true)).toBe('Você deve pagar à assessoria');
    expect(directionText('NONE')).toBe('Nada a repassar');
  });
});
