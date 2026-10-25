import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, from, switchMap } from 'rxjs';
import type { ExternalDialogData } from '../payments/external-dialog';
import type { InstructionDialogData } from '../instructions/instruction-dialog';
import { InstructionView, PaymentView } from './payment.models';

/**
 * Abre os diálogos de pagamento a partir de outras telas (ficha do acordo, estação de cobrança)
 * carregando-os sob demanda: o código do módulo de pagamentos (inclusive o gerador de QR Code)
 * não entra no bloco da tela de origem.
 */
// TRANSITÓRIO (migração para Bootstrap): fornecido nas rotas do shell (shell.routes.ts), não na raiz,
// para usar o MatDialog e os padrões do Material daquele injetor. Volta a `providedIn: 'root'`
// quando passar a usar o DialogService.
@Injectable()
export class PaymentLauncher {
  private readonly dialog = inject(MatDialog);

  /** Gerar Pix; emite a instrução criada (ou nada, se fechado sem gerar). */
  instruction(data: InstructionDialogData): Observable<InstructionView | undefined> {
    return from(import('../instructions/instruction-dialog')).pipe(
      switchMap(({ InstructionDialog }) =>
        this.dialog.open<unknown, InstructionDialogData, InstructionView>(InstructionDialog, { data, width: '720px', maxWidth: '96vw', autoFocus: 'first-heading' }).afterClosed(),
      ),
    );
  }

  /** Informar pagamento externo com comprovante; emite o pagamento registrado. */
  external(data: ExternalDialogData): Observable<PaymentView | undefined> {
    return from(import('../payments/external-dialog')).pipe(
      switchMap(({ ExternalDialog }) =>
        this.dialog.open<unknown, ExternalDialogData, PaymentView>(ExternalDialog, { data, width: '760px', maxWidth: '96vw', autoFocus: 'first-heading' }).afterClosed(),
      ),
    );
  }
}
