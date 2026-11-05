import { Component, computed, inject, input, output } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { CHANNEL_LABELS } from '../data/communication.labels';
import { MessageView } from '../data/communication.models';
import { MessageLauncher } from './message-launcher';
import { StartContact } from './start-conversation-dialog';

/**
 * "Mensagem" na estação de cobrança: contato ativo por WhatsApp, SMS ou e-mail a partir do caso
 * (template aprovado e porteiro de contato). Oculto para quem não envia mensagens.
 */
@Component({
  selector: 'app-message-button',
  imports: [NgbTooltip, Icon],
  template: `
    @if (canSend()) {
      <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="open()" ngbTooltip="Enviar mensagem ao devedor (Alt + W)" data-tour="message-button">
        <app-icon name="chat" />
        Mensagem
      </button>
    }
  `,
})
export class MessageButton {
  readonly caseId = input.required<string>();
  readonly debtorId = input.required<string>();
  readonly creditorId = input.required<string>();
  readonly debtorName = input.required<string>();
  readonly contacts = input.required<readonly StartContact[]>();
  /** Mensagem enfileirada (a estação atualiza as conversas do caso). */
  readonly sent = output<MessageView>();

  private readonly launcher = inject(MessageLauncher);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);
  protected readonly canSend = computed(() => this.auth.hasPermission(Permission.MESSAGE_SEND));

  open(): void {
    if (!this.canSend()) {
      return;
    }
    this.launcher
      .start({ caseId: this.caseId(), debtorId: this.debtorId(), creditorId: this.creditorId(), debtorName: this.debtorName(), contacts: this.contacts() })
      .subscribe((m) => {
        if (m) {
          this.notifications.success(`Mensagem por ${CHANNEL_LABELS[m.channel]} na fila de envio. As respostas chegam em Conversas.`);
          this.sent.emit(m);
        }
      });
  }
}
