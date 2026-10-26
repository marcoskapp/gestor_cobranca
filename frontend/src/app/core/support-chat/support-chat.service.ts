import { Injectable, inject } from '@angular/core';
import { NgbOffcanvas, NgbOffcanvasRef } from '@ng-bootstrap/ng-bootstrap';

/** Abre o painel do chat de suporte (conteúdo carregado sob demanda, fora do bundle inicial). */
@Injectable({ providedIn: 'root' })
export class SupportChatService {
  private readonly offcanvas = inject(NgbOffcanvas);
  private panel: NgbOffcanvasRef | null = null;

  isOpen(): boolean {
    return this.panel !== null;
  }

  async open(): Promise<void> {
    if (this.panel) {
      return;
    }
    const { SupportChatPanel, SUPPORT_CHAT_TITLE_ID } = await import('./support-chat-panel');
    if (this.panel) {
      return;
    }
    const ref = this.offcanvas.open(SupportChatPanel, {
      position: 'end',
      panelClass: 'app-chat-panel',
      ariaLabelledBy: SUPPORT_CHAT_TITLE_ID,
    });
    this.panel = ref;
    const clear = () => (this.panel = null);
    ref.result.then(clear, clear);
  }
}
