import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { reviewItem } from '../../../../testing/payment-review-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { AgreementView } from '../data/agreement.models';
import { AgreementDetail } from './agreement-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('AgreementDetail', () => {
  let http: HttpTestingController;
  const permissions = ['AGREEMENT_VIEW', 'AGREEMENT_CANCEL', 'NEGOTIATION_SIMULATE', 'NEGOTIATION_PROPOSE', 'DEBTOR_VIEW'];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AgreementDetail],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser({ id: 'u-1' })), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  let reviewStatus: unknown = { agreementId: 'a-1', pendingCount: 0, pendingTotal: 0, pending: [], rejected: [], rejectedSince: '2026-09-26T00:00:00Z', expectedCoverage: [] };

  async function render(view: AgreementView): Promise<ComponentFixture<AgreementDetail>> {
    const fixture = TestBed.createComponent(AgreementDetail);
    fixture.componentRef.setInput('id', view.id);
    for (let i = 0; i < 6; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === `/api/v1/negotiation/agreements/${view.id}`) req.flush(view);
        else if (url === `/api/v1/payment/review-status/agreements/${view.id}`) req.flush(reviewStatus as object);
        else if (url.endsWith('/payments')) req.flush([{ paymentId: 'pg-1', amount: 300, appliedAmount: 300, unappliedAmount: 0, paidAt: '2026-10-25T12:00:00Z', status: 'APPLIED', ledgerStatus: 'POSTED', reversedAt: null }]);
        else if (url === '/api/v1/debtors/d-1') req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**' });
        else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        else if (url === '/api/v1/users/display-names') req.flush([{ id: 'u-2', name: 'Carla Operadora' }, { id: 'u-3', name: 'Fábio Financeiro' }]);
        else req.flush({});
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('mostra cronograma com situação das parcelas, pagamentos e participantes', async () => {
    const fixture = await render(agreement());
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Acordo · Ana Paula Ribeiro');
    expect(text).toContain('1 de 3 parcelas pagas');
    expect(el(fixture).querySelectorAll('tbody tr').length).toBeGreaterThanOrEqual(4);
    expect(text).toContain('Entrada');
    expect(text).toContain('Paga');
    expect(text).toContain('A vencer');
    expect(text).toContain('Lançado no razão');
    expect(text).toContain('Formalizou');
    expect(el(fixture).querySelector('.ad-next')?.textContent).toContain('25/11/2026');
  });

  it('cancelar exige motivo, mostra o impacto e pede CANCELAR', async () => {
    const fixture = await render(agreement());
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of('Devedor desistiu'));
    button(fixture, 'Cancelar acordo')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data).toMatchObject({ reason: 'required', confirmationText: 'CANCELAR', destructive: true });
    expect(data.details).toEqual(expect.arrayContaining([{ label: 'Parcelas em aberto', value: '2 de 3' }, { label: 'Próximo vencimento', value: '25/11/2026' }]));
    const req = http.expectOne('/api/v1/negotiation/agreements/a-1/cancel');
    expect(req.request.body).toEqual({ reason: 'Devedor desistiu' });
    req.flush(agreement({ status: 'CANCELLED' }));
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/negotiation/agreements/a-1').flush(agreement({ status: 'CANCELLED' }));
    fixture.detectChanges();
    await tick();
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('quebrado: sem cancelar, renegociar abre o simulador com o acordo a substituir', async () => {
    const fixture = await render(agreement({ status: 'BROKEN', statusReason: '2 parcelas vencidas' }));
    expect(button(fixture, 'Cancelar acordo')).toBeUndefined();
    expect(el(fixture).textContent).toContain('Acordo quebrado: 2 parcelas vencidas');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Renegociar')!.click();
    expect(navigate).toHaveBeenCalledWith(['/negociacao/simulador'], { queryParams: { credor: 'c-1', devedor: 'd-1', caso: 'k-1', renegociar: 'a-1' } });
  });

  it('Pix por parcela em aberto: abre o diálogo de pagamento com a parcela e o saldo', async () => {
    permissions.push('PAYMENT_INSTRUCTION_CREATE');
    try {
      const fixture = await render(agreement());
      const launch = vi.spyOn(TestBed.inject(PaymentLauncher), 'instruction').mockReturnValue(of(undefined));
      const pix = [...el(fixture).querySelectorAll<HTMLButtonElement>('button[aria-label^="Gerar Pix da parcela"]')];
      // parcela 1 paga: só as duas em aberto
      expect(pix.map((b) => b.getAttribute('aria-label'))).toEqual(['Gerar Pix da parcela 2', 'Gerar Pix da parcela 3']);
      pix[0].click();
      expect(launch).toHaveBeenCalledWith(
        expect.objectContaining({
          target: { kind: 'INSTALLMENT', creditorId: 'c-1', agreementId: 'a-1', installmentId: 'i-2', installmentNumber: 2, dueDate: '2026-11-25', balance: 1350 },
          debtorId: 'd-1',
          debtorName: 'Ana Paula Ribeiro',
        }),
      );
    } finally {
      permissions.pop();
    }
  });

  it('acordo encerrado não oferece Pix', async () => {
    permissions.push('PAYMENT_INSTRUCTION_CREATE');
    try {
      const fixture = await render(agreement({ status: 'SETTLED' }));
      expect(el(fixture).querySelectorAll('button[aria-label^="Gerar Pix"]').length).toBe(0);
    } finally {
      permissions.pop();
    }
  });

  it('pagamento informado aguardando aprovação: aviso com valor, data, quem informou, parcela coberta e selo na parcela', async () => {
    reviewStatus = {
      agreementId: 'a-1',
      pendingCount: 1,
      pendingTotal: 1350,
      pending: [reviewItem()],
      rejected: [reviewItem({ paymentId: 'pg-3', status: 'REJECTED', amount: 200, reviewedBy: 'u-3', reviewedAt: '2026-10-26T10:00:00Z', rejectionReason: 'Comprovante de outro devedor' })],
      rejectedSince: '2026-09-26T00:00:00Z',
      expectedCoverage: [{ installmentId: 'i-2', paymentId: 'pg-1', amount: 1350 }],
    };
    try {
      const fixture = await render(agreement());
      const t = el(fixture).textContent!.replace(/\s+/g, ' ');
      expect(t).toContain('Pagamento de R$ 1.350,00 informado em 25/10/2026');
      expect(t).toContain('por Carla Operadora aguardando aprovação de outra pessoa');
      expect(t).toContain('Destino previsto: 2ª parcela.');
      expect(t).toContain('A parcela só é baixada depois da aprovação');
      expect(t).toContain('Motivo: Comprovante de outro devedor');
      // selo só na parcela coberta; sem PAYMENT_VIEW, sem link para o pagamento
      const rows = [...el(fixture).querySelectorAll('table[aria-label="Parcelas do acordo"] tbody tr')];
      expect(rows.map((r) => !!r.querySelector('.ad-review'))).toEqual([false, true, false]);
      expect(rows[1].querySelector('.ad-review')!.textContent!.replace(/\s+/g, ' ')).toContain('Em revisão: R$ 1.350,00 informado, aguardando aprovação');
      expect(el(fixture).querySelector('a[href^="/pagamentos/"]')).toBeNull();
    } finally {
      reviewStatus = { agreementId: 'a-1', pendingCount: 0, pendingTotal: 0, pending: [], rejected: [], rejectedSince: '2026-09-26T00:00:00Z', expectedCoverage: [] };
    }
  });

  it('sem pendência nem recusa: nenhum aviso de pagamento em revisão', async () => {
    const fixture = await render(agreement());
    expect(el(fixture).querySelector('app-payment-review-alert .alert')).toBeNull();
    expect(el(fixture).querySelector('.ad-review')).toBeNull();
  });
});
