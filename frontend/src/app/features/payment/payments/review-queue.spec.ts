import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { payment } from '../../../../testing/payment-fixtures';
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
});
