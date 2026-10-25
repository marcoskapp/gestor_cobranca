import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { agreement, proposal } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { CaseNegotiation } from './case-negotiation';

const tick = () => new Promise((r) => setTimeout(r, 0));
const page = <T>(content: T[]) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

describe('CaseNegotiation (aba da estação)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CaseNegotiation],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasPermission: () => true, hasAnyPermission: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(): Promise<ComponentFixture<CaseNegotiation>> {
    const fixture = TestBed.createComponent(CaseNegotiation);
    fixture.componentRef.setInput('caseId', 'k-1');
    fixture.componentRef.setInput('creditorId', 'c-1');
    fixture.componentRef.setInput('debtorId', 'd-1');
    fixture.detectChanges();
    await tick();
    const proposals = http.expectOne((r) => r.url === '/api/v1/negotiation/proposals');
    // filtro por caso no servidor (sem trazer as propostas do devedor em outros casos)
    expect(proposals.request.params.get('caseId')).toBe('k-1');
    expect(proposals.request.params.has('debtorId')).toBe(false);
    proposals.flush(page([proposal({ id: 'p-1', status: 'PENDING_APPROVAL' }), proposal({ id: 'p-3', status: 'EXPIRED' })]));
    const agreements = http.expectOne((r) => r.url === '/api/v1/negotiation/agreements');
    expect(agreements.request.params.get('caseId')).toBe('k-1');
    agreements.flush(page([agreement({ installments: [...agreement().installments.slice(0, 2), { ...agreement().installments[2], status: 'OVERDUE' }] })]));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    return fixture;
  }

  it('lista só propostas abertas do caso e acordos com o progresso do cronograma', async () => {
    const fixture = await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Aguardando aprovação');
    expect(text).not.toContain('Expirada');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('a[href^="/negociacao/propostas/"]').length).toBe(1);
    expect(text).toContain('1 de 3 pagas');
    expect(text).toContain('1 vencida(s)');
  });

  it('gera Pix da próxima parcela do acordo em vigor durante o atendimento', async () => {
    const fixture = await render();
    const launch = vi.spyOn(TestBed.inject(PaymentLauncher), 'instruction').mockReturnValue(of(undefined));
    const button = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Gerar Pix da 2ª parcela'))!;
    button.click();
    expect(launch).toHaveBeenCalledWith(expect.objectContaining({ debtorId: 'd-1', target: expect.objectContaining({ kind: 'INSTALLMENT', installmentId: 'i-2', balance: 1350 }) }));
  });
});
