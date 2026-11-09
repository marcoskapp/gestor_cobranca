import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup } from '../../../../testing/component-harness';
import { CaseList } from '../../collection/cases/case-list';
import { DivergenceList } from '../../payment/reconciliation/divergence-list';
import { ReceiptList } from '../../settlement/ledger/receipt-list';

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

/**
 * As listas de destino do drill-down leem os parâmetros que o painel manda e os repassam ao servidor (a base do número
 * é a mesma); a contagem do "Filtros (n)" do `app-filter-bar` acompanha o que veio na URL.
 */
describe('listas de destino do drill-down dos painéis', () => {
  async function open<T>(component: Type<T>, permissions: string[], url: string) {
    const { http } = setup(component, permissions);
    await TestBed.inject(Router).navigateByUrl(url);
    const requests: { url: string; params: Record<string, string | null> }[] = [];
    const fixture = await render(component, http, (u, req) => {
      const params: Record<string, string | null> = {};
      req.request.params.keys().forEach((k) => (params[k] = req.request.params.get(k)));
      requests.push({ url: u, params });
      return u.endsWith('/queues') ? [] : emptyPage;
    });
    await new Promise((r) => setTimeout(r, 300));
    for (const req of http.match((r) => r.method === 'GET')) {
      req.flush(emptyPage);
    }
    fixture.detectChanges();
    const active = (fixture.componentInstance as unknown as { activeFilters(): number }).activeFilters();
    return { requests, active, el: fixture.nativeElement as HTMLElement };
  }

  it('casos: credor, carteira, elegibilidade e atendimento da fila pendente', async () => {
    const { requests, active, el } = await open(CaseList, ['CASE_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW'], '/cobranca/casos?credor=c-1&carteira=p-1&elegivel=true&atendimento=QUEUE');
    const search = requests.filter((r) => r.url.endsWith('/collection/cases')).at(-1)!;
    expect(search.params).toMatchObject({ creditorId: 'c-1', portfolioId: 'p-1', eligible: 'true', ownerType: 'QUEUE' });
    expect(active).toBe(4);
    expect(el.querySelector('app-filter-bar form.app-filters')).not.toBeNull();
  });

  it('razão: período, carteira e tipo (recebimentos × estornos)', async () => {
    const { requests, active } = await open(ReceiptList, ['SETTLEMENT_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW'], '/prestacao-de-contas/razao?credor=c-1&carteira=p-1&tipo=REVERSAL&pago-de=2026-06-01&pago-ate=2026-06-30');
    const search = requests.filter((r) => r.url.endsWith('/settlement/receipts')).at(-1)!;
    expect(search.params).toMatchObject({ creditorId: 'c-1', portfolioId: 'p-1', kind: 'REVERSAL', from: '2026-06-01', to: '2026-06-30' });
    expect(active).toBe(5);
  });

  it('divergências: credor e abertas no período, em todas as situações', async () => {
    const { requests, active } = await open(DivergenceList, ['RECONCILIATION_MANAGE', 'CREDITOR_VIEW'], '/pagamentos/divergencias?credor=c-1&situacao=todas&aberta-de=2026-06-01&aberta-ate=2026-06-30&tipo=UNMATCHED');
    const search = requests.filter((r) => r.url.endsWith('/divergences')).at(-1)!;
    expect(search.params).toMatchObject({ creditorId: 'c-1', openedFrom: '2026-06-01', openedTo: '2026-06-30', type: 'UNMATCHED' });
    expect(search.params['status']).toBeUndefined();
    expect(active).toBe(5);
  });
});
