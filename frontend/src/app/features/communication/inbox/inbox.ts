import { DOCUMENT, Component, DestroyRef, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ConversationsApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS } from '../data/communication.labels';
import { ConversationView, InboxCounts, InboxFilter } from '../data/communication.models';
import { InboxStream } from '../data/inbox-stream';
import { InboxTab, slaHint, slaTone, tabFilter, waitingText } from '../data/communication.rules';
import { ConversationPanel } from './conversation-panel';

/** Atualização periódica da lista sem tempo real (aguardando resposta e novas conversas). */
export const INBOX_REFRESH_MS = 30_000;
/** Com o tempo real ativo, a atualização periódica vira rede de segurança (a cada 4 ciclos). */
export const INBOX_LIVE_REFRESH_EVERY = 4;
/** Avisos próximos viram uma única recarga. */
export const INBOX_EVENT_DEBOUNCE_MS = 500;

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
  imports: [FormsModule, RouterLink, ...FORM_FIELD, Icon, EmptyState, Skeleton, StatusChip, AppDatePipe, ConversationPanel],
  templateUrl: './inbox.html',
  styleUrl: './inbox.scss',
})
export class Inbox {
  readonly id = input<string>();

  private readonly api = inject(ConversationsApi);
  private readonly stream = inject(InboxStream);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  protected readonly tabs = TABS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly waitingText = waitingText;
  protected readonly slaTone = slaTone;
  protected readonly slaHint = slaHint;
  private readonly datePipe = new AppDatePipe();
  protected readonly time = (iso: string) => this.datePipe.transform(iso, 'time');
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

  /** Contagem por aba (só abertas aguardando resposta) e estouros de SLA, numa única chamada. */
  protected readonly counters = rxResource({
    params: () => ({ channel: this.channel() }),
    stream: ({ params }) => this.api.counts(params.channel || null, true).pipe(catchError(() => of<InboxCounts | null>(null))),
  });
  /** Conversas aguardando além da meta de primeira resposta (todas as abas do escopo). */
  protected readonly breached = computed(() => (this.counters.hasValue() ? (this.counters.value()?.breached ?? 0) : 0));
  /** Tempo real ativo (SSE); sem ele, só a atualização periódica. */
  protected readonly live = this.stream.live;

  constructor() {
    let ticks = 0;
    const timer = setInterval(() => {
      ticks++;
      if (this.document.visibilityState !== 'hidden' && (!this.live() || ticks % INBOX_LIVE_REFRESH_EVERY === 0)) {
        this.refresh();
      }
    }, INBOX_REFRESH_MS);
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => clearInterval(timer));
    this.stream
      .events()
      .pipe(debounceTime(INBOX_EVENT_DEBOUNCE_MS), takeUntilDestroyed(destroyRef))
      .subscribe(() => this.refresh());
  }

  protected count(tab: InboxTab): number | null {
    const c = this.counters.hasValue() ? this.counters.value() : null;
    return c ? c[tab] : null;
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
