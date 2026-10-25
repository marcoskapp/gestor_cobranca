import { Directive, inject, input } from '@angular/core';
import { DialogRef } from './dialog-ref';

/** Título do diálogo: recebe o id usado em `aria-labelledby` da janela. */
@Directive({
  selector: '[appDialogTitle]',
  host: { '[id]': 'ref.titleId', class: 'modal-title' },
})
export class DialogTitle {
  protected readonly ref = inject(DialogRef);
}

/**
 * Fecha o diálogo ao clicar, devolvendo o valor informado (equivalente ao `mat-dialog-close`):
 * `<button type="button" class="btn btn-link" [appDialogClose]="false">Cancelar</button>`.
 * Sem valor, o resultado é `undefined` (cancelamento) — nunca `''`, que seria lido como confirmação.
 */
@Directive({
  selector: 'button[appDialogClose], a[appDialogClose]',
  host: { '(click)': 'ref.close(appDialogClose())' },
})
export class DialogClose {
  protected readonly ref = inject(DialogRef);
  /** Atributo sem valor (`appDialogClose`) chega como `''` e vira cancelamento (`undefined`). */
  readonly appDialogClose = input<unknown, unknown>(undefined, { transform: (v: unknown) => (v === '' ? undefined : v) });
}

/** Conjunto para importar nos componentes de diálogo. */
export const DIALOG_DIRECTIVES = [DialogTitle, DialogClose] as const;
