import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideAppLocale } from '../../core/config/app-locale';
import { DebtsView, PortfolioDebt, TitleSummary } from '../data/portal.models';
import { PortalDebts } from './portal-debts';

const title = (id: string, ref: string, inOffer: boolean): TitleSummary => ({
  titleId: id, reference: ref, dueDate: '2026-09-10', daysOverdue: 70, openBalance: 500, status: 'OPEN', inOffer,
});

const debt = (over: Partial<PortfolioDebt>): DebtsView => ({
  creditorName: 'Banco Fictício',
  portfolios: [{
    portfolioId: 'p1', portfolioName: 'Cartão', openBalance: 1000, updatedAmount: 620, negotiable: true,
    unavailableReason: null, activeAgreementId: null,
    titles: [title('t1', 'final 1111', true), title('t2', 'final 2222', false)], ...over,
  }],
});

describe('PortalDebts', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PortalDebts],
      providers: [provideAppLocale(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(view: DebtsView): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(PortalDebts);
    fixture.componentRef.setInput('slug', 'banco-a');
    fixture.detectChanges();
    http.expectOne('/api/portal/v1/me/debts').flush(view);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  it('separa as parcelas que ficam fora do valor atualizado, sem dizer o motivo', async () => {
    const el = await render(debt({}));
    const text = el.textContent ?? '';
    expect(text).toContain('Não inclui 1 parcela, que está na lista abaixo. Para ela, fale com um atendente.');
    expect(text).toContain('Falar com atendente sobre a parcela fora');
    const lists = Array.from(el.querySelectorAll('ul.portal-list'));
    expect(lists.length).toBe(2);
    expect(lists[0].getAttribute('aria-labelledby')).toBe('dentro-p1');
    expect(lists[0].textContent).toContain('final 1111');
    expect(lists[1].getAttribute('aria-labelledby')).toBe('fora-p1');
    expect(lists[1].textContent).toContain('final 2222');
  });

  it('no plural, avisa quantas parcelas ficam fora e omite a lista de dentro vazia', async () => {
    const el = await render(debt({ titles: [title('t1', 'final 1111', true), title('t2', 'final 2222', false), title('t3', 'final 3333', false)] }));
    expect(el.textContent).toContain('Não inclui 2 parcelas, que estão na lista abaixo. Para elas, fale com um atendente.');
    expect(el.textContent).toContain('Falar com atendente sobre as parcelas fora');
    const empty = await render(debt({ titles: [title('t2', 'final 2222', false)] }));
    expect(empty.querySelectorAll('ul.portal-list').length).toBe(1);
    expect(empty.querySelector('#dentro-p1')).toBeNull();
  });

  it('com todas as parcelas no valor, mostra uma lista só e nenhum aviso', async () => {
    const el = await render(debt({ titles: [title('t1', 'final 1111', true), title('t2', 'final 2222', true)] }));
    expect(el.querySelectorAll('ul.portal-list').length).toBe(1);
    expect(el.textContent).not.toContain('Não inclui');
    expect(el.textContent).not.toContain('parcela fora');
  });

  it('sem condição no portal, não separa as parcelas (o aviso da dívida já explica)', async () => {
    const el = await render(debt({ updatedAmount: null, negotiable: false, unavailableReason: 'NOT_ELIGIBLE',
      titles: [title('t1', 'final 1111', false), title('t2', 'final 2222', false)] }));
    expect(el.querySelectorAll('ul.portal-list').length).toBe(1);
    expect(el.textContent).not.toContain('Não inclui');
    expect(el.textContent).toContain('Valor em aberto');
  });
});
