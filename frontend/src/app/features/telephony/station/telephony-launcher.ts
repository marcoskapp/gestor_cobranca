import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, finalize, from, of, switchMap } from 'rxjs';
import type { CallDialogData } from './call-dialog';
import type { TabulationDialogData } from './tabulation-dialog';
import { CallView } from '../data/telephony.models';

/**
 * Abre os diálogos de telefonia a partir da estação de cobrança carregando-os sob demanda: o
 * código da ligação e da tabulação não entra no bloco da estação.
 */
// TRANSITÓRIO (migração para Bootstrap): fornecido nas rotas do shell (shell.routes.ts), não na raiz,
// para usar o MatDialog e os padrões do Material daquele injetor. Volta a `providedIn: 'root'`
// quando passar a usar o DialogService.
@Injectable()
export class TelephonyLauncher {
  private readonly dialog = inject(MatDialog);
  private tabulationOpen = false;

  /** "Ligar": decisão do porteiro por telefone e clique para ligar; emite a chamada criada. */
  call(data: CallDialogData): Observable<CallView | undefined> {
    return from(import('./call-dialog')).pipe(
      switchMap(({ CallDialog }) =>
        this.dialog.open<unknown, CallDialogData, CallView>(CallDialog, { data, width: '560px', maxWidth: '96vw', autoFocus: 'first-heading' }).afterClosed(),
      ),
    );
  }

  /**
   * Tabulação obrigatória do pós-atendimento (não fecha sem tabular). Abre no máximo um diálogo por
   * vez; emite a chamada tabulada (ou a recarregada, se o prazo automático venceu).
   */
  tabulation(data: TabulationDialogData): Observable<CallView | undefined> {
    if (this.tabulationOpen) {
      return of(undefined);
    }
    this.tabulationOpen = true;
    return from(import('./tabulation-dialog')).pipe(
      switchMap(({ TabulationDialog }) =>
        this.dialog
          .open<unknown, TabulationDialogData, CallView>(TabulationDialog, { data, width: '620px', maxWidth: '96vw', disableClose: true, autoFocus: 'first-heading' })
          .afterClosed(),
      ),
      finalize(() => (this.tabulationOpen = false)),
    );
  }
}
