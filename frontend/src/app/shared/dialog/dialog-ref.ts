import { InjectionToken } from '@angular/core';
import { Observable, ReplaySubject } from 'rxjs';

/** Dados do diálogo, injetáveis no componente aberto (equivalente ao `MAT_DIALOG_DATA`). */
export const DIALOG_DATA = new InjectionToken<unknown>('DIALOG_DATA');

let nextId = 0;

/**
 * Referência ao diálogo aberto por `DialogService` (equivalente ao `MatDialogRef`): injetável no
 * componente do diálogo para fechá-lo com um resultado. `afterClosed()` emite o resultado (ou
 * `undefined` quando o usuário cancela por Esc, fundo ou botão de fechar) e completa.
 */
export class DialogRef<R = unknown, C = unknown> {
  /** Id do título (`appDialogTitle`), usado em `aria-labelledby` da janela. */
  readonly titleId = `app-dialog-title-${++nextId}`;
  private readonly closed$ = new ReplaySubject<R | undefined>(1);
  private closeFn: ((result: R | undefined) => void) | null = null;
  private dismissFn: (() => void) | null = null;
  private instance: C | null = null;
  private done = false;

  get componentInstance(): C | null {
    return this.instance;
  }

  /** Fecha devolvendo `result` a quem abriu. */
  close(result?: R): void {
    this.closeFn?.(result);
  }

  /** Cancela (mesmo efeito de Esc): `afterClosed()` emite `undefined`. */
  dismiss(): void {
    this.dismissFn?.();
  }

  afterClosed(): Observable<R | undefined> {
    return this.closed$.asObservable();
  }

  /** @internal — ligação com a janela do ng-bootstrap feita pelo `DialogService`. */
  attach(instance: C, close: (result: R | undefined) => void, dismiss: () => void, result: Promise<unknown>): void {
    this.instance = instance;
    this.closeFn = close;
    this.dismissFn = dismiss;
    result.then(
      (value) => this.finish(value as R | undefined),
      () => this.finish(undefined),
    );
  }

  private finish(value: R | undefined): void {
    if (this.done) {
      return;
    }
    this.done = true;
    this.closed$.next(value);
    this.closed$.complete();
  }
}
