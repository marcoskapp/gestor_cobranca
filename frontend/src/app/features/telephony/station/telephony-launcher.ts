import { Injectable, inject } from '@angular/core';
import { Observable, finalize, from, of, switchMap } from 'rxjs';
import { DialogService } from '../../../shared/dialog/dialog.service';
import type { CallDialogData } from './call-dialog';
import type { TabulationDialogData } from './tabulation-dialog';
import { CallView } from '../data/telephony.models';

/**
 * Abre os diálogos de telefonia a partir da estação de cobrança carregando-os sob demanda: o
 * código da ligação e da tabulação não entra no bloco da estação.
 */
@Injectable({ providedIn: 'root' })
export class TelephonyLauncher {
  private readonly dialog = inject(DialogService);
  private tabulationOpen = false;

  /** "Ligar": decisão do porteiro por telefone e clique para ligar; emite a chamada criada. */
  call(data: CallDialogData): Observable<CallView | undefined> {
    return from(import('./call-dialog')).pipe(switchMap(({ CallDialog }) => this.dialog.open<unknown, CallDialogData, CallView>(CallDialog, { data }).afterClosed()));
  }

  /**
   * Tabulação obrigatória do pós-atendimento (não fecha sem tabular: sem Esc, sem clique no fundo
   * e sem botão de fechar). Abre no máximo um diálogo por vez; emite a chamada tabulada (ou a
   * recarregada, se o prazo automático venceu).
   */
  tabulation(data: TabulationDialogData): Observable<CallView | undefined> {
    if (this.tabulationOpen) {
      return of(undefined);
    }
    this.tabulationOpen = true;
    return from(import('./tabulation-dialog')).pipe(
      switchMap(({ TabulationDialog }) => this.dialog.open<unknown, TabulationDialogData, CallView>(TabulationDialog, { data, disableClose: true }).afterClosed()),
      finalize(() => (this.tabulationOpen = false)),
    );
  }
}
