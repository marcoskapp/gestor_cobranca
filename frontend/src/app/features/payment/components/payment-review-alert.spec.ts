import { render, setup, text } from '../../../../testing/component-harness';
import { reviewItem } from '../../../../testing/payment-review-fixtures';
import { PaymentReviewAlert } from './payment-review-alert';

const names = (url: string) => (url.endsWith('/users/display-names') ? [{ id: 'u-2', name: 'Carla Operadora' }, { id: 'u-3', name: 'Fábio Financeiro' }] : undefined);

describe('PaymentReviewAlert', () => {
  it('pendente: valor, quando, quem, parcela coberta, link para o pagamento e explicação da baixa', async () => {
    const { http } = setup(PaymentReviewAlert, ['AGREEMENT_VIEW', 'CASE_VIEW', 'PAYMENT_VIEW']);
    const fixture = await render(PaymentReviewAlert, http, names, {
      pending: [reviewItem()],
      coverage: [{ installmentId: 'i-2', paymentId: 'pg-1', amount: 1350 }],
      installmentLabels: new Map([['i-2', '2ª parcela']]),
    });
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(t).toContain('Pagamento de R$ 1.350,00 informado em 25/10/2026');
    expect(t).toContain('por Carla Operadora aguardando aprovação de outra pessoa');
    expect(t).toContain('Destino previsto: 2ª parcela.');
    expect(t).toContain('A parcela só é baixada depois da aprovação');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('.alert-warning')!;
    expect(alert.getAttribute('role')).toBe('status');
    const link = alert.querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/pagamentos/pg-1');
    expect(link.getAttribute('aria-label')).toMatch(/^Ver pagamento de R\$\s1\.350,00 aguardando aprovação$/);
    http.verify();
  });

  it('sem PAYMENT_VIEW não há link; vários pendentes mostram o total; quem informou é "você"', async () => {
    const { http } = setup(PaymentReviewAlert, ['AGREEMENT_VIEW', 'CASE_VIEW'], 'u-2');
    const fixture = await render(PaymentReviewAlert, http, names, {
      pending: [reviewItem(), reviewItem({ paymentId: 'pg-2', amount: 100, requestedItemIds: [] })],
      coverage: [
        { installmentId: 'i-2', paymentId: 'pg-1', amount: 1350 },
        { installmentId: 'i-3', paymentId: 'pg-2', amount: 100 },
      ],
      installmentLabels: new Map([['i-2', '2ª parcela'], ['i-3', '3ª parcela']]),
    });
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(t).toContain('2 pagamentos informados aguardando aprovação de outra pessoa · total R$ 1.450,00');
    expect(t).toContain('por você');
    expect(t).toContain('Destino previsto: 3ª parcela.');
    expect((fixture.nativeElement as HTMLElement).querySelector('a[href^="/pagamentos"]')).toBeNull();
    http.verify();
  });

  it('recusado: motivo para quem pode ver, aviso de motivo restrito e o que fazer', async () => {
    const { http } = setup(PaymentReviewAlert, ['AGREEMENT_VIEW', 'CASE_VIEW']);
    const fixture = await render(PaymentReviewAlert, http, names, {
      rejected: [
        reviewItem({ paymentId: 'pg-3', status: 'REJECTED', reviewedBy: 'u-3', reviewedAt: '2026-10-26T10:00:00Z', rejectionReason: 'Comprovante ilegível' }),
        reviewItem({ paymentId: 'pg-4', status: 'REJECTED', amount: 80, reviewedBy: 'u-3', reviewedAt: '2026-10-20T10:00:00Z', reasonRestricted: true }),
      ],
    });
    const t = text(fixture).replace(/\s+/g, ' ');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('.alert-danger')!;
    expect(alert.getAttribute('role')).toBe('status');
    expect(t).toContain('2 pagamentos recusados nos últimos 30 dias');
    expect(t).toContain('foi recusado em 26/10/2026');
    expect(t).toContain('por Fábio Financeiro');
    expect(t).toContain('Motivo: Comprovante ilegível');
    expect(t).toContain('O motivo fica visível para quem informou e para quem consulta pagamentos.');
    expect(t).toContain('Nada foi baixado.');
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert-warning')).toBeNull();
    http.verify();
  });

  it('compacto (ficha do caso): texto curto, atalho para o acordo e título avulso sem destino de parcela', async () => {
    const { http } = setup(PaymentReviewAlert, ['AGREEMENT_VIEW', 'CASE_VIEW']);
    const fixture = await render(PaymentReviewAlert, http, names, {
      compact: true,
      pending: [reviewItem(), reviewItem({ paymentId: 'pg-5', targetKind: 'TITLES', agreementId: null, requestedItemIds: ['t-1'], amount: 700 })],
    });
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(t).toContain('Só baixa depois da aprovação.');
    expect(t).not.toContain('Destino previsto');
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/negociacao/acordos/a-1']);
    expect((fixture.nativeElement as HTMLElement).querySelector('.pra--compact')).not.toBeNull();
    http.verify();
  });

  it('nada pendente nem recusado: não renderiza aviso', async () => {
    const { http } = setup(PaymentReviewAlert, ['AGREEMENT_VIEW']);
    const fixture = await render(PaymentReviewAlert, http, names, {});
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert')).toBeNull();
    http.verify();
  });
});
