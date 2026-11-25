import { Component, input } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { CREDITOR_REJECTION_REASON_PORTAL_TEXT } from '../data/payment.labels';
import { PaymentView } from '../data/payment.models';

/**
 * Aviso do pagamento informado pela pessoa do credor no portal: títulos informados, resultado da verificação do
 * comprovante (sem verificação de vírus → abrir com cuidado) e, na recusa, o texto que o credor vê.
 */
@Component({
  selector: 'app-creditor-portal-note',
  imports: [Icon],
  template: `
    @if (payment().creditorPortal; as cp) {
      <div class="alert d-flex gap-2 align-items-start" [class.alert-info]="cp.evidenceScanVerdict === 'CLEAN'"
           [class.alert-warning]="cp.evidenceScanVerdict !== 'CLEAN'" role="note">
        <app-icon [name]="cp.evidenceScanVerdict === 'CLEAN' ? 'shield-check' : 'exclamation-triangle'" />
        <span>
          Informado pela pessoa do credor no portal, para os títulos {{ numbers(cp.titleNumbers) }}.
          {{ cp.evidenceScanVerdict === 'CLEAN' ? 'O comprovante passou pela verificação de vírus.' : 'O comprovante não passou pela verificação de vírus (verificação desligada): abra com cuidado.' }}
          @if (cp.creditorRejectionReason; as r) {
            <span class="d-block mt-1">O credor vê: “{{ portalText[r] }}”</span>
          }
        </span>
      </div>
    }
  `,
})
export class CreditorPortalNote {
  readonly payment = input.required<PaymentView>();
  protected readonly portalText = CREDITOR_REJECTION_REASON_PORTAL_TEXT;
  protected readonly numbers = (joined: string) => joined.split(',').join(', ');
}
