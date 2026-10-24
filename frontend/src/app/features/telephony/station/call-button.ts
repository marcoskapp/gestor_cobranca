import { Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotificationService } from '../../../core/notification/notification.service';
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
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (session.enabled()) {
      <button mat-stroked-button type="button" (click)="open()" [disabled]="inCall()" [matTooltip]="inCall() ? 'Você já está em uma chamada' : 'Ligar para o devedor (Alt + L)'" data-tour="call-button">
        <mat-icon aria-hidden="true">call</mat-icon>
        Ligar
      </button>
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
