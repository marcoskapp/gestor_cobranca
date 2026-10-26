import { Component, inject } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../shared/components/icon/icon';
import { SupportChatService } from './support-chat.service';

/**
 * Botão do chat de suporte na barra superior: disponível em toda tela autenticada e para todos os
 * perfis. Abre o painel lateral; o foco volta ao botão quando o painel fecha (ng-bootstrap).
 */
@Component({
  selector: 'app-support-chat-button',
  imports: [NgbTooltip, Icon],
  template: `
    <button
      type="button"
      class="btn app-topbar__btn"
      data-tour="support-chat"
      aria-haspopup="dialog"
      aria-label="Chat de suporte"
      ngbTooltip="Chat de suporte"
      (click)="chat.open()"
    >
      <app-icon name="chat-dots" />
    </button>
  `,
})
export class SupportChatButton {
  protected readonly chat = inject(SupportChatService);
}
