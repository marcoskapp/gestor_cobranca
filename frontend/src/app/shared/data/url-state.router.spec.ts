import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { map, of, startWith } from 'rxjs';
import { PageRequest } from '../models/page';
import { pagedQuery } from './paged-query';
import { sameValue } from './same-value';
import { syncFormWithUrl, urlParam, urlTab } from './url-state';

@Component({ template: '' })
class ListHost {
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly filters = syncFormWithUrl(this.fb.group({ status: ['ACTIVE'], q: [''] }), { status: urlParam.text('situacao') }, 0);
  readonly tab = urlTab(['a', 'b'], 'a');
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      map(() => this.filters.getRawValue()),
    ),
    { initialValue: this.filters.getRawValue(), equal: sameValue },
  );
  readonly calls: PageRequest[] = [];
  readonly query = pagedQuery(
    this.filter,
    (_, page) => {
      this.calls.push(page);
      return of({ content: [], page: page.page, size: page.size, totalElements: 100, totalPages: 5 });
    },
    { url: true, sortFields: { saldo: 'openBalance' } },
  );
}

@Component({ template: '' })
class Other {}

const settle = async (harness: RouterTestingHarness) => {
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r, 0));
    harness.detectChanges();
  }
};

describe('estado de tela na URL (com o roteador)', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'lista', component: ListHost }, { path: 'outra', component: Other }])],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  async function open(url: string): Promise<ListHost> {
    const host = await harness.navigateByUrl(url, ListHost);
    await settle(harness);
    return host;
  }

  it('lê filtros, página, ordem e aba ao abrir; valor inválido cai no padrão', async () => {
    const host = await open('/lista?situacao=CLOSED&pagina=3&ordem=-saldo&aba=b');
    expect(host.filters.getRawValue().status).toBe('CLOSED');
    expect(host.query.page().page).toBe(2);
    expect(host.query.sort()).toEqual({ column: 'saldo', direction: 'desc' });
    expect(host.tab.value()).toBe('b');
    expect(host.calls.at(-1)).toEqual({ page: 2, size: 20, sort: 'openBalance,desc' });

    const other = await open('/lista?aba=z&ordem=-desconhecida');
    expect(other.tab.value()).toBe('a');
    expect(other.query.sort()).toBeNull();
  });

  it('gravações da mesma tela coalescem numa navegação só, com replaceUrl', async () => {
    const host = await open('/lista');
    const navigate = vi.spyOn(router, 'navigate');
    host.tab.set('b');
    host.query.setPage({ page: 2, size: 20 });
    harness.detectChanges(); // o ciclo de detecção roda o efeito que grava a página
    await settle(harness);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate.mock.calls[0][1]).toMatchObject({ replaceUrl: true, state: { urlState: true } });
    expect(router.url).toContain('aba=b');
    expect(router.url).toContain('pagina=3');
  });

  it('gravações em ciclos seguidos não se perdem (a segunda mescla a primeira ainda em curso)', async () => {
    const host = await open('/lista');
    host.tab.set('b');
    await new Promise((r) => setTimeout(r, 0));
    host.query.setSort({ column: 'saldo', direction: 'desc' });
    await settle(harness);
    expect(router.url).toContain('aba=b');
    expect(router.url).toContain('ordem=-saldo');
  });

  it('gravação logo antes de um clique em link é descartada (não cancela a saída)', async () => {
    const host = await open('/lista');
    host.tab.set('b');
    void router.navigateByUrl('/outra');
    await settle(harness);
    expect(router.url).toBe('/outra');
  });

  it('gravação depois de sair da tela é descartada', async () => {
    const host = await open('/lista');
    await harness.navigateByUrl('/outra');
    host.tab.set('b');
    await settle(harness);
    expect(router.url).toBe('/outra');
  });

  it('o eco da própria gravação não mexe no formulário; link externo para a mesma tela muda filtros, página e ordem', async () => {
    const host = await open('/lista');
    host.filters.controls.status.setValue('');
    host.filters.controls.q.setValue('livre');
    await settle(harness);
    expect(router.url).toBe('/lista?situacao=');
    expect(host.filters.getRawValue()).toEqual({ status: '', q: 'livre' });

    await harness.navigateByUrl('/lista?situacao=CLOSED&pagina=4&ordem=saldo');
    await settle(harness);
    expect(host.filters.getRawValue().status).toBe('CLOSED');
    expect(host.query.page().page).toBe(3);
    expect(host.query.sort()).toEqual({ column: 'saldo', direction: 'asc' });
    expect(host.calls.at(-1)).toMatchObject({ page: 3, sort: 'openBalance,asc' });
  });

  it('mudar o filtro volta para a primeira página e tira a página da URL', async () => {
    const host = await open('/lista?pagina=3');
    host.filters.controls.status.setValue('CLOSED');
    await settle(harness);
    expect(host.query.page().page).toBe(0);
    expect(router.url).not.toContain('pagina');
  });
});
