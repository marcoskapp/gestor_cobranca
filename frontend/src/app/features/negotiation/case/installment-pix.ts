import { Observable } from 'rxjs';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { InstructionView } from '../../payment/data/payment.models';
import { AgreementView, InstallmentView } from '../data/agreement.models';
import { installmentBalance } from '../data/agreement.rules';

/** Pix de uma parcela do acordo (QR Code e copia e cola), com o canal do acordo para o envio. */
export function installmentPix(
  launcher: PaymentLauncher,
  a: AgreementView,
  next: InstallmentView,
  debtorId: string,
): Observable<InstructionView | undefined> {
  return launcher.instruction({
    target: {
      kind: 'INSTALLMENT',
      creditorId: a.creditorId,
      agreementId: a.id,
      installmentId: next.id,
      installmentNumber: next.number,
      dueDate: next.dueDate,
      balance: installmentBalance(next),
    },
    debtorId,
    channel: a.channel,
  });
}
