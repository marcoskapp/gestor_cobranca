import { Directive, TemplateRef, inject, input } from '@angular/core';

export interface CellContext<T> {
  $implicit: T;
}

/**
 * Template de célula para `app-data-table`:
 * `<ng-template appCell="name" [appCellOf]="rows()" let-row>…</ng-template>`.
 * `appCellOf` é opcional e serve apenas para tipar `row` no template.
 */
@Directive({ selector: 'ng-template[appCell]' })
export class CellDef<T> {
  /** Chave da coluna. */
  readonly appCell = input.required<string>();
  /** Apenas para inferência de tipo das linhas no template. */
  readonly appCellOf = input<readonly T[]>([]);
  readonly template = inject<TemplateRef<CellContext<T>>>(TemplateRef);

  static ngTemplateContextGuard<T>(_dir: CellDef<T>, context: unknown): context is CellContext<T> {
    return context !== null && typeof context === 'object';
  }
}
