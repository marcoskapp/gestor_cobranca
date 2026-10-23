import { DOCUMENT, Component, DestroyRef, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ConversationsApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS } from '../data/communication.labels';
import { ConversationView, InboxFilter } from '../data/communication.models';
import { InboxTab, slaTone, tabFilter, waitingText } from '../data/communication.rules';
import { ConversationPanel } from './conversation-panel';

/** Atualização da lista (aguardando resposta e novas conversas). */
export const INBOX_REFRESH_MS = 30_000;

const TABS: readonly { id: InboxTab; label: string }[] = [
  { id: 'mine', label: 'Minhas' },
  { id: 'queue', label: 'Fila' },
  { id: 'assistant', label: 'Assistente' },
  { id: 'all', label: 'Todas' },
];

export function inboxFilter(tab: InboxTab, channel: string, awaiting: boolean): InboxFilter {
  return { ...tabFilter(tab), channel: channel || null, awaitingReply: awaiting ? true : null, status: 'OPEN' };
}

/**
 * Caixa de entrada (F12): conversas abertas por dono (minhas, fila, assistente, todas) com o SLA de
 * primeira resposta em destaque, e a conversa selecionada em linha do tempo única por devedor.
 * Rotas `/conversas` e `/conversas/:id`.
 */
@Component({
  selector: 'app-inbox',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatSlideToggleModule, EmptyState, Skeleton, StatusChip, AppDatePipe, ConversationPanel],
  templateUrl: './inbox.html',
  styleUrl: './inbox.scss',
})
export class Inbox {
  readonly id = input<string>();

  private readonly api = inject(ConversationsApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  protected readonly tabs = TABS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly waitingText = waitingText;
  protected readonly slaTone = slaTone;
  protected readonly canManageChannels = computed(() => this.auth.hasPermission(Permission.CHANNEL_MANAGE));
  protected readonly canManageTemplates = computed(() => this.auth.hasPermission(Permission.TEMPLATE_MANAGE));

  protected readonly tab = signal<InboxTab>('mine');
  protected readonly channel = signal('');
  protected readonly awaiting = signal(false);
  protected readonly selectedId = linkedSignal<string | null>(() => this.id() ?? null);

  private readonly filter = computed(() => inboxFilter(this.tab(), this.channel(), this.awaiting()), { equal: sameValue });
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.inbox(f, page), 50);
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((c) => c.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));

  /** Contagem por aba (só abertas aguardando resposta, para o operador ver o que está esperando). */
  protected readonly counters = rxResource({
    params: () => ({ channel: this.channel() }),
    stream: ({ params }) =>
      forkJoin(
        TABS.map((t) =>
          this.api.inbox({ ...inboxFilter(t.id, params.channel, true) }, { page: 0, size: 1 }).pipe(
            map((p) => p.totalElements),
            catchError(() => of(null)),
          ),
        ),
      ).pipe(map((values) => Object.fromEntries(TABS.map((t, i) => [t.id, values[i]])) as Record<InboxTab, number | null>)),
  });

  constructor() {
    const timer = setInterval(() => {
      if (this.document.visibilityState !== 'hidden') {
        this.refresh();
      }
    }, INBOX_REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected count(tab: InboxTab): number | null {
    return this.counters.hasValue() ? (this.counters.value()?.[tab] ?? null) : null;
  }

  protected selectTab(tab: InboxTab): void {
    this.tab.set(tab);
  }

  /** Setas esquerda/direita movem entre as abas (padrão tablist). */
  protected tabKey(event: KeyboardEvent, index: number): void {
    const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (!delta) {
      return;
    }
    event.preventDefault();
    const next = (index + delta + TABS.length) % TABS.length;
    this.tab.set(TABS[next].id);
    (this.document.getElementById(`inbox-tab-${TABS[next].id}`) as HTMLElement | null)?.focus();
  }

  protected open(c: ConversationView): void {
    this.selectedId.set(c.id);
    void this.router.navigate(['/conversas', c.id], { replaceUrl: !!this.id() });
  }

  protected back(): void {
    this.selectedId.set(null);
    void this.router.navigate(['/conversas']);
  }

  protected refresh(): void {
    this.query.reload();
    this.counters.reload();
  }

  protected title(c: ConversationView): string {
    return c.debtorId ? this.debtors.name(c.debtorId) : 'Contato não identificado';
  }
}
