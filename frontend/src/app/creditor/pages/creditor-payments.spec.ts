import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NgbConfig, NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { provideBootstrapComponentDefaults } from '../../core/config/bootstrap-components.config';
import { provideBootstrapDefaults } from '../../core/config/bootstrap.config';
import { CreditorSession } from '../data/creditor-session';
import { CreditorMe, CreditorReportedPayment } from '../data/creditor.models';
import { CreditorPayments } from './creditor-payments';

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'FINANCEIRO', roleLabel: 'Financeiro', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

const REJECTED: CreditorReportedPayment = { id: 'p1', status: 'REJECTED', amount: 300, paidOn: '2026-11-20', method: 'PIX',
  titleNumbers: ['T-60'], reference: 'REF-1', informedAt: '2026-11-21T12:00:00Z', decidedAt: '2026-11-22T12:00:00Z',
  rejectionReason: 'PROOF_INVALID' };

/** Pagamentos informados no portal do credor: só quem pode informa; o informe leva os títulos, o comprovante e o código. */
describe('pagamentos informados no portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideBootstrapDefaults(),
        provideRouter([{ path: 'credor/:slug', providers: [provideBootstrapComponentDefaults()],
          children: [{ path: 'pagamentos', component: CreditorPayments }] }])],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(NgbConfig).animation = false;
  });

  afterEach(() => TestBed.inject(NgbModal).dismissAll());

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  async function open(list: CreditorReportedPayment[]): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create('/credor/aurora/pagamentos');
    http.expectOne('/api/creditor/v1/payments').flush(list);
    await harness.fixture.whenStable();
    harness.detectChanges();
    return { harness, root: harness.routeNativeElement! };
  }

  it('perfil só de consulta vê os informes e o motivo da recusa, sem o botão de informar', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { root } = await open([REJECTED]);
    expect(root.textContent).toContain('T-60');
    expect(root.textContent).toContain('O comprovante não permite confirmar o pagamento');
    expect(root.textContent).not.toContain('Informar pagamento');
  });

  it('informe confere o título, envia o comprovante com a chave e o código', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_PAYMENT_REPORT']);
    const { harness, root } = await open([]);
    [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Informar pagamento'))!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();

    const modal = () => document.querySelector('ngb-modal-window')!;
    const number = modal().querySelector<HTMLInputElement>('input[maxlength="60"]')!;
    number.value = 'T-60';
    number.dispatchEvent(new Event('input'));
    [...modal().querySelectorAll('button')].find((b) => b.textContent?.includes('Adicionar'))!.click();
    http.expectOne((r) => r.url === '/api/creditor/v1/payments/titles' && r.params.get('number') === 'T-60')
      .flush([{ number: 'T-60', installmentNumber: 1, dueDate: '2026-09-25', openBalance: 1000, underAgreement: false }]);
    harness.detectChanges();
    expect(modal().textContent).toContain('T-60');

    const form = modal().querySelector('form')!;
    const amount = modal().querySelector<HTMLInputElement>('input[appMoney]')!;
    amount.value = '30000';
    amount.dispatchEvent(new Event('input'));
    const date = modal().querySelector<HTMLInputElement>('input[placeholder="dd/mm/aaaa"]')!;
    date.value = '20/11/2026';
    date.dispatchEvent(new Event('input'));
    date.dispatchEvent(new Event('blur'));
    const file = modal().querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(file, 'files', { value: [new File(['%PDF-1.4'], 'comprovante.pdf', { type: 'application/pdf' })] });
    file.dispatchEvent(new Event('change'));
    const code = modal().querySelector<HTMLInputElement>('input[autocomplete="one-time-code"]')!;
    code.value = '123456';
    code.dispatchEvent(new Event('input'));
    harness.detectChanges();
    form.dispatchEvent(new Event('submit'));

    const req = http.expectOne('/api/creditor/v1/payments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    const body = req.request.body as FormData;
    const report = JSON.parse(await (body.get('report') as Blob).text());
    expect(report).toEqual(expect.objectContaining({ titleNumbers: ['T-60'], amount: 300, paidOn: '2026-11-20', method: 'PIX', code: '123456' }));
    expect((body.get('evidence') as File).name).toBe('comprovante.pdf');
    req.flush({ ...REJECTED, status: 'IN_REVIEW', rejectionReason: null, decidedAt: null });
    await vi.waitFor(() => {
      harness.detectChanges();
      return http.expectOne('/api/creditor/v1/payments');
    }).then((reload) => reload.flush([]));
    harness.detectChanges();
    expect(root.textContent).toContain('Pagamento enviado para análise da assessoria');
  });
});
