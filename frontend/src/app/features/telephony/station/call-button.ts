import { Component, computed, inject, input } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { SheetContact } from '../../collection/data/collection.models';
import { TelephonyLauncher } from './telephony-launcher';
import { TelephonySession } from './telephony-session';

/**
 * "Ligar" (clique para ligar) na estação e na ficha do caso. Abre a escolha do telefone com a
 * decisão do porteiro de contato antes de discar; a chamada em andamento aparece na barra de
 * presença.
 */
@Component({
  selector: 'app-call-button',
  imports: [NgbTooltip, Icon],
  template: `
    @if (session.enabled()) {
      <!-- o tooltip fica no invólucro: botão desabilitado não recebe ponteiro nem foco -->
      <span class="d-inline-block" [ngbTooltip]="inCall() ? 'Você já está em uma chamada' : 'Ligar para o devedor (Alt + L)'">
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="open()" [disabled]="inCall()" data-tour="call-button">
          <app-icon name="telephone" />
          Ligar
        </button>
      </span>
    }
  `,
})
export class CallButton {
  readonly caseId = input.required<string>();
  readonly debtorName = input.required<string>();
  readonly contacts = input.required<readonly SheetContact[]>();

  protected readonly session = inject(TelephonySession);
  private readonly launcher = inject(TelephonyLauncher);
  private readonly notifications = inject(NotificationService);
  protected readonly inCall = computed(() => !!this.session.call());

  open(): void {
    if (!this.session.enabled() || this.inCall()) {
      return;
    }
    this.launcher.call({ caseId: this.caseId(), debtorName: this.debtorName(), contacts: this.contacts() }).subscribe((call) => {
      if (call) {
        this.notifications.success(`Ligando para ${call.numberMasked ?? 'o devedor'}: atenda no seu ramal.`);
      }
    });
  }
}
