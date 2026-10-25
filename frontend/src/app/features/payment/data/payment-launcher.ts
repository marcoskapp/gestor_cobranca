import { Injectable, inject } from '@angular/core';
import { Observable, from, switchMap } from 'rxjs';
import { DialogService } from '../../../shared/dialog/dialog.service';
import type { InstructionDialogData } from '../instructions/instruction-dialog';
import type { ExternalDialogData } from '../payments/external-dialog';
import { InstructionView, PaymentView } from './payment.models';

/**
 * Abre os diálogos de pagamento a partir de outras telas (ficha do acordo, estação de cobrança)
 * carregando-os sob demanda: o código do módulo de pagamentos (inclusive o gerador de QR Code)
 * não entra no bloco da tela de origem.
 */
@Injectable({ providedIn: 'root' })
export class PaymentLauncher {
  private readonly dialog = inject(DialogService);

  /** Gerar Pix; emite a instrução criada (ou nada, se fechado sem gerar). */
  instruction(data: InstructionDialogData): Observable<InstructionView | undefined> {
    return from(import('../instructions/instruction-dialog')).pipe(
      switchMap(({ InstructionDialog }) =>
        this.dialog.open<unknown, InstructionDialogData, InstructionView>(InstructionDialog, { data, size: 'lg' }).afterClosed(),
      ),
    );
  }

  /** Informar pagamento externo com comprovante; emite o pagamento registrado. */
  external(data: ExternalDialogData): Observable<PaymentView | undefined> {
    return from(import('../payments/external-dialog')).pipe(
      switchMap(({ ExternalDialog }) =>
        this.dialog.open<unknown, ExternalDialogData, PaymentView>(ExternalDialog, { data, size: 'lg' }).afterClosed(),
      ),
    );
  }
}
