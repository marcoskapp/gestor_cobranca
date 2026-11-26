import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { payment } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { PaymentApproval } from './payment-approval';

describe('PaymentApproval (decisão da fila de pagamentos)', () => {
  let http: HttpTestingController;
  let own: boolean;
  const informed = payment({ id: 'pay-1', status: 'INFORMED', source: 'EXTERNAL', amount: 900, informedBy: 'u-2' });
  let current = informed;

  beforeEach(() => {
    own = false;
    current = informed;
    TestBed.configureTestingModule({
      imports: [PaymentApproval],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(fakeUser({ id: 'u-1' })), hasPermission: (p: string) => ['PAYMENT_REVIEW', 'DEBTOR_VIEW'].includes(p), hasAnyPermission: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function reply(req: TestRequest): void {
    const url = req.request.url;
    if (url === '/api/v1/payment/payments/pay-1') return req.flush(current);
    if (url === '/api/v1/payment/payments/pay-1/review-assessment') {
      return req.flush({ paymentId: 'pay-1', reason: 'AMOUNT', limit: 500, consideredAmount: 900, excess: 400, windowHours: 24, currentPerPaymentLimit: 500, ownPayment: own });
    }
    if (url === '/api/v1/payment/payments/review-queue') {
      return req.flush({ content: [informed, payment({ id: 'pay-2' })], page: 0, size: 200, totalElements: 2, totalPages: 1 });
    }
    if (url === '/api/v1/users/display-names') return req.flush([{ id: 'u-2', name: 'Carla Operadora' }]);
    return req.flush(url.endsWith('/names') ? [] : {});
  }

  async function render(): Promise<ComponentFixture<PaymentApproval>> {
    await TestBed.inject(Router).navigateByUrl('/pagamentos/aprovacoes/pay-1?origem=AGENCY_ACCOUNT');
    const fixture = TestBed.createComponent(PaymentApproval);
    fixture.componentRef.setInput('id', 'pay-1');
    for (let i = 0; i < 5; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      http.match(() => true).forEach(reply);
    }
    fixture.detectChanges();
    return fixture;
  }

  const text = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';
  const button = (f: ComponentFixture<unknown>, label: string) =>
    [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('quanto passa do limite, quem informou e "Aprovar e abrir a próxima" com o filtro da fila', async () => {
    const fixture = await render();
    expect(text(fixture)).toContain('Pagamento 1 de 2');
    expect(text(fixture)).toContain('Valor do pagamento');
    expect(text(fixture)).toContain('(80% acima)');
    expect(text(fixture)).toContain('Carla Operadora · informou o pagamento');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Aprovar e abrir a próxima')!.click();
    http.expectOne('/api/v1/payment/payments/pay-1/approve').flush({ ...informed, status: 'CONFIRMED' });
    const queue = http.expectOne((r) => r.url === '/api/v1/payment/payments/review-queue');
    expect(queue.request.params.get('origin')).toBe('AGENCY_ACCOUNT');
    queue.flush({ content: [payment({ id: 'pay-2' })], page: 0, size: 200, totalElements: 1, totalPages: 1 });
    expect(navigate).toHaveBeenCalledWith(['/pagamentos/aprovacoes', 'pay-2'], { queryParams: { origem: 'AGENCY_ACCOUNT' } });
    http.match(() => true).forEach((r) => r.flush({}));
  });

  it('quem informou não decide: o motivo aparece e as ações somem', async () => {
    own = true;
    const fixture = await render();
    expect(text(fixture)).toContain('Você informou este pagamento');
    expect(button(fixture, 'Aprovar')).toBeUndefined();
  });

  it('integração do credor com o acesso revogado: destaque e confirmação explícita para aprovar (UX-05)', async () => {
    current = payment({
      id: 'pay-1', status: 'INFORMED', source: 'EXTERNAL', amount: 900, informedBy: 'tok-1', externalOrigin: 'CREDITOR_DIRECT',
      integration: { tokenId: 'tok-1', tokenName: 'ERP do Banco A', tokenPrefix: 'gc_ab12', tokenRevoked: true, tokenState: 'REVOKED' },
    });
    const fixture = await render();
    const note = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="integration-token-note"]')!;
    expect(note.classList).toContain('alert-warning');
    expect(note.textContent).toContain('acesso revogado');
    // sem a confirmação, aprovar não aparece; recusar continua
    expect(button(fixture, 'Aprovar')).toBeUndefined();
    expect(button(fixture, 'Recusar')).toBeDefined();
    const check = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[data-testid="integration-token-confirm"]')!;
    check.click();
    fixture.detectChanges();
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Aprovar')!.click();
    const approve = http.expectOne('/api/v1/payment/payments/pay-1/approve');
    expect(approve.request.body).toEqual({ note: null, confirmInactiveToken: true });
    approve.flush({ ...current, status: 'CONFIRMED' });
    http.match(() => true).forEach((r) => r.flush({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }));
  });

  it('a confirmação do token inativo não passa para o próximo pagamento da fila (RC-01)', async () => {
    const inactive = (id: string, state: 'REVOKED' | 'PAUSED') => payment({
      id, status: 'INFORMED', source: 'EXTERNAL', amount: 900, informedBy: 'tok-' + id, externalOrigin: 'CREDITOR_DIRECT',
      integration: { tokenId: 'tok-' + id, tokenName: 'ERP do Banco A', tokenPrefix: 'gc_ab12', tokenRevoked: state === 'REVOKED', tokenState: state },
    });
    current = inactive('pay-1', 'REVOKED');
    const fixture = await render();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLInputElement>('[data-testid="integration-token-confirm"]')!.click();
    fixture.detectChanges();
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Aprovar')!.click();
    const approve = http.expectOne('/api/v1/payment/payments/pay-1/approve');
    expect(approve.request.body).toEqual({ note: null, confirmInactiveToken: true });
    approve.flush({ ...current, status: 'CONFIRMED' });
    http.match(() => true).forEach((r) => r.flush({ content: [payment({ id: 'pay-2' })], page: 0, size: 200, totalElements: 1, totalPages: 1 }));
    // a fila reaproveita o componente: só o id muda
    const next = inactive('pay-2', 'PAUSED');
    fixture.componentRef.setInput('id', 'pay-2');
    for (let i = 0; i < 5; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 0));
      http.match(() => true).forEach((r) => {
        const url = r.request.url;
        if (url === '/api/v1/payment/payments/pay-2') return r.flush(next);
        if (url === '/api/v1/payment/payments/pay-2/review-assessment') {
          return r.flush({ paymentId: 'pay-2', reason: 'AMOUNT', limit: 500, consideredAmount: 900, excess: 400, windowHours: 24, currentPerPaymentLimit: 500, ownPayment: false });
        }
        return reply(r);
      });
    }
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="integration-token-note"]')!.textContent).toContain('pausado pela plataforma');
    expect(host.querySelector<HTMLInputElement>('[data-testid="integration-token-confirm"]')!.checked).toBe(false);
    expect(button(fixture, 'Aprovar')).toBeUndefined();
    expect(text(fixture)).toContain('marque no aviso da integração');
  });

  it('pagamento comum não pede a confirmação nem envia o campo', async () => {
    const fixture = await render();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="integration-token-note"]')).toBeNull();
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Aprovar')!.click();
    expect(http.expectOne('/api/v1/payment/payments/pay-1/approve').request.body).toEqual({ note: null });
    http.match(() => true).forEach((r) => r.flush({}));
  });
});
