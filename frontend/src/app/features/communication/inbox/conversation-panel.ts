import { LiveAnnouncer } from '@angular/cdk/a11y';
import { DOCUMENT, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, catchError, finalize, forkJoin, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ConversationsApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, HANDOFF_KIND_LABELS, HANDOFF_REASON_LABELS } from '../data/communication.labels';
import { ConversationView, DraftView, HandoffView, MessageView } from '../data/communication.models';
import { blockText, mergeTimeline, newInbound, senderText, slaTone, waitingText } from '../data/communication.rules';
import { AssignConversationDialog, ConversationDialogData, IdentifyDialog } from './conversation-dialogs';
import { Composer } from './composer';

/** Intervalo de atualização da conversa aberta (sem som; anúncio discreto para leitores de tela). */
export const CONVERSATION_REFRESH_MS = 15_000;

/**
 * Conversa em linha do tempo única por devedor (todas as conversas e canais do devedor, canal por
 * ícone **e** texto, assistente identificado por texto), rascunhos do assistente para revisão,
 * resposta e comandos de dono (assumir, devolver, atribuir, encerrar, identificar devedor).
 */
@Component({
  selector: 'app-conversation-panel',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatTooltipModule,
    EmptyState,
    Skeleton,
    StatusChip,
    AppDatePipe,
    Composer,
  ],
  templateUrl: './conversation-panel.html',
  styleUrl: './conversation-panel.scss',
})
export class ConversationPanel {
  readonly conversationId = input.required<string>();
  /** A conversa mudou de dono ou situação: a lista recarrega. */
  readonly changed = output<void>();

  private readonly api = inject(ConversationsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly document = inject(DOCUMENT);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly kindLabels = HANDOFF_KIND_LABELS;
  protected readonly blockText = blockText;
  protected readonly waitingText = waitingText;
  protected readonly slaTone = slaTone;

  protected readonly detail = rxResource({ params: () => this.conversationId(), stream: ({ params }) => this.api.get(params) });
  protected readonly conversation = computed(() => (this.detail.hasValue() ? this.detail.value()!.conversation : null));
  protected readonly handoffs = computed(() => (this.detail.hasValue() ? this.detail.value()!.handoffs : []));
  protected readonly drafts = computed(() => (this.detail.hasValue() ? this.detail.value()!.pendingDrafts.filter((d) => d.status === 'PENDING') : []));
  /** Texto editado de cada rascunho (o envio registra a edição). */
  protected readonly draftEdits = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly busy = signal(false);
  protected readonly lastBlock = signal<string | null>(null);

  /** Mensagens de todas as conversas do devedor (ou só desta, se o contato não foi identificado). */
  private readonly messageList = rxResource({
    params: () => {
      const c = this.conversation();
      return c ? { id: c.id, debtorId: c.debtorId, creditorId: c.creditorId } : undefined;
    },
    stream: ({ params }) => this.loadMessages(params.id, params.debtorId, params.creditorId).pipe(map((list) => ({ conversationId: params.id, list }))),
  });
  private readonly loaded = valueOr(this.messageList, undefined);
  protected readonly messages = computed(() => this.loaded()?.list ?? []);
  protected readonly timeline = computed(() => mergeTimeline(this.messages(), this.handoffs()));
  protected readonly loading = computed(() => this.detail.isLoading() && !this.conversation());

  private readonly userId = computed(() => this.auth.user()?.id ?? null);
  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.conversation()?.debtorId]);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.conversation()?.creditorId]);
  protected readonly users = nameMap(
    inject(UserNames),
    () => [
      this.conversation()?.ownerUserId,
      ...this.messages().filter((m) => m.senderType === 'USER').map((m) => m.senderId),
      ...this.handoffs().flatMap((h) => [h.toUserId, h.actorType === 'USER' ? h.actorId : null]),
    ],
    { batch: USER_NAMES_BATCH },
  );

  private readonly canSend = computed(() => this.auth.hasPermission(Permission.MESSAGE_SEND));
  private readonly canAssignCase = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  private readonly canManageInbox = computed(() => this.auth.hasPermission(Permission.INBOX_MANAGE));
  protected readonly canSeeCase = computed(() => this.auth.hasPermission(Permission.CASE_VIEW));

  protected readonly mine = computed(() => {
    const c = this.conversation();
    return !!c && c.ownerType === 'USER' && c.ownerUserId === this.userId();
  });
  protected readonly actions = computed(() => {
    const c = this.conversation();
    if (!c || c.status !== 'OPEN') {
      return { takeOver: false, release: false, assistant: false, assign: false, close: false, identify: false };
    }
    const mine = this.mine();
    const otherUser = c.ownerType === 'USER' && !mine;
    const supervise = this.canAssignCase();
    return {
      takeOver: this.canSend() && !mine && (!otherUser || supervise),
      release: this.canSend() && (mine || (supervise && (c.ownerType === 'USER' || c.ownerType === 'ASSISTANT'))),
      assistant: this.canSend() && c.ownerType !== 'ASSISTANT' && (mine || supervise) && !!c.debtorId,
      assign: this.canManageInbox() && !!c.caseId,
      close: this.canSend() && (mine || supervise || c.ownerType === 'QUEUE' || c.ownerType === 'NONE'),
      identify: this.canSend() && !c.debtorId,
    };
  });
  /** Por que não é possível responder agora (ou nulo se pode). */
  protected readonly replyBlocked = computed(() => {
    const c = this.conversation();
    if (!c) {
      return null;
    }
    if (!this.canSend()) {
      return 'Seu perfil consulta conversas, mas não envia mensagens.';
    }
    if (c.status !== 'OPEN') {
      return 'Conversa encerrada. Uma nova mensagem do devedor reabre o atendimento.';
    }
    if (!c.debtorId) {
      return 'Identifique o devedor antes de responder: o contato ainda não está vinculado a um cadastro.';
    }
    if (c.ownerType === 'ASSISTANT') {
      return 'O assistente está conduzindo esta conversa. Assuma a conversa para responder.';
    }
    if (c.ownerType === 'USER' && !this.mine()) {
      return `Esta conversa está com ${this.users.name(c.ownerUserId)}. ${this.canAssignCase() ? 'Assuma a conversa para responder.' : 'Peça à supervisão para transferi-la.'}`;
    }
    return null;
  });
  protected readonly ownerText = computed(() => {
    const c = this.conversation();
    if (!c) {
      return '';
    }
    if (c.ownerType === 'USER') {
      return this.mine() ? 'Com você' : `Com ${this.users.name(c.ownerUserId)}`;
    }
    return c.ownerType === 'QUEUE' ? `Na fila${c.requiredProfile ? ` (exige ${c.requiredProfile})` : ''}` : c.ownerType === 'ASSISTANT' ? 'Com o assistente' : 'Sem atendimento';
  });

  /** Última lista carregada (por conversa), para anunciar só o que chegou depois. */
  private previous: { conversationId: string; list: MessageView[] } | null = null;

  constructor() {
    // conversa trocada: limpa estado local
    effect(() => {
      this.conversationId();
      untracked(() => {
        this.previous = null;
        this.draftEdits.set(new Map());
        this.lastBlock.set(null);
      });
    });
    // anúncio discreto de nova mensagem do devedor (sem som)
    effect(() => {
      const current = this.loaded();
      if (!current) {
        return;
      }
      untracked(() => {
        const before = this.previous?.conversationId === current.conversationId ? this.previous.list : null;
        const fresh = newInbound(before, current.list);
        this.previous = { conversationId: current.conversationId, list: [...current.list] };
        if (fresh.length) {
          const m = fresh[fresh.length - 1];
          void this.announcer.announce(
            fresh.length === 1 ? `Nova mensagem do devedor pelo ${CHANNEL_LABELS[m.channel]}.` : `${fresh.length} novas mensagens do devedor.`,
            'polite',
          );
        }
      });
    });
    // marca como lida ao abrir
    effect(() => {
      const c = this.conversation();
      if (c && c.unreadCount > 0 && this.canSend()) {
        untracked(() => this.api.markRead(c.id).subscribe(() => this.changed.emit()));
      }
    });
    const timer = setInterval(() => {
      if (this.document.visibilityState !== 'hidden' && !this.busy()) {
        this.refresh();
      }
    }, CONVERSATION_REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private loadMessages(id: string, debtorId: string | null, creditorId: string | null): Observable<MessageView[]> {
    const of50 = { page: 0, size: 50 };
    const own = (conversationId: string) => this.api.messages(conversationId, of50).pipe(
      map((p) => p.content),
      catchError(() => of<MessageView[]>([])),
    );
    if (!debtorId) {
      return own(id);
    }
    const byStatus = (status: string) =>
      this.api.inbox({ debtorId, creditorId, status }, { page: 0, size: 10 }).pipe(
        map((p) => p.content.map((c) => c.id)),
        catchError(() => of<string[]>([])),
      );
    return forkJoin([byStatus('OPEN'), byStatus('CLOSED')]).pipe(
      map(([open, closed]) => [...new Set([id, ...open, ...closed])]),
      switchMap((ids) => forkJoin(ids.map(own))),
      map((lists) => lists.flat()),
    );
  }

  protected refresh(): void {
    this.detail.reload();
    this.messageList.reload();
  }

  protected senderName(m: MessageView): string {
    return senderText(m, this.userId(), (id) => this.users.name(id));
  }

  protected eventText(h: HandoffView): string {
    const target = h.toOwnerType === 'USER' ? (h.toUserId === this.userId() ? 'você' : this.users.name(h.toUserId)) : null;
    const parts = [HANDOFF_KIND_LABELS[h.kind] ?? h.kind];
    if (target && (h.kind === 'ASSIGNMENT' || h.kind === 'TAKE_OVER')) {
      parts[0] += ` (${target})`;
    }
    if (h.reasonCode) {
      parts.push(HANDOFF_REASON_LABELS[h.reasonCode] ?? h.reasonCode);
    }
    if (h.summary) {
      parts.push(h.summary);
    }
    return parts.join(' · ');
  }

  protected eventActor(h: HandoffView): string {
    return h.actorType === 'ASSISTANT' ? 'Assistente' : h.actorType === 'USER' ? (h.actorId === this.userId() ? 'Você' : this.users.name(h.actorId)) : 'Sistema';
  }

  // ------------------------------------------------------------------ dono do atendimento

  protected takeOver(): void {
    const c = this.conversation();
    if (!c) {
      return;
    }
    const fromOther = c.ownerType === 'USER' && !this.mine();
    this.confirm
      .confirm({
        title: 'Assumir conversa',
        message: fromOther ? 'A conversa e o caso passam de outro operador para você.' : 'Você passa a ser o dono desta conversa e do caso; as demais conversas abertas do caso acompanham.',
        confirmLabel: 'Assumir',
        reason: fromOther ? 'required' : 'optional',
        reasonMaxLength: 300,
        details: this.impact(c),
      })
      .subscribe((reason) => this.command(this.api.takeOver(c.id, reason || null), 'Você assumiu a conversa.'));
  }

  protected release(): void {
    const c = this.conversation();
    if (c) {
      this.confirm
        .confirm({ title: 'Devolver à fila', message: 'A conversa volta para a fila e pode ser assumida por outro operador.', confirmLabel: 'Devolver', reason: 'optional', reasonMaxLength: 300, details: this.impact(c) })
        .subscribe((reason) => this.command(this.api.release(c.id, reason || null), 'Conversa devolvida à fila.'));
    }
  }

  protected toAssistant(): void {
    const c = this.conversation();
    if (c) {
      this.confirm
        .confirm({
          title: 'Devolver ao assistente',
          message: 'O assistente volta a responder o devedor conforme a política dele. Rascunhos pendentes expiram.',
          confirmLabel: 'Devolver ao assistente',
          reason: 'optional',
          reasonMaxLength: 300,
          details: this.impact(c),
        })
        .subscribe((reason) => this.command(this.api.returnToAssistant(c.id, reason || null), 'Conversa devolvida ao assistente.'));
    }
  }

  protected close(): void {
    const c = this.conversation();
    if (c) {
      this.confirm
        .confirm({
          title: 'Encerrar conversa',
          message: 'O atendimento termina. Uma nova mensagem do devedor abre outra conversa na fila.',
          confirmLabel: 'Encerrar',
          reason: 'optional',
          reasonMaxLength: 300,
          details: this.impact(c),
        })
        .subscribe((reason) => this.command(this.api.close(c.id, reason || null), 'Conversa encerrada.'));
    }
  }

  protected assign(): void {
    this.openDialog(AssignConversationDialog, 'Conversa atribuída.');
  }

  protected identify(): void {
    this.openDialog(IdentifyDialog, 'Devedor identificado.');
  }

  private openDialog(component: typeof IdentifyDialog | typeof AssignConversationDialog, message: string): void {
    const c = this.conversation();
    if (!c) {
      return;
    }
    this.dialog
      .open<unknown, ConversationDialogData, ConversationView>(component, { data: { conversation: c }, width: '520px' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(message);
          this.refresh();
          this.changed.emit();
        }
      });
  }

  private impact(c: ConversationView): { label: string; value: string }[] {
    return [
      { label: 'Devedor', value: c.debtorId ? this.debtors.name(c.debtorId) : 'Não identificado' },
      { label: 'Canal', value: `${CHANNEL_LABELS[c.channel]} · ${c.contactMasked}` },
      { label: 'Atendimento', value: this.ownerText() },
    ];
  }

  private command(call: Observable<unknown>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe(() => {
      this.notifications.success(message);
      this.refresh();
      this.changed.emit();
    });
  }

  // ------------------------------------------------------------------ rascunhos e envio

  protected draftText(d: DraftView): string {
    return this.draftEdits().get(d.id) ?? d.content;
  }

  protected editDraft(d: DraftView, value: string): void {
    this.draftEdits.update((m) => new Map(m).set(d.id, value));
  }

  protected sendDraft(d: DraftView): void {
    const c = this.conversation();
    if (!c) {
      return;
    }
    const text = this.draftText(d).trim();
    if (!text) {
      return;
    }
    this.busy.set(true);
    this.api
      .sendDraft(c.id, d.id, text !== d.content ? text : null)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe((m) => this.afterSend(m, text !== d.content ? 'Rascunho editado e enviado.' : 'Rascunho aprovado e enviado.'));
  }

  protected discardDraft(d: DraftView): void {
    const c = this.conversation();
    if (c) {
      this.command(this.api.discardDraft(c.id, d.id), 'Rascunho descartado.');
    }
  }

  protected afterSend(m: MessageView, message = 'Mensagem enviada para a fila de envio.'): void {
    const blocked = blockText(m);
    this.lastBlock.set(blocked);
    if (blocked) {
      this.notifications.error(blocked);
    } else {
      this.notifications.success(message);
    }
    this.refresh();
    this.changed.emit();
  }
}
