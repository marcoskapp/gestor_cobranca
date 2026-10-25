import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PageRequest } from '../../models/page';
import { CellDef } from './cell-def';
import { DataTable, TablePage, TableSort, pageRangeLabel } from './data-table';

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

  it('textos da faixa', () => {
    expect(pageRangeLabel(0, 20, 0)).toBe('0 de 0');
    expect(pageRangeLabel(2, 20, 45)).toBe('41 – 45 de 45');
  });
});
