import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ShowMoreButton } from '../components/show-more/show-more';
import { UrlStateEmbedded } from '../data/url-state';
import { SHOW_MORE_MAX, parseShowMoreLimit, showMore } from './show-more';

const people = (n: number) =>
  Array.from({ length: n }, (_, i) => ({ key: `p${i + 1}`, label: `Pessoa ${i + 1}` }));

@Component({
  imports: [ShowMoreButton],
  template: `
    <table #tableEl>
      <tbody>
        @for (row of table.visible(); track row.key) {
          <tr>
            <th scope="row">{{ row.label }}</th>
            <td>1</td>
          </tr>
        }
      </tbody>
    </table>
    <app-show-more [list]="table" [table]="tableEl" singular="pessoa" plural="pessoas" />
  `,
})
class ListHost {
  readonly rows = signal(people(60));
  readonly filter = signal({ period: 'out' });
  readonly table = showMore(() => this.rows(), { resetOn: () => this.filter() });
}

@Component({ selector: 'app-embedded-list', template: '' })
class EmbeddedList {
  readonly table = showMore(() => people(60));
}

@Component({
  imports: [EmbeddedList, UrlStateEmbedded],
  template: '<app-embedded-list appUrlStateEmbedded />',
})
class EmbeddingHost {}

const settle = async (harness: RouterTestingHarness) => {
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r, 0));
    harness.detectChanges();
  }
};

describe('quantidade visível lida da URL', () => {
  it('ausente, inválida ou menor que um passo → um passo; acima do teto → o teto', () => {
    expect(parseShowMoreLimit(null)).toBe(25);
    expect(parseShowMoreLimit('abc')).toBe(25);
    expect(parseShowMoreLimit('-50')).toBe(25);
    expect(parseShowMoreLimit('10')).toBe(25);
    expect(parseShowMoreLimit('50')).toBe(50);
    expect(parseShowMoreLimit('999999')).toBe(SHOW_MORE_MAX);
    expect(parseShowMoreLimit('30', 10, 20)).toBe(20);
  });
});

describe('"Mostrar mais" (showMore + app-show-more)', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'lista', component: ListHost },
          { path: 'embutida', component: EmbeddingHost },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  async function open(url: string): Promise<ListHost> {
    const host = await harness.navigateByUrl(url, ListHost);
    await settle(harness);
    return host;
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const rows = () => el().querySelectorAll('tbody tr').length;
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const button = () => el().querySelector<HTMLButtonElement>('app-show-more button');

  it('mostra 25, conta o que aparece e acrescenta 25 a cada pedido; some com tudo visível', async () => {
    const host = await open('/lista');
    expect(rows()).toBe(25);
    expect(text(el().querySelector('[role="status"]'))).toBe('Mostrando 25 de 60 pessoas');
    expect(text(button())).toBe('Mostrar mais 25 pessoas');

    button()!.click();
    await settle(harness);
    expect(rows()).toBe(50);
    expect(host.table.remaining()).toBe(10);
    // o último passo diz quantas faltam
    expect(text(button())).toBe('Mostrar mais 10 pessoas');

    button()!.click();
    await settle(harness);
    expect(rows()).toBe(60);
    expect(el().querySelector('app-show-more .show-more')).toBeNull();
  });

  it('o rótulo usa o singular quando falta uma linha', async () => {
    const host = await open('/lista');
    host.rows.set(people(26));
    await settle(harness);
    expect(text(button())).toBe('Mostrar mais 1 pessoa');
  });

  it('lista curta: sem botão nem contagem', async () => {
    const host = await open('/lista');
    host.rows.set(people(25));
    await settle(harness);
    expect(rows()).toBe(25);
    expect(el().querySelector('app-show-more .show-more')).toBeNull();
  });

  it('depois de "Mostrar mais", o foco vai para a primeira linha nova', async () => {
    await open('/lista');
    button()!.click();
    await settle(harness);
    const focused = document.activeElement as HTMLElement;
    expect(focused.tagName).toBe('TH');
    expect(text(focused)).toBe('Pessoa 26');
    expect(focused.tabIndex).toBe(-1);
  });

  it('grava a quantidade na URL (qtd, replaceUrl, demais parâmetros preservados) e a lê ao abrir', async () => {
    await open('/lista?ordem=-nome');
    const navigate = vi.spyOn(router, 'navigate');
    button()!.click();
    await settle(harness);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate.mock.calls[0][1]).toMatchObject({
      replaceUrl: true,
      queryParamsHandling: 'merge',
    });
    expect(router.url).toBe('/lista?ordem=-nome&qtd=50');

    const reopened = await open('/lista?qtd=50');
    expect(reopened.table.limit()).toBe(50);
    expect(rows()).toBe(50);
  });

  it('trocar o recorte volta para 25 e tira o qtd da URL', async () => {
    const host = await open('/lista?qtd=50&ordem=-nome');
    expect(rows()).toBe(50);
    host.filter.set({ period: 'nov' });
    await settle(harness);
    expect(host.table.limit()).toBe(25);
    expect(rows()).toBe(25);
    expect(router.url).toBe('/lista?ordem=-nome');
  });

  it('valor adulterado na URL cai no padrão', async () => {
    const host = await open('/lista?qtd=xyz');
    expect(host.table.limit()).toBe(25);
    expect(rows()).toBe(25);
  });

  it('sob appUrlStateEmbedded, a quantidade fica só em memória', async () => {
    await harness.navigateByUrl('/embutida?qtd=50', EmbeddingHost);
    await settle(harness);
    const list = harness.fixture.debugElement.query(
      (d) => d.componentInstance instanceof EmbeddedList,
    ).componentInstance as EmbeddedList;
    expect(list.table.limit()).toBe(25);
    const navigate = vi.spyOn(router, 'navigate');
    list.table.more();
    await settle(harness);
    expect(list.table.limit()).toBe(50);
    expect(navigate).not.toHaveBeenCalled();
  });
});
