import { Injectable, inject } from '@angular/core';
import { Observable, from, switchMap } from 'rxjs';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { MessageView } from '../data/communication.models';
import type { StartConversationData } from './start-conversation-dialog';

/**
 * Abre o contato ativo por mensagem a partir da estação de cobrança carregando o diálogo sob
 * demanda: o código da conversa não entra no bloco da estação.
 */
@Injectable({ providedIn: 'root' })
export class MessageLauncher {
  private readonly dialog = inject(DialogService);

  /** "Mensagem": escolha do canal, do contato e do template; emite a mensagem enfileirada. */
  start(data: StartConversationData): Observable<MessageView | undefined> {
    return from(import('./start-conversation-dialog')).pipe(
      switchMap(({ StartConversationDialog }) =>
        this.dialog.open<unknown, StartConversationData, MessageView>(StartConversationDialog, { data, size: 'lg' }).afterClosed(),
      ),
    );
  }
}
