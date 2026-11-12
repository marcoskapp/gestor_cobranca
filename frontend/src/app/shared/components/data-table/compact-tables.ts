import { Directive, InjectionToken } from '@angular/core';

/**
 * Como o `app-data-table` decide virar lista empilhada. `fit`: além da tela estreita (< 576 px), também quando a tabela
 * não cabe na própria área (painel lateral, coluna estreita) — em vez da barra de rolagem lateral.
 */
export const DATA_TABLE_STACK = new InjectionToken<'fit'>('DATA_TABLE_STACK');

/**
 * Tabelas da área marcada viram lista empilhada (com linha expansível) quando não cabem na largura disponível
 * (desenho `acordos-ficha-lateral-e-atencao-especial.md` §A2). Para painéis e colunas estreitas em tela larga, como a
 * ficha do devedor no painel lateral da estação.
 *
 * ```html
 * <app-debtor-detail appUrlStateEmbedded appCompactTables [id]="debtorId" />
 * ```
 */
@Directive({
  selector: '[appCompactTables]',
  providers: [{ provide: DATA_TABLE_STACK, useValue: 'fit' }],
})
export class CompactTables {}
