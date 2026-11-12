import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, signal } from '@angular/core';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { PageRequest } from '../../models/page';
import { CellDef } from './cell-def';
import { DataTable, TablePage, TableSort, pageRangeLabel, stackLayout } from './data-table';
import { CompactTables } from './compact-tables';

interface Row {
  id: string;
  name: string;
  amount: number | null;
}

@Component({
  imports: [DataTable, CellDef],
  template: `
    <app-data-table
      caption="Credores"
      [rows]="rows()"
      [columns]="columns"
      [loading]="loading()"
      [failed]="failed()"
      [page]="page()"
      [sort]="sort()"
      (pageChange)="pages.push($event)"
      (sortChange)="sorts.push($event); sort.set($event)"
      (retry)="retries = retries + 1"
      emptyMessage="Nenhum credor."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-r><strong>{{ r.name }}</strong></ng-template>
      <button appEmptyAction type="button">Cadastrar credor</button>
    </app-data-table>
  `,
})
class Host {
  readonly rows = signal<Row[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly page = signal<TablePage | null>(null);
  readonly sort = signal<TableSort | null>(null);
  readonly columns = [
    { key: 'name', header: 'Nome', sortable: true },
    { key: 'amount', header: 'Valor', align: 'end' as const },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  pages: PageRequest[] = [];
  sorts: TableSort[] = [];
  retries = 0;
}

describe('DataTable (Bootstrap)', () => {
  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  it('tabela acessível com template de célula, texto seguro e "—" para vazio', () => {
    const { fixture, host, el } = render();
    host.rows.set([{ id: '1', name: 'Banco <b>Alfa</b>', amount: null }]);
    fixture.detectChanges();
    const table = el.querySelector('table')!;
    expect(table.classList).toContain('table');
    expect(table.getAttribute('aria-label')).toBe('Credores');
    expect(el.querySelectorAll('th[scope="col"]').length).toBe(3);
    expect(el.querySelector('th.text-end .visually-hidden')?.textContent).toContain('Ações');
    const cells = el.querySelectorAll('tbody td');
    expect(cells[0].querySelector('strong')?.textContent).toBe('Banco <b>Alfa</b>');
    expect(cells[1].textContent?.trim()).toBe('—');
  });

  it('esqueleto na primeira carga, vazio com ação e erro com "Tentar novamente"', () => {
    const { fixture, host, el } = render();
    host.loading.set(true);
    fixture.detectChanges();
    expect(el.querySelector('.app-table')?.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('app-skeleton')).not.toBeNull();

    host.loading.set(false);
    fixture.detectChanges();
    expect(el.textContent).toContain('Nenhum credor.');
    expect(el.textContent).toContain('Cadastrar credor');

    host.failed.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-empty-state')?.getAttribute('role')).toBe('alert');
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Tentar novamente'))!.click();
    expect(host.retries).toBe(1);
  });

  it('ordenação por coluna com aria-sort', () => {
    const { fixture, host, el } = render();
    const th = el.querySelector('th[aria-sort]')!;
    expect(th.getAttribute('aria-sort')).toBe('none');
    th.querySelector('button')!.click();
    fixture.detectChanges();
    expect(host.sorts).toEqual([{ column: 'name', direction: 'asc' }]);
    expect(th.getAttribute('aria-sort')).toBe('ascending');
    th.querySelector('button')!.click();
    fixture.detectChanges();
    expect(host.sorts.at(-1)).toEqual({ column: 'name', direction: 'desc' });
    expect(th.getAttribute('aria-sort')).toBe('descending');
  });

  it('paginação do servidor: faixa em português, página e tamanho', async () => {
    const { fixture, host, el } = render();
    host.rows.set([{ id: '1', name: 'A', amount: 1 }]);
    host.page.set({ index: 0, size: 20, total: 45 });
    fixture.detectChanges();
    expect(el.textContent).toContain('1 – 20 de 45');
    Array.from(el.querySelectorAll<HTMLAnchorElement>('ngb-pagination a.page-link')).find((a) => a.textContent?.trim() === '2')!.click();
    await new Promise((r) => setTimeout(r, 0)); // o ngb-pagination emite pageChange de forma assíncrona
    fixture.detectChanges();
    expect(host.pages).toEqual([{ page: 1, size: 20 }]);

    const select = el.querySelector<HTMLSelectElement>('select')!;
    select.value = '50';
    select.dispatchEvent(new Event('change'));
    expect(host.pages.at(-1)).toEqual({ page: 0, size: 50 });
  });

  it('não muda de página por ajustes do paginador durante a carga', () => {
    const { fixture, host } = render();
    host.page.set({ index: 2, size: 20, total: 0 });
    host.loading.set(true);
    fixture.detectChanges();
    expect(host.pages).toEqual([]);
  });

  it('sem paginador quando tudo cabe numa página; sem rodapé quando o total não passa da menor página', () => {
    const { fixture, host, el } = render();
    host.rows.set([{ id: '1', name: 'A', amount: 1 }]);
    host.page.set({ index: 0, size: 50, total: 30 });
    fixture.detectChanges();
    expect(el.querySelector('ngb-pagination')).toBeNull();
    expect(el.textContent).toContain('1 – 30 de 30');
    expect(el.querySelector('select')).not.toBeNull();

    host.page.set({ index: 0, size: 20, total: 12 });
    fixture.detectChanges();
    expect(el.querySelector('.app-table__footer')).toBeNull();
  });

  it('lista empilhada do celular: título, selo da situação e até três metadados', () => {
    const layout = stackLayout([
      { key: 'name', header: 'Nome' },
      { key: 'document', header: 'CPF/CNPJ' },
      { key: 'status', header: 'Situação' },
      { key: 'type', header: 'Tipo' },
      { key: 'createdAt', header: 'Cadastrado em' },
      { key: 'updatedAt', header: 'Atualizado em' },
      { key: 'actions', header: 'Ações', actions: true },
    ]);
    expect(layout.title?.key).toBe('name');
    expect(layout.badge?.key).toBe('status');
    expect(layout.meta.map((c) => c.key)).toEqual(['document', 'type', 'createdAt']);
    expect(layout.actions?.key).toBe('actions');

    const custom = stackLayout([
      { key: 'code', header: 'Código', mobile: 'hidden' },
      { key: 'name', header: 'Nome', mobile: 'title' },
      { key: 'amount', header: 'Valor', mobile: 'meta' },
      { key: 'stage', header: 'Fase', mobile: 'badge' },
    ]);
    expect([custom.title?.key, custom.badge?.key, custom.meta.map((c) => c.key)]).toEqual(['name', 'stage', ['amount']]);
    // "Mais detalhes": o que não está no resumo, inclusive as colunas ocultas no resumo
    expect(custom.details.map((c) => c.key)).toEqual(['code']);
    expect(layout.details.map((c) => c.key)).toEqual(['updatedAt']);
  });

  it('textos da faixa', () => {
    expect(pageRangeLabel(0, 20, 0)).toBe('0 de 0');
    expect(pageRangeLabel(2, 20, 45)).toBe('41 – 45 de 45');
  });
});

@Component({
  imports: [DataTable, CellDef],
  template: `
    <app-data-table caption="Devedores" [rows]="rows" [columns]="columns">
      <ng-template appCell="name" [appCellOf]="rows" let-r><a href="/devedores/1">{{ r.name }}</a></ng-template>
      <ng-template appCell="actions" let-r><button type="button">Abrir</button></ng-template>
    </app-data-table>
  `,
})
class StackHost {
  readonly rows = [{ id: '1', name: 'Ana Souza', document: '***.459.717-**', status: 'Ativo', type: 'Pessoa física', createdAt: '06/11/2026' }];
  readonly columns = [
    { key: 'document', header: 'CPF/CNPJ', mobile: 'meta' as const },
    { key: 'name', header: 'Nome', mobile: 'title' as const },
    { key: 'type', header: 'Tipo', mobile: 'hidden' as const },
    { key: 'status', header: 'Situação', mobile: 'badge' as const },
    { key: 'createdAt', header: 'Cadastrado em', mobile: 'meta' as const },
    { key: 'actions', header: 'Ações', actions: true },
  ];
}

describe('DataTable no celular (< 576 px)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: BreakpointObserver, useValue: { observe: () => of({ matches: true, breakpoints: {} }), isMatched: () => true } }],
    });
  });

  it('lista empilhada: título com link (linha clicável), selo, metadados com rótulo e ações; sem tabela', () => {
    const fixture = TestBed.createComponent(StackHost);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('table')).toBeNull();
    const item = el.querySelector<HTMLElement>('ul.app-table__stack > li.app-table__item')!;
    expect(el.querySelector('ul.app-table__stack')?.getAttribute('aria-label')).toBe('Devedores');
    expect(item.classList).toContain('app-table__item--link');
    expect(item.querySelector('.app-table__item-title a')?.textContent).toBe('Ana Souza');
    expect(item.querySelector('.app-table__item-badge')?.textContent?.trim()).toBe('Ativo');
    const meta = [...item.querySelectorAll('.app-table__item-meta > div')].map((d) => [d.querySelector('dt')?.textContent, d.querySelector('dd')?.textContent?.trim()]);
    expect(meta).toEqual([
      ['CPF/CNPJ', '***.459.717-**'],
      ['Cadastrado em', '06/11/2026'],
    ]);
    expect(item.textContent).not.toContain('Pessoa física');
    expect(item.querySelector('.app-table__item-actions button')?.textContent).toBe('Abrir');
  });
});

describe('DataTable: linha expansível na lista empilhada', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: BreakpointObserver, useValue: { observe: () => of({ matches: true, breakpoints: {} }), isMatched: () => true } }],
    });
  });

  it('"Mais detalhes" abre as colunas fora do resumo, com rótulo, e fecha de novo', () => {
    const fixture = TestBed.createComponent(StackHost);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const toggle = el.querySelector<HTMLButtonElement>('.app-table__item-toggle')!;
    expect(toggle.textContent?.trim()).toBe('Mais detalhes');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const details = el.querySelector<HTMLElement>('.app-table__item-details')!;
    expect(details.id).toBe(toggle.getAttribute('aria-controls'));
    expect([...details.querySelectorAll('div')].map((d) => [d.querySelector('dt')?.textContent, d.querySelector('dd')?.textContent?.trim()])).toEqual([
      ['Tipo', 'Pessoa física'],
    ]);
    toggle.click();
    fixture.detectChanges();
    expect(el.querySelector<HTMLElement>('.app-table__item-details')?.hidden).toBe(true);
    expect(toggle.textContent?.trim()).toBe('Mais detalhes');
  });
});

@Component({
  selector: 'app-stack-host-fit',
  imports: [DataTable],
  template: `<app-data-table caption="Devedores" [rows]="rows" [columns]="columns" />`,
})
class FitTable extends StackHost {}

@Component({
  imports: [FitTable, CompactTables],
  template: `<div appCompactTables><app-stack-host-fit /></div>`,
})
class FitHost {}

describe('DataTable dentro de appCompactTables (painel lateral)', () => {
  let width = 400;
  let observed: (() => void) | null = null;

  beforeEach(() => {
    width = 400;
    observed = null;
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: () => void) {
          observed = cb;
        }
        observe = vi.fn();
        disconnect = vi.fn();
      },
    );
    // o jsdom não calcula layout: a tabela precisa de 700 px; a área tem `width`
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => width);
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('table-responsive') ? 700 : width;
    });
    TestBed.configureTestingModule({
      providers: [{ provide: BreakpointObserver, useValue: { observe: () => of({ matches: false, breakpoints: {} }), isMatched: () => false } }],
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('tabela que não cabe na área vira lista; com a área larga de novo, volta a ser tabela', async () => {
    const fixture = TestBed.createComponent(FitHost);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('table')).toBeNull();
    expect(el.querySelector('ul.app-table__stack')).not.toBeNull();

    width = 900;
    observed?.();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('table')).not.toBeNull();
  });

  it('fora de appCompactTables, a tela larga mantém a tabela (com rolagem lateral)', async () => {
    const fixture = TestBed.createComponent(FitTable);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('table')).not.toBeNull();
  });
});
