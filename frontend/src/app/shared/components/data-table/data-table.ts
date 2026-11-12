import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, ElementRef, afterNextRender, afterRenderEffect, computed, contentChildren, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgbPagination } from '@ng-bootstrap/ng-bootstrap';
import { map } from 'rxjs';
import { DensityService } from '../../../core/preferences/density.service';
import { PAGE_SIZE_OPTIONS, PageRequest } from '../../models/page';
import { EmptyState } from '../empty-state/empty-state';
import { Icon } from '../icon/icon';
import { InfoHint } from '../info-hint/info-hint';
import { IconName } from '../icon/icon-names';
import { Skeleton } from '../skeleton/skeleton';
import { CellDef } from './cell-def';
import { DATA_TABLE_STACK } from './compact-tables';

export interface TableColumn {
  key: string;
  header: string;
  align?: 'start' | 'end' | 'center';
  /** Coluna de ações: sem cabeçalho visível, alinhada à direita. */
  actions?: boolean;
  /** Cabeçalho clicável que emite `sortChange` (a ordenação é feita por quem fornece as linhas). */
  sortable?: boolean;
  /** Id do indicador no catálogo de informação contextual: "i" ao lado do cabeçalho (ux.md §8.6). */
  info?: string;
  /**
   * Papel na lista empilhada do celular (< 576 px): `title` (linha principal), `meta` (até três
   * metadados com o rótulo), `badge` (selo à direita) ou `hidden`. Sem indicação: a primeira coluna
   * é o título, a coluna `status` é o selo e as três seguintes são metadados.
   */
  mobile?: 'title' | 'meta' | 'badge' | 'hidden';
}

/** Distribuição das colunas na lista empilhada do celular. */
export interface StackLayout {
  title: TableColumn | undefined;
  badge: TableColumn | undefined;
  meta: TableColumn[];
  actions: TableColumn | undefined;
  /** Demais colunas (inclusive as `hidden` do resumo): aparecem ao expandir a linha ("Mais detalhes"). */
  details: TableColumn[];
}

const STACK_META = 3;

/** Papéis das colunas na lista empilhada (ver `TableColumn.mobile`). */
export function stackLayout(columns: readonly TableColumn[]): StackLayout {
  const content = columns.filter((c) => !c.actions && c.mobile !== 'hidden');
  const title = content.find((c) => c.mobile === 'title') ?? content.find((c) => c.mobile === undefined && c.key !== 'status') ?? content[0];
  const badge = content.find((c) => c.mobile === 'badge') ?? content.find((c) => c !== title && c.mobile === undefined && c.key === 'status');
  const explicit = content.filter((c) => c.mobile === 'meta');
  const meta = explicit.length ? explicit : content.filter((c) => c !== title && c !== badge && c.mobile === undefined).slice(0, STACK_META);
  const details = columns.filter((c) => !c.actions && c !== title && c !== badge && !meta.includes(c));
  return { title, badge, meta, actions: columns.find((c) => c.actions), details };
}

export interface TablePage {
  index: number;
  size: number;
  total: number;
}

export interface TableSort {
  column: string;
  direction: 'asc' | 'desc';
}

/** "1 – 20 de 135" (textos do paginador em português). */
export function pageRangeLabel(page: number, pageSize: number, length: number): string {
  if (length === 0 || pageSize === 0) {
    return `0 de ${length}`;
  }
  const start = page * pageSize;
  const end = Math.min(start + pageSize, length);
  return `${start + 1} – ${end} de ${length}`;
}

let nextId = 0;

/**
 * Tabela densa reutilizável (tabela responsiva do Bootstrap) com estados de carregamento
 * (esqueleto na primeira carga, barra nas seguintes), vazio e erro com ação, ordenação opcional
 * por coluna e paginação do servidor (`PageResponse`, `ngb-pagination`). Uma ação para o estado
 * vazio pode ser projetada com o atributo `appEmptyAction`. Células personalizadas por
 * `<ng-template appCell="coluna">`; sem template, a célula exibe o valor como texto
 * (interpolação, nunca HTML). Na densidade compacta usa `.table-sm`.
 *
 * Celular (< 576 px): lista empilhada em vez da tabela — coluna principal como título, até três
 * metadados com rótulo, selo à direita e a linha inteira clicável quando o título tem um link
 * (ver `TableColumn.mobile`); as demais colunas aparecem ao expandir a linha ("Mais detalhes"). A
 * paginação some quando tudo cabe numa página. Dentro de `appCompactTables` (painel lateral), a
 * lista empilhada vale também quando a tabela não cabe na largura da área, sem rolagem lateral.
 */
@Component({
  selector: 'app-data-table',
  imports: [NgTemplateOutlet, NgbPagination, EmptyState, Skeleton, Icon, InfoHint],
  templateUrl: './data-table.html',
  styleUrl: './data-table.scss',
})
export class DataTable<T> {
  readonly rows = input.required<readonly T[]>();
  readonly columns = input.required<readonly TableColumn[]>();
  readonly caption = input.required<string>();
  readonly loading = input(false);
  readonly failed = input(false);
  readonly emptyMessage = input('Nenhum registro encontrado.');
  /** Orientação abaixo da mensagem de vazio (ex.: "Ajuste os filtros ou cadastre um novo."). */
  readonly emptyHint = input<string>();
  readonly emptyIcon = input('search');
  readonly page = input<TablePage | null>(null);
  readonly sort = input<TableSort | null>(null);
  readonly rowKey = input<(row: T) => unknown>((row: T) => (row as { id?: unknown }).id ?? row);

  readonly pageChange = output<PageRequest>();
  readonly sortChange = output<TableSort>();
  readonly retry = output<void>();

  private readonly cells = contentChildren(CellDef);
  protected readonly density = inject(DensityService);

  protected readonly sizeId = `app-table-size-${++nextId}`;
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly skeletonColumns = computed(() => Math.max(1, Math.min(this.columns().filter((c) => !c.actions).length, 6)));
  protected readonly templates = computed(() => new Map(this.cells().map((c) => [c.appCell(), c.template])));
  protected readonly trackRow = (row: T): unknown => this.rowKey()(row);

  /** Tela abaixo de 576 px (ponto `sm` do Bootstrap). */
  private readonly narrowScreen = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 575.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  /** `appCompactTables`: a tabela não coube na largura da própria área (`fit`). */
  private readonly overflowing = signal(false);
  /** Largura de que a tabela precisou quando transbordou: volta a ser tabela quando a área chega a ela. */
  private neededWidth = 0;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Lista empilhada: tela estreita ou, com `appCompactTables`, tabela que não cabe na área. */
  protected readonly stacked = computed(() => this.narrowScreen() || this.overflowing());
  protected readonly stack = computed(() => stackLayout(this.columns()));
  /** Linhas expandidas na lista empilhada (pela chave da linha). */
  private readonly expanded = signal<ReadonlySet<unknown>>(new Set());
  protected readonly detailsPrefix = `app-table-details-${nextId}`;

  constructor() {
    if (inject(DATA_TABLE_STACK, { optional: true }) !== 'fit') {
      return;
    }
    // mede depois de cada renderização que muda linhas, colunas ou o modo, e quando a área muda de largura
    afterRenderEffect(() => {
      this.rows();
      this.columns();
      this.stacked();
      this.fit();
    });
    if (typeof ResizeObserver !== 'undefined') {
      const resize = new ResizeObserver(() => this.fit());
      afterNextRender(() => resize.observe(this.host.nativeElement));
      inject(DestroyRef).onDestroy(() => resize.disconnect());
    }
  }

  /**
   * Tabela mais larga que a área → lista; lista numa área que já comporta a tabela medida → tabela de novo. A largura
   * guardada é a que o host precisa ter (tabela + borda do cartão), a mesma caixa comparada na volta: sem isso, na
   * largura exata as duas condições valem e a tela alterna sem parar.
   */
  private fit(): void {
    const host = this.host.nativeElement;
    if (this.overflowing()) {
      if (host.clientWidth > 0 && host.clientWidth >= this.neededWidth) {
        this.overflowing.set(false);
      }
      return;
    }
    const wrap = host.querySelector<HTMLElement>('.table-responsive');
    if (wrap && wrap.clientWidth > 0 && wrap.scrollWidth > wrap.clientWidth + 1) {
      this.neededWidth = wrap.scrollWidth + (host.clientWidth - wrap.clientWidth);
      this.overflowing.set(true);
    }
  }

  protected isExpanded(row: T): boolean {
    return this.expanded().has(this.trackRow(row));
  }

  protected toggleDetails(row: T): void {
    const key = this.trackRow(row);
    this.expanded.update((set) => {
      const next = new Set(set);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  }

  /** Paginador só quando há mais de uma página (ou fora da primeira). */
  protected showPager(p: TablePage): boolean {
    return p.total > p.size || p.index > 0;
  }

  /** Rodapé (quantidade por página e faixa) só quando a quantidade escolhida muda algo. */
  protected showFooter(p: TablePage): boolean {
    return this.showPager(p) || p.total > Math.min(...this.pageSizeOptions);
  }

  protected text(row: T, key: string): string {
    const value = (row as Record<string, unknown>)[key];
    return value === null || value === undefined ? '—' : String(value);
  }

  protected range(p: TablePage): string {
    return pageRangeLabel(p.index, p.size, p.total);
  }

  protected onPage(page: number): void {
    const current = this.page();
    // o ngb-pagination também emite quando ajusta a página às entradas (ex.: total ainda 0 durante
    // a carga); só a escolha do usuário — possível apenas fora da carga — muda a página
    if (current && !this.loading() && page - 1 !== current.index) {
      this.pageChange.emit({ page: page - 1, size: current.size });
    }
  }

  protected onSize(event: Event): void {
    const size = Number((event.target as HTMLSelectElement).value);
    // nova quantidade por página volta para a primeira página
    this.pageChange.emit({ page: 0, size });
  }

  protected ariaSort(column: TableColumn): 'ascending' | 'descending' | 'none' | null {
    if (!column.sortable) {
      return null;
    }
    const sort = this.sort();
    return sort?.column === column.key ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none';
  }

  protected sortIcon(column: TableColumn): IconName {
    const sort = this.sort();
    return sort?.column !== column.key ? 'arrow-down-up' : sort.direction === 'asc' ? 'caret-up-fill' : 'caret-down-fill';
  }

  protected toggleSort(column: TableColumn): void {
    const sort = this.sort();
    const direction = sort?.column === column.key && sort.direction === 'asc' ? 'desc' : 'asc';
    this.sortChange.emit({ column: column.key, direction });
  }
}
