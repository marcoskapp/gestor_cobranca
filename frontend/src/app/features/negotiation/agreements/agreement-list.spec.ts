import { page, render, setup } from '../../../../testing/component-harness';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { AgreementList } from './agreement-list';

describe('AgreementList', () => {
  const rows = [agreement({ id: 'a-1' }), agreement({ id: 'a-2' }), agreement({ id: 'a-3' })];

  it('selo de pagamento em revisão e recusado por linha, numa única consulta para a página', async () => {
    const { http } = setup(AgreementList, ['AGREEMENT_VIEW', 'CREDITOR_VIEW', 'DEBTOR_VIEW']);
    const badgeCalls: string[] = [];
    const fixture = await render(AgreementList, http, (url, req) => {
      if (url.endsWith('/negotiation/agreements')) return page(rows);
      if (url.endsWith('/payment/review-status/agreements')) {
        badgeCalls.push(req.request.params.get('ids') ?? '');
        return [
          { agreementId: 'a-1', pendingCount: 1, pendingTotal: 1350, lastInformedAt: '2026-10-25T13:30:00Z', rejectedCount: 0, lastRejectedAt: null },
          { agreementId: 'a-3', pendingCount: 0, pendingTotal: 0, lastInformedAt: null, rejectedCount: 2, lastRejectedAt: '2026-10-26T10:00:00Z' },
        ];
      }
      return undefined;
    });
    expect(badgeCalls).toEqual(['a-1,a-2,a-3']);
    const lines = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')];
    expect(lines).toHaveLength(3);
    const badges = lines.map((tr) => tr.querySelector('.al-review')?.textContent?.replace(/\s+/g, ' ').trim() ?? '');
    expect(badges[0]).toContain('Pagamento em revisão');
    expect(badges[0]).toContain('1 informado(s), R$ 1.350,00, aguardando aprovação');
    expect(badges[1]).toBe('');
    expect(badges[2]).toContain('Pagamento recusado');
    expect(badges[2]).not.toContain('Pagamento em revisão');
    http.verify();
  });

  it('sem AGREEMENT_VIEW não consulta a situação de revisão', async () => {
    const { http } = setup(AgreementList, ['CREDITOR_VIEW']);
    const urls: string[] = [];
    await render(AgreementList, http, (url) => {
      urls.push(url);
      return url.endsWith('/negotiation/agreements') ? page(rows) : undefined;
    });
    expect(urls.some((u) => u.includes('/payment/review-status'))).toBe(false);
    http.verify();
  });
});
