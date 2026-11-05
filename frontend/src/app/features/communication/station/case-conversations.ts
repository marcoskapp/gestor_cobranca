import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { nameMap } from '../../../shared/data/name-map';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { ConversationsApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS } from '../data/communication.labels';
import { ConversationView } from '../data/communication.models';
import { ownerText, waitingText } from '../data/communication.rules';

/**
 * Conversas do caso (abertas e encerradas, mais recentes primeiro) na ficha da estação: canal,
 * contato mascarado, quem atende, espera por resposta e atalho para a conversa na caixa de entrada.
 * As mensagens enviadas e recebidas também entram no histórico do caso como interações.
 */
@Component({
  selector: 'app-case-conversations',
  imports: [RouterLink, Icon, EmptyState, AppDatePipe],
  template: `
    <div [attr.aria-busy]="list.isLoading()">
      @if (list.error()) {
        <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar as conversas." message="Tente de novo em instantes." [compact]="true" />
      } @else {
        <ol class="cc-list list-unstyled mb-0" aria-label="Conversas do caso, da mais recente para a mais antiga">
          @for (c of rows(); track c.id) {
            <li class="cc-item" [class.cc-item--closed]="c.status === 'CLOSED'">
              <app-icon class="cc-item__icon" [name]="channelIcons[c.channel]" />
              <div class="cc-item__body">
                <div class="d-flex flex-wrap align-items-center column-gap-2">
                  <strong>{{ channelLabels[c.channel] }}</strong>
                  <span class="app-numeric app-muted">{{ c.contactMasked }}</span>
                  <span class="app-status" [class]="c.status === 'CLOSED' ? 'app-status--neutral' : c.awaitingSince ? 'app-status--warning' : 'app-status--info'">{{ owner(c) }}</span>
                  @if (c.status === 'OPEN' && waiting(c); as w) {
                    <span class="app-status app-status--warning">{{ w }}</span>
                  }
                  @if (c.unreadCount) {
                    <span class="badge rounded-pill text-bg-primary">{{ c.unreadCount }} não lida{{ c.unreadCount > 1 ? 's' : '' }}</span>
                  }
                </div>
                <div class="app-muted small">
                  {{ c.messageCount }} mensage{{ c.messageCount === 1 ? 'm' : 'ns' }}
                  @if (c.lastMessageAt) {
                    · última em {{ c.lastMessageAt | appDate }}
                  }
                  @if (c.status === 'CLOSED' && c.closedAt) {
                    · encerrada em {{ c.closedAt | appDate }}
                  }
                </div>
              </div>
              <a class="btn btn-sm btn-link" [routerLink]="['/conversas', c.id]">Abrir</a>
            </li>
          } @empty {
            @if (!list.isLoading()) {
              <li>
                <app-empty-state icon="chat-left-text" title="Nenhuma conversa neste caso." message="Use Mensagem para iniciar o contato por WhatsApp, SMS ou e-mail." [compact]="true" />
              </li>
            }
          }
        </ol>
      }
    </div>
  `,
  styles: `
    .cc-item {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 8px 0;
      border-bottom: 1px solid var(--bs-border-color-translucent);
    }
    .cc-item:last-child {
      border-bottom: 0;
    }
    .cc-item--closed {
      opacity: 0.75;
    }
    .cc-item__body {
      flex: 1 1 auto;
      min-width: 0;
    }
    .cc-item__icon {
      margin-top: 2px;
      color: var(--bs-secondary-color);
    }
  `,
})
export class CaseConversations {
  readonly caseId = input.required<string>();
  /** Muda quando a estação envia uma mensagem: recarrega a lista. */
  readonly revision = input(0);

  private readonly api = inject(ConversationsApi);
  private readonly auth = inject(AuthService);
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;

  protected readonly list = rxResource({
    params: () => ({ caseId: this.caseId(), revision: this.revision() }),
    stream: ({ params }) => this.api.inbox({ caseId: params.caseId, status: 'ALL' }, { page: 0, size: 50 }),
  });
  protected readonly rows = computed<ConversationView[]>(() => (this.list.hasValue() ? this.list.value()!.content : []));
  private readonly users = nameMap(
    inject(UserNames),
    () => this.rows().filter((c) => c.ownerType === 'USER' && c.ownerUserId).map((c) => c.ownerUserId!),
    { batch: USER_NAMES_BATCH },
  );

  protected owner(c: ConversationView): string {
    return ownerText(c, this.auth.user()?.id ?? null, (id) => this.users.name(id));
  }

  protected waiting(c: ConversationView): string | null {
    return waitingText(c);
  }
}
