import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { payment } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { PaymentView } from '../data/payment.models';
import { PaymentDetail } from './payment-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('PaymentDetail', () => {
  let http: HttpTestingController;
  let userId = 'u-1';
  const confirm = { confirm: vi.fn() };
  const open = vi.fn();
  const permissions = ['PAYMENT_VIEW', 'PAYMENT_REVIEW', 'PAYMENT_REVERSE', 'RECONCILIATION_MANAGE', 'AGREEMENT_VIEW', 'DEBTOR_VIEW'];

  beforeEach(() => {
    userId = 'u-1';
    TestBed.configureTestingModule({
      imports: [PaymentDetail],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ConfirmService, useValue: confirm },
        { provide: DialogService, useValue: { open } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
        {
          provide: AuthService,
          useFactory: () => ({
            user: signal(fakeUser({ id: userId })),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          }),
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  async function render(view: PaymentView): Promise<ComponentFixture<PaymentDetail>> {
    const fixture = TestBed.createComponent(PaymentDetail);
    fixture.componentRef.setInput('id', view.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === `/api/v1/payment/payments/${view.id}`) req.flush(view);
        else if (url === '/api/v1/negotiation/agreements/a-1') req.flush(agreement());
        else if (url === '/api/v1/debtors/names') req.flush([{ id: 'd-1', name: 'Ana Paula Ribeiro', status: 'ACTIVE' }]);
        else if (url === '/api/v1/users/display-names') req.flush([{ id: 'u-2', name: 'Carla Operadora' }, { id: 'u-3', name: 'Diego Financeiro' }]);
        else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        else req.flush({});
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim().startsWith(label) || b.textContent?.includes(label));

  it('mostra destinos com o número da parcela, a linha do tempo e quem aprovou', async () => {
    const fixture = await render(payment());
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Pagamento · Ana Paula Ribeiro');
    expect(text).toContain('2ª parcela');
    expect(text).toContain('Baixado');
    expect(text).toContain('Pagamento informado');
    expect(text).toContain('Carla Operadora');
    expect(text).toContain('Aprovado na revisão');
    expect(text).toContain('Diego Financeiro');
    expect(button(fixture, 'Estornar')).toBeTruthy();
    expect(button(fixture, 'Aprovar')).toBeFalsy();
  });

  it('quem informou vê a explicação e não tem o botão de aprovar', async () => {
    const fixture = await render(payment({ status: 'INFORMED', informedBy: 'u-1', reviewedAt: null, reviewedBy: null, reviewMode: null, confirmedAt: null, allocations: [], allocatedAmount: 0 }));
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Você informou este pagamento');
    expect(text).toContain('outra pessoa');
    expect(button(fixture, 'Aprovar')).toBeFalsy();
    expect(button(fixture, 'Estornar')).toBeFalsy();
    // quem aprova (e quem informou) vê para onde o valor vai antes de existir alocação
    expect(text).toContain('Destino solicitado por quem informou');
    expect(text).toContain('2ª parcela');
  });

  it('revisor aprova; recusa do servidor por segregação aparece na tela', async () => {
    const view = payment({ status: 'INFORMED', informedBy: 'u-2', reviewedAt: null, reviewedBy: null, reviewMode: null, confirmedAt: null });
    const fixture = await render(view);
    confirm.confirm.mockReturnValue(of('Conferido com o extrato'));
    button(fixture, 'Aprovar')!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Aprovar pagamento informado', reason: 'optional' }));
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/payments/pg-1/approve');
    expect(req.request.body).toEqual({ note: 'Conferido com o extrato' });
    req.flush({ code: 'payment.self_review', detail: 'Quem informou o pagamento não pode revisá-lo' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="alert"]')?.textContent).toContain('A aprovação precisa ser feita por outra pessoa');
  });

  it('estorno abre o diálogo com motivo e palavra de confirmação', async () => {
    const fixture = await render(payment());
    open.mockReturnValue({ afterClosed: () => of(undefined) });
    button(fixture, 'Estornar')!.click();
    expect(open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ debtorName: 'Ana Paula Ribeiro' }) }));
  });

  it('pagamento informado sem destino registrado mantém a explicação genérica', async () => {
    userId = 'u-9';
    const fixture = await render(payment({ status: 'INFORMED', informedBy: 'u-2', reviewedAt: null, reviewedBy: null, reviewMode: null, confirmedAt: null, allocations: [], allocatedAmount: 0, requestedTarget: null }));
    expect(el(fixture).textContent).toContain('O destino escolhido por quem informou é aplicado na aprovação.');
  });
});
