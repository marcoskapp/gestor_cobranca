import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup, tick } from '../../../../../testing/component-harness';
import { DebtorList } from './debtor-list';

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

describe('DebtorList — estado na URL', () => {
  it('a busca (nome ou documento) nunca vai para a URL; o credor vai', async () => {
    const { http } = setup(DebtorList, ['DEBTOR_VIEW', 'CREDITOR_VIEW', 'PORTFOLIO_VIEW']);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/devedores');
    const fixture = await render(DebtorList, http, () => emptyPage);
    const list = fixture.componentInstance as unknown as { filters: { controls: { q: { setValue(v: string): void }; creditor: { setValue(v: string[]): void } } } };

    list.filters.controls.q.setValue('Maria Fictícia');
    list.filters.controls.creditor.setValue(['c-1']);
    await new Promise((r) => setTimeout(r, 400));
    await tick();
    http.match(() => true).forEach((req) => req.flush(emptyPage));

    expect(router.url).toContain('credor=c-1');
    expect(decodeURIComponent(router.url)).not.toContain('Maria');
    expect(router.url).not.toContain('busca');

    list.filters.controls.q.setValue('529.982.247-25');
    await new Promise((r) => setTimeout(r, 400));
    await tick();
    expect(router.url).not.toMatch(/529|busca/);
  });
});
