import { Component, DestroyRef, computed, effect, inject, input, model, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { NgbNavModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DocumentDisplay } from '../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { NameMap, nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { RESTRICTION_TYPE_LABELS } from '../../compliance/data/compliance.labels';
import { OverdueBadge } from '../../debt/components/overdue-badge';
import { CaseNegotiation } from '../../negotiation/case/case-negotiation';
import { CaseScoreCard } from '../../scoring/case/case-score-card';
import { DebtorContactsApi, DebtorsApi } from '../../debt/data/debt.api';
import { CONTACT_LABEL_LABELS, CONTACT_TYPE_ICONS, CONTACT_TYPE_LABELS } from '../../debt/data/debt.labels';
import { PaymentReviewAlert } from '../../payment/components/payment-review-alert';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { PaymentReviewStatusApi } from '../../payment/data/review-status';
import { InteractionsApi, PromisesApi, TasksApi } from '../data/collection.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, DISPUTE_TYPE_LABELS, TASK_TYPE_LABELS, eligibilityText } from '../data/collection.labels';
import { CaseSheet, PromiseView, SheetContact, SheetTitle, TabulationView, TaskView } from '../data/collection.models';
import { formatLocalDateTime, taskLate, zonedNow } from '../data/collection.rules';
import { ScheduleDialog, ScheduleDialogData } from './schedule-dialog';

/** Abas da ficha na ordem dos atalhos `Alt + 1…6`. */
export const SHEET_TABS = ['Títulos', 'Contatos', 'Histórico', 'Agenda e promessas', 'Contestações', 'Negociação'] as const;

/**
 * Ficha do caso no centro da estação: devedor (documento e contatos mascarados, revelação
 * auditada), posição, elegibilidade e abas com títulos, contatos, histórico, agenda/promessas e
 * contestações/restrições. Reaproveita os componentes da ficha do devedor (atraso, documento,
 * situação, valores).
 */
@Component({
  selector: 'app-case-sheet-panel',
  imports: [RouterLink, NgbNavModule, NgbTooltip, Icon, Amount, DocumentDisplay, EmptyState, StatusChip, AppDatePipe, OverdueBadge, CaseNegotiation, CaseScoreCard, PaymentReviewAlert],
  templateUrl: './case-sheet-panel.html',
  styleUrl: './case-sheet-panel.scss',
})
export class CaseSheetPanel {
  readonly sheet = input.required<CaseSheet>();
  readonly tabulations = input<readonly TabulationView[]>([]);
  readonly creditors = input.required<NameMap>();
  readonly portfolios = input.required<NameMap>();
  readonly tab = model(0);
  /** A ficha mudou (tarefa, promessa): a estação recarrega. */
  readonly changed = output<void>();

  private readonly auth = inject(AuthService);
  private readonly debtorsApi = inject(DebtorsApi);
  private readonly contactsApi = inject(DebtorContactsApi);
  private readonly interactionsApi = inject(InteractionsApi);
  private readonly tasksApi = inject(TasksApi);
  private readonly promisesApi = inject(PromisesApi);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly paymentLauncher = inject(PaymentLauncher);
  private readonly reviewApi = inject(PaymentReviewStatusApi);

  protected readonly tabs = SHEET_TABS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly contactTypes = CONTACT_TYPE_LABELS;
  protected readonly contactIcons = CONTACT_TYPE_ICONS;
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly disputeTypes = DISPUTE_TYPE_LABELS;
  protected readonly taskTypes = TASK_TYPE_LABELS;
  protected readonly restrictionTypes: Record<string, string> = RESTRICTION_TYPE_LABELS;
  protected readonly eligibilityText = eligibilityText;
  protected readonly formatLocal = formatLocalDateTime;

  protected readonly canReveal = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));
  protected readonly canManageTasks = computed(() => this.auth.hasPermission(Permission.TASK_MANAGE));
  protected readonly canCancelPromise = computed(() => this.auth.hasPermission(Permission.INTERACTION_REGISTER));
  /** Pix de título avulso; com acordo em vigor, o Pix é da parcela (aba Negociação). */
  protected readonly canPix = computed(() => this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE) && this.sheet().collectionCase.status !== 'AGREEMENT');
  protected readonly userId = computed(() => this.auth.user()?.id ?? null);

  protected readonly c = computed(() => this.sheet().collectionCase);
  protected readonly debtor = computed(() => this.sheet().debtor);
  protected readonly active = computed(() => this.debtor().status === 'ACTIVE');
  private readonly caseId = computed(() => this.c().id);

  /**
   * Pagamentos informados do devedor no credor aguardando aprovação e recusados recentes (aviso
   * mínimo; o detalhe fica no acordo). Só com `AGREEMENT_VIEW`; falha silenciosa.
   */
  private readonly reviewResource = rxResource({
    params: () =>
      this.auth.hasPermission(Permission.AGREEMENT_VIEW) ? { creditorId: this.c().creditorId, debtorId: this.c().debtorId, caseId: this.c().id } : undefined,
    stream: ({ params }) => this.reviewApi.debtor(params.creditorId, params.debtorId).pipe(catchError(() => of(undefined))),
  });
  protected readonly review = valueOr(this.reviewResource, undefined);

  /** Histórico completo sob demanda (a ficha traz as 10 últimas). */
  protected readonly showAllHistory = signal(false);
  private readonly history = rxResource({
    params: () => (this.showAllHistory() ? this.caseId() : undefined),
    stream: ({ params }) => this.interactionsApi.list(params, { page: 0, size: 100 }),
  });
  protected readonly interactions = computed(() =>
    this.showAllHistory() && this.history.hasValue() ? this.history.value()!.content : this.sheet().recentInteractions,
  );
  protected readonly historyLoading = this.history.isLoading;
  /** Autores das interações (só id → nome, pelo lookup leve de usuários). */
  private readonly authors = nameMap(
    inject(UserNames),
    () => this.interactions().filter((i) => i.actorType === 'USER' && i.operatorId !== this.userId()).map((i) => i.operatorId),
    { batch: USER_NAMES_BATCH },
  );

  /** Histórico de promessas sob demanda. */
  protected readonly showPromises = signal(false);
  private readonly promises = rxResource({
    params: () => (this.showPromises() ? this.caseId() : undefined),
    stream: ({ params }) => this.promisesApi.byCase(params),
  });
  protected readonly promiseHistory = valueOr(this.promises, []);

  /** Valores revelados (somente em memória, nesta tela; descartados ao trocar de caso). */
  protected readonly revealedDocument = signal<string | null>(null);
  protected readonly revealedContacts = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly tabulationNames = computed(() => new Map(this.tabulations().map((t) => [t.code, t.name])));
  protected readonly now = computed(() => zonedNow());

  constructor() {
    effect(() => {
      this.caseId();
      this.revealedDocument.set(null);
      this.revealedContacts.set(new Map());
      this.showAllHistory.set(false);
      this.showPromises.set(false);
    });
    inject(DestroyRef).onDestroy(() => {
      this.revealedDocument.set(null);
      this.revealedContacts.set(new Map());
    });
  }

  protected channelText(channel: string): string {
    return (CHANNEL_LABELS as Record<string, string>)[channel] ?? channel;
  }

  protected late(task: TaskView): boolean {
    return taskLate(task, this.now());
  }

  protected operatorText(operatorId: string | null, actorType: string): string {
    if (actorType === 'ASSISTANT') {
      return 'Assistente';
    }
    if (actorType !== 'USER') {
      return 'Sistema';
    }
    if (!operatorId) {
      return 'Operador';
    }
    return operatorId === this.userId() ? 'Você' : this.authors.name(operatorId);
  }

  protected revealDocument(): void {
    this.debtorsApi.revealDocument(this.debtor().id).subscribe((r) => this.revealedDocument.set(r.document));
  }

  protected revealContact(contact: SheetContact): void {
    this.contactsApi
      .reveal(this.debtor().id, contact.contactId)
      .subscribe((r) => this.revealedContacts.update((m) => new Map(m).set(contact.contactId, r.value)));
  }

  protected hideContact(contact: SheetContact): void {
    this.revealedContacts.update((m) => {
      const next = new Map(m);
      next.delete(contact.contactId);
      return next;
    });
  }

  protected reschedule(task: TaskView): void {
    this.dialog
      .open<ScheduleDialog, ScheduleDialogData, TaskView>(ScheduleDialog, { data: { caseId: task.caseId, task } })
      .afterClosed()
      .subscribe((saved) => saved && this.changed.emit());
  }

  protected complete(task: TaskView): void {
    this.tasksApi.complete(task.id, null).subscribe((done) => {
      this.notifications.success(done.completedOnTime === false ? 'Retorno concluído com atraso.' : 'Retorno concluído.');
      this.changed.emit();
    });
  }

  protected cancelTask(task: TaskView): void {
    this.confirm
      .confirm({
        title: 'Cancelar agendamento',
        message: 'O agendamento deixa de aparecer na agenda e na próxima ação do caso.',
        confirmLabel: 'Cancelar agendamento',
        destructive: true,
        reason: 'required',
        details: [
          { label: 'Tipo', value: TASK_TYPE_LABELS[task.type] },
          { label: 'Horário', value: formatLocalDateTime(task.dueAtLocal) },
        ],
      })
      .subscribe((reason) =>
        this.tasksApi.cancel(task.id, reason).subscribe(() => {
          this.notifications.success('Agendamento cancelado.');
          this.changed.emit();
        }),
      );
  }

  protected cancelPromise(promise: PromiseView): void {
    this.confirm
      .confirm({
        title: 'Cancelar promessa',
        message: 'Use apenas para promessa registrada por engano: o caso volta à negociação.',
        confirmLabel: 'Cancelar promessa',
        destructive: true,
        reason: 'required',
        details: [
          { label: 'Devedor', value: this.debtor().name },
          { label: 'Valor', value: formatAmount(promise.amount) },
          { label: 'Data prometida', value: promise.dueDate.split('-').reverse().join('/') },
        ],
      })
      .subscribe((reason) =>
        this.promisesApi.cancel(promise.id, reason).subscribe(() => {
          this.notifications.success('Promessa cancelada.');
          this.changed.emit();
        }),
      );
  }

  protected pixableTitle(t: SheetTitle): boolean {
    return t.status === 'OPEN' && t.openBalance > 0 && !t.disputeBlocked;
  }

  protected titlePix(t: SheetTitle): void {
    const cs = this.sheet().collectionCase;
    this.paymentLauncher
      .instruction({
        target: {
          kind: 'TITLE',
          creditorId: cs.creditorId,
          titleId: t.titleId,
          label: `Título ${t.externalId}${t.installmentNumber ? '/' + t.installmentNumber : ''}`,
          dueDate: t.dueDate,
          balance: t.openBalance,
        },
        debtorId: this.sheet().debtor.id,
        debtorName: this.sheet().debtor.name,
      })
      .subscribe((created) => {
        if (created?.status === 'PAID') {
          this.changed.emit();
        }
      });
  }
}
