import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideAppLocale } from '../../core/config/app-locale';
import { OffersView } from '../data/portal.models';
import { PortalOffers } from './portal-offers';

const OFFERS: OffersView = {
  simulationId: 'sim-1', portfolioId: 'p1', updatedAmount: 1800, referenceDate: '2026-10-23', maxInstallments: 6,
  maxDaysToFirstPayment: 10, proposalValidityDays: 5, breakOverdueInstallments: 2, breakToleranceDays: 5,
  offers: [
    { level: 1, installments: 1, downPayment: 1500, installmentAmount: 1500, total: 1500, discount: 300, discountPercent: 16.6,
      financingInterest: 0, firstDueDate: '2026-10-23', schedule: [{ number: 1, dueDate: '2026-10-23', amount: 1500 }] },
    { level: 1, installments: 3, downPayment: 600, installmentAmount: 600, total: 1800, discount: 0, discountPercent: 0,
      financingInterest: 0, firstDueDate: '2026-10-23', schedule: [1, 2, 3].map((n) => ({ number: n, dueDate: '2026-10-23', amount: 600 })) },
  ],
};

describe('PortalOffers', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PortalOffers],
      providers: [provideAppLocale(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('mostra só as ofertas recebidas e envia o aceite com a oferta escolhida e o "li e aceito"', async () => {
    const fixture = TestBed.createComponent(PortalOffers);
    fixture.componentRef.setInput('slug', 'banco-a');
    fixture.componentRef.setInput('portfolioId', 'p1');
    fixture.detectChanges();
    http.expectOne('/api/portal/v1/me/portfolios/p1/offers').flush(OFFERS);
    await fixture.whenStable();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('À vista');
    expect(el.textContent).toContain('3x de');
    const buttons = () => Array.from(el.querySelectorAll('button')) as HTMLButtonElement[];
    buttons().find((b) => b.textContent?.includes('Continuar'))!.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Confira antes de fechar');
    expect(el.textContent).toContain('2 parcelas ficarem');
    const close = buttons().find((b) => b.textContent?.includes('Fechar acordo'))!;
    expect(close.disabled).toBe(true);
    (el.querySelector('input[type=checkbox]') as HTMLInputElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    buttons().find((b) => b.textContent?.includes('Fechar acordo'))!.click();
    const req = http.expectOne('/api/portal/v1/me/agreements');
    expect(req.request.body).toEqual({ simulationId: 'sim-1', level: 1, installments: 1, termsAccepted: true });
    req.flush({ agreement: { id: 'a1', installments: [{ id: 'i1', number: 1, dueDate: '2026-10-23', amount: 1500, paidAmount: 0, status: 'OPEN', pixStatus: 'ACTIVE' }] },
      pix: { instructionId: 'x', agreementId: 'a1', installmentId: 'i1', installmentNumber: 1, status: 'ACTIVE', amount: 1500, expiresAt: null, copyPaste: '000201pix', paidAt: null },
      pixError: null });
    fixture.detectChanges();
    expect(el.textContent).toContain('Acordo fechado!');
    expect(el.textContent).toContain('000201pix');
  });
});
