import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, contentChildren, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatPaginatorIntl, MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { PAGE_SIZE_OPTIONS, PageRequest } from '../../models/page';
import { EmptyState } from '../empty-state/empty-state';
import { Skeleton } from '../skeleton/skeleton';
import { CellDef } from './cell-def';
import { PtBrPaginatorIntl } from './paginator-intl';

export interface TableColumn {
  key: string;
  header: string;
  align?: 'start' | 'end' | 'center';
  /** Coluna de ações: sem cabeçalho visível, alinhada à direita. */
  actions?: boolean;
}

export interface TablePage {
  index: number;
  size: number;
  total: number;
}

/**
 * Tabela densa reutilizável com estados de carregamento (esqueleto na primeira carga, barra nas
 * seguintes), vazio e erro com ação, e paginação do servidor (`PageResponse`). Uma ação para o
 * estado vazio pode ser projetada com o atributo `appEmptyAction`. Células personalizadas por `<ng-template appCell="coluna">`; sem template, a
 * célula exibe o valor como texto (interpolação, nunca HTML).
 */
@Component({
  selector: 'app-data-table',
  imports: [MatTableModule, MatPaginatorModule, MatProgressBarModule, MatButtonModule, NgTemplateOutlet, EmptyState, Skeleton],
  templateUrl: './data-table.html',
  styleUrl: './data-table.scss',
  providers: [{ provide: MatPaginatorIntl, useClass: PtBrPaginatorIntl }],
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
  readonly emptyIcon = input('search_off');
  readonly page = input<TablePage | null>(null);
  readonly rowKey = input<(row: T) => unknown>((row: T) => (row as { id?: unknown }).id ?? row);

  readonly pageChange = output<PageRequest>();
  readonly retry = output<void>();

  private readonly cells = contentChildren(CellDef);

  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly columnKeys = computed(() => this.columns().map((c) => c.key));
  protected readonly skeletonColumns = computed(() => Math.max(1, Math.min(this.columns().filter((c) => !c.actions).length, 6)));
  protected readonly templates = computed(() => new Map(this.cells().map((c) => [c.appCell(), c.template])));
  protected readonly trackRow = (_: number, row: T): unknown => this.rowKey()(row);

  protected text(row: T, key: string): string {
    const value = (row as Record<string, unknown>)[key];
    return value === null || value === undefined ? '—' : String(value);
  }

  protected onPage(event: PageEvent): void {
    this.pageChange.emit({ page: event.pageIndex, size: event.pageSize });
  }
}
