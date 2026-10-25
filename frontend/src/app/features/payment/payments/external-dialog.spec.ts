import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { payment } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { ExternalDialog } from './external-dialog';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('ExternalDialog', () => {
  let http: HttpTestingController;
  const close = vi.fn();
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const permissions = ['PAYMENT_VIEW', 'PAYMENT_REGISTER_EXTERNAL', 'AGREEMENT_VIEW', 'DEBTOR_VIEW'];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ExternalDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: { debtorId: 'd-1', debtorName: 'Ana Paula Ribeiro' } },
        { provide: DialogRef, useValue: { close } },
        { provide: NotificationService, useValue: notifications },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser()), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  async function render(): Promise<ComponentFixture<ExternalDialog>> {
    const fixture = TestBed.createComponent(ExternalDialog);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === '/api/v1/payment/settings') req.flush({ externalReviewThreshold: 500, pixExpirationSeconds: 86400, pixDueValidityDays: 30, reconciliationDateToleranceDays: 3, customized: false, updatedAt: null });
        else if (url === '/api/v1/negotiation/agreements') req.flush({ content: [agreement()], page: 0, size: 50, totalElements: 1, totalPages: 1 });
        else if (url === '/api/v1/titles') req.flush({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
        else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        else req.flush({});
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const submit = (f: ComponentFixture<unknown>) => el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const pdf = new File(['%PDF-1.7'], 'comprovante.pdf', { type: 'application/pdf' });

  it('exige comprovante e destino; avisa a segunda aprovação acima do limite', async () => {
    const fixture = await render();
    fixture.componentInstance['form'].patchValue({ amount: '800,00' });
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('outra pessoa');
    expect(submit(fixture).textContent).toContain('Enviar para aprovação');
    submit(fixture).click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Anexe o comprovante do pagamento.');
    http.expectNone((r) => r.method === 'POST');

    fixture.componentInstance['pick']([new File(['x'], 'nota.docx', { type: 'application/msword' })] as unknown as FileList);
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('PDF, PNG ou JPEG');
  });

  it('envia o multipart com o pagamento em JSON e o comprovante, com o acordo escolhido', async () => {
    const fixture = await render();
    // único acordo em vigor: escolhido automaticamente, com as parcelas em aberto na ordem
    expect(fixture.componentInstance['target']()).toMatchObject({ kind: 'AGREEMENT', agreementId: 'a-1', itemIds: [] });
    fixture.componentInstance['form'].patchValue({ amount: '300', method: 'PIX', externalReference: ' E2E-1 ' });
    fixture.componentInstance['pick']([pdf] as unknown as FileList);
    fixture.detectChanges();
    expect(submit(fixture).textContent).toContain('Registrar pagamento');
    submit(fixture).click();
    const req: TestRequest = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/payments/external');
    const body = req.request.body as FormData;
    expect(body.get('evidence')).toBeInstanceOf(File);
    const json = JSON.parse(await (body.get('payment') as Blob).text());
    expect(json).toMatchObject({
      creditorId: 'c-1',
      debtorId: 'd-1',
      amount: 300,
      method: 'PIX',
      origin: 'AGENCY_ACCOUNT',
      externalReference: 'E2E-1',
      note: null,
      target: { kind: 'AGREEMENT', agreementId: 'a-1', itemIds: [], strategy: null },
    });
    expect(Date.parse(json.paidAt)).toBeLessThanOrEqual(Date.now());
    expect(req.request.headers.get('Idempotency-Key')).toBeTruthy();
    req.flush(payment({ status: 'CONFIRMED', amount: 300 }));
    expect(close).toHaveBeenCalled();
    expect(notifications.success).toHaveBeenCalledWith(expect.stringContaining('confirmado'));
  });

  it('lê a data digitada em dd/mm/aaaa (modelo ISO, sem fuso) e recusa data futura', async () => {
    const fixture = await render();
    const form = fixture.componentInstance['form'];
    const input = el(fixture).querySelector<HTMLInputElement>('input[formcontrolname="paidOn"]')!;
    const type = (value: string) => {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
    };
    type('20/10/2025');
    expect(form.controls.paidOn.value).toBe('2025-10-20');
    expect(form.controls.paidOn.valid).toBe(true);
    type('01/01/2999');
    expect(form.controls.paidOn.invalid).toBe(true);
    expect(el(fixture).textContent).toContain('Informe a data (não pode ser futura).');
    expect(input.getAttribute('aria-invalid')).toBe('true');
  });
});
