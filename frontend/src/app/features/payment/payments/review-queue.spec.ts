import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { payment } from '../../../../testing/payment-fixtures';
import { breakSuspension } from '../../../../testing/payment-review-fixtures';
import { ReviewQueue } from './review-queue';

describe('ReviewQueue (aprovação de pagamentos)', () => {

  it('mostra para onde o valor vai (destino solicitado) antes de aprovar', async () => {
    const { http } = setup(ReviewQueue, ['PAYMENT_REVIEW', 'AGREEMENT_VIEW', 'DEBTOR_VIEW']);
    const informed = payment({ id: 'pg-9', status: 'INFORMED', allocations: [], informedBy: 'u-2', requestedTarget: { kind: 'AGREEMENT', agreementId: 'a-1', itemIds: ['i-2', 'i-3'], strategy: null } });
    const titles = payment({ id: 'pg-10', status: 'INFORMED', allocations: [], informedBy: 'u-2', requestedTarget: { kind: 'TITLES', agreementId: null, itemIds: ['t-1'], strategy: 'OLDEST_FIRST' } });
    const fixture = await render(ReviewQueue, http, (url) => {
      if (url.endsWith('/payment/payments/review-queue')) return page([informed, titles]);
      if (url.endsWith('/negotiation/agreements/a-1')) return agreement();
      if (url.endsWith('/titles/t-1')) return { id: 't-1', externalId: 'CRED-77', installmentNumber: 4 };
      if (url.endsWith('/debtors/names')) return [{ id: 'd-1', name: 'Ana Paula Ribeiro', status: 'ACTIVE' }];
      if (url.endsWith('/users/display-names')) return [{ id: 'u-2', name: 'Carla Operadora' }];
      return undefined;
    });
    const t = text(fixture);
    expect(t).toContain('Destino solicitado');
    expect(t).toContain('2ª e 3ª parcelas');
    expect(t).toContain('Título CRED-77/4 · vencidos primeiro');
    expect(t).toContain('quem informa não aprova');
    expect(button(fixture, 'Revisar')).toBeTruthy();
    http.verify();
  });

  it('destaca a quebra suspensa pelo pagamento e o prazo de suspensão vencido', async () => {
    const { http } = setup(ReviewQueue, ['PAYMENT_REVIEW', 'AGREEMENT_VIEW', 'DEBTOR_VIEW']);
    const target = { kind: 'AGREEMENT' as const, agreementId: 'a-1', itemIds: [], strategy: null };
    const suspended = payment({ id: 'pg-11', status: 'INFORMED', allocations: [], informedBy: 'u-2', requestedTarget: target, breakSuspension: breakSuspension({ suspendedUntil: '2999-01-02' }) });
    const expired = payment({
      id: 'pg-12',
      status: 'INFORMED',
      allocations: [],
      informedBy: 'u-2',
      requestedTarget: target,
      breakSuspension: breakSuspension({ status: 'ENDED', endReason: 'DEADLINE', endedAt: '2026-11-03T03:20:00Z' }),
    });
    const plain = payment({ id: 'pg-13', status: 'INFORMED', allocations: [], informedBy: 'u-2', requestedTarget: target, breakSuspension: null });
    const fixture = await render(ReviewQueue, http, (url) => {
      if (url.endsWith('/payment/payments/review-queue')) return page([suspended, expired, plain]);
      if (url.endsWith('/negotiation/agreements/a-1')) return agreement();
      if (url.endsWith('/debtors/names')) return [{ id: 'd-1', name: 'Ana Paula Ribeiro', status: 'ACTIVE' }];
      if (url.endsWith('/users/display-names')) return [{ id: 'u-2', name: 'Carla Operadora' }];
      return undefined;
    });
    const badges = [...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="break-hold"]')];
    expect(badges.map((b) => b.textContent!.trim())).toEqual(['Quebra suspensa até 02/01/2999', 'Prazo de suspensão vencido: a quebra voltou a valer']);
    expect(badges[0].classList).toContain('bg-warning-subtle');
    expect(badges[1].classList).toContain('bg-danger-subtle');
    http.verify();
  });
});
