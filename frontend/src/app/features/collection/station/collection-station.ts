import { Component, DestroyRef, TemplateRef, computed, effect, inject, input, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { injectUrlStateWriter } from '../../../shared/data/url-state';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NgbDropdownModule, NgbOffcanvas, NgbOffcanvasRef, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { catchError, filter, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { KeyboardShortcuts } from '../../../core/keyboard/keyboard-shortcuts.service';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { CommandPaletteService } from '../../../core/layout/command-palette/command-palette.service';
import { NotificationService } from '../../../core/notification/notification.service';
import { DensityService } from '../../../core/preferences/density.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { valueOr } from '../../../shared/data/resource-value';
import { Option } from '../../../shared/models/lookup';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { OverdueBadge } from '../../debt/components/overdue-badge';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AssignDialog, AssignDialogData } from '../cases/assign-dialog';
import { CasesApi, CaseWorkApi, CollectionSettingsApi, TabulationsApi } from '../data/collection.api';
import { CASE_ACTIONS, CaseAction, caseActions } from '../data/collection.labels';
import { QueueNames } from '../data/collection.lookups';
import { CaseView, DisputeView, RecordResult, TaskView } from '../data/collection.models';
import { canWorkCase, formatDuration, isWorkable, nextActionText } from '../data/collection.rules';
import { CONVERSATIONS_TAB, CaseSheetPanel, NEGOTIATION_TAB, SHEET_TABS, SHEET_TAB_SLUGS, tabFromSlug } from './case-sheet-panel';
import { AttendanceService } from './attendance.service';
import { ContactWindowPanel } from './contact-window';
import { DensityInvite } from './density-invite';
import { DisputeDialog, DisputeDialogData } from './dispute-dialog';
import { InteractionPanel } from './interaction-panel';
import { ScheduleDialog, ScheduleDialogData } from './schedule-dialog';
import { CallButton } from '../../telephony/station/call-button';
import { MessageButton } from '../../communication/station/message-button';
import { PresenceBar } from '../../telephony/station/presence-bar';
import { Simulator } from '../../negotiation/simulator/simulator';
import { DebtorDetail } from '../../debt/debtors/debtor-detail/debtor-detail';
import { AuthorityApi } from '../../negotiation/data/negotiation.api';
import { ProposalView } from '../../negotiation/data/agreement.models';
import { planText } from '../../negotiation/data/negotiation.labels';
import { installmentPix } from '../../negotiation/case/installment-pix';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import {
  CloseAgreementData,
  CloseAgreementDialog,
  CloseAgreementResult,
} from '../../negotiation/case/close-agreement-dialog';
import { scheduleSummary } from '../../negotiation/data/agreement.rules';

const GROUP = 'Estação de cobrança';

/**
 * Estação de cobrança em três zonas com rolagem própria: fila e "próximo caso" (esquerda,
 * recolhível), ficha do caso (centro) e painel de ação (direita): barra de ações, "posso contatar
 * agora?" em chips e o registro de interação no topo, com o botão fixo no rodapé. Atalhos do grupo
 * "Estação de cobrança" (`Alt + N`, `Alt + R`, `Alt + A`, `Alt + S`, `Alt + W`, `Alt + 1…7`) ficam
 * ativos só nesta tela.
 *
 * O atendimento (início, rascunho do registro e aba) vive no `AttendanceService` por caso: abrir a
 * ficha do devedor ou o simulador — que abrem num painel lateral, sem sair da estação — ou outra tela
 * e voltar não zera a contagem nem o que foi digitado. O acordo fecha sem sair do caso (D7): simular
 * → propor → aceite e formalização (confirmação digitada) → Pix → cartão "Acordo formalizado".
 *
 * Rotas: `/cobranca/fila[?caso=id][&aba=]` (operador) e `/cobranca/casos/:id[?aba=]` (abrir um caso
 * específico).
 */
@Component({
  selector: 'app-collection-station',
  imports: [
    FormsModule,
    RouterLink,
    NgbDropdownModule,
    NgbTooltip,
    Icon,
    Amount,
    EmptyState,
    Skeleton,
    StatusChip,
    OverdueBadge,
    CaseSheetPanel,
    ContactWindowPanel,
    DensityInvite,
    InteractionPanel,
    CallButton,
    MessageButton,
    PresenceBar,
    Simulator,
    DebtorDetail,
  ],
  templateUrl: './collection-station.html',
  styleUrl: './collection-station.scss',
})
export class CollectionStation {
  /** `/cobranca/casos/:id` */
  readonly id = input<string>();
  /** `/cobranca/fila?caso=` */
  readonly caso = input<string>();
  /** `?aba=` (títulos, contatos, histórico, agenda, contestações, negociação, conversas). */
  readonly aba = input<string>();
  /** `?proximo=1`: "Próximo caso" pedido no início — a estação entrega o caso ao abrir. */
  readonly proximo = input<string>();

  private readonly auth = inject(AuthService);
  private readonly casesApi = inject(CasesApi);
  private readonly workApi = inject(CaseWorkApi);
  private readonly tabulationsApi = inject(TabulationsApi);
  private readonly settingsApi = inject(CollectionSettingsApi);
  private readonly queueNames = inject(QueueNames);
  private readonly router = inject(Router);
  private readonly urlState = injectUrlStateWriter();
  private readonly dialog = inject(DialogService);
  protected readonly density = inject(DensityService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly interactionPanel = viewChild(InteractionPanel);
  private readonly callButton = viewChild(CallButton);
  private readonly messageButton = viewChild(MessageButton);
  private readonly attendance = inject(AttendanceService);
  private readonly offcanvas = inject(NgbOffcanvas);
  private readonly authorityApi = inject(AuthorityApi);
  private readonly paymentLauncher = inject(PaymentLauncher);
  private readonly simulatorPanel = viewChild.required<TemplateRef<unknown>>('simulatorPanel');
  private readonly debtorPanel = viewChild.required<TemplateRef<unknown>>('debtorPanel');
  /** Painel lateral aberto (simulador ou ficha do devedor). */
  private panel: NgbOffcanvasRef | null = null;

  protected readonly caseActions = CASE_ACTIONS;
  protected readonly nextActionText = nextActionText;

  protected readonly canWork = computed(() => this.auth.hasPermission(Permission.CASE_WORK));
  protected readonly canAssign = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CASE_MANAGE));
  protected readonly canRegister = computed(() => this.auth.hasPermission(Permission.INTERACTION_REGISTER));
  protected readonly canSchedule = computed(() => this.auth.hasPermission(Permission.TASK_MANAGE));
  protected readonly canDispute = computed(() => this.auth.hasAnyPermission([Permission.DISPUTE_MANAGE, Permission.CASE_WORK]));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  /** Aba "Conversas" da ficha: só para quem vê a caixa de entrada. */
  protected readonly canSeeConversations = computed(() => this.auth.hasPermission(Permission.INBOX_VIEW));
  private readonly userId = computed(() => this.auth.user()?.id ?? null);

  /** Zona da fila recolhida (mais espaço para a ficha). */
  protected readonly queueCollapsed = signal(false);
  protected readonly queueId = signal<string>('');
  protected readonly nextLoading = signal(false);
  protected readonly tab = signal(0);
  /** Incrementado a cada mensagem enviada pela estação: a aba Conversas recarrega. */
  protected readonly conversationsRevision = signal(0);
  /** Incrementado quando a estação cria ou avança uma proposta: cartão e aba Negociação recarregam. */
  protected readonly dealRevision = signal(0);
  /** Acordo formalizado nesta estação para o caso aberto (cartão "Acordo formalizado"). */
  protected readonly closedAgreementId = signal<string | null>(null);

  protected readonly selectedId = linkedSignal<string | null>(() => this.id() ?? this.caso() ?? null);

  // ------------------------------------------------------------------ dados

  protected readonly queues = rxResource({ stream: () => this.queueNames.all() });
  protected readonly queueOptions = valueOr<Option[]>(this.queues, []);

  protected readonly myCases = rxResource({
    params: () => (this.canWork() ? true : undefined),
    stream: () => this.workApi.myCases({ page: 0, size: 100 }),
  });
  protected readonly myCaseRows = computed<CaseView[]>(() => (this.myCases.hasValue() ? this.myCases.value()!.content : []));

  protected readonly sheet = rxResource({
    params: () => this.selectedId() ?? undefined,
    stream: ({ params }) => this.casesApi.sheet(params),
  });
  protected readonly current = valueOr(this.sheet, undefined);

  private readonly tabulationList = rxResource({ stream: () => this.tabulationsApi.list(false) });
  protected readonly allTabulations = valueOr(this.tabulationList, []);
  protected readonly activeTabulations = computed(() =>
    this.allTabulations()
      .filter((t) => t.active)
      .sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name)),
  );
  private readonly settings = rxResource({ stream: () => this.settingsApi.get() });
  protected readonly promiseMaxDays = computed(() => (this.settings.hasValue() ? this.settings.value()!.promiseMaxDays : 30));

  protected readonly debtors = nameMap(inject(DebtorNames), () => this.myCaseRows().map((c) => c.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.myCaseRows().map((c) => c.creditorId),
    ...(this.current()?.otherCases.map((c) => c.creditorId) ?? []),
    this.current()?.collectionCase.creditorId,
  ]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [
    ...(this.current()?.otherCases.map((c) => c.portfolioId) ?? []),
    this.current()?.collectionCase.portfolioId,
  ]);

  protected readonly selectedCase = computed(() => this.current()?.collectionCase ?? null);
  /** O usuário pode registrar e agendar neste caso (dele, sem operador, ou com `CASE_ASSIGN`). */
  protected readonly workable = computed(() => {
    const c = this.selectedCase();
    return !!c && canWorkCase(c, this.userId(), this.canAssign());
  });
  protected readonly mine = computed(() => this.selectedCase()?.assigneeUserId === this.userId());
  protected readonly lifecycle = computed<CaseAction[]>(() => (this.selectedCase() ? caseActions(this.selectedCase()!.status) : []));
  protected readonly retained = computed(() => {
    const c = this.selectedCase();
    return !!c && !isWorkable(c) && c.status !== 'AGREEMENT';
  });

  // ------------------------------------------------------------------ cronômetro de atendimento

  protected readonly startedAt = signal<Date | null>(null);
  private readonly clock = signal(Date.now());
  protected readonly elapsed = computed(() => {
    const start = this.startedAt();
    return start ? formatDuration((this.clock() - start.getTime()) / 1000) : '00:00';
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.debtor.name ?? null));
    // Caso aberto: retoma o atendimento guardado (início e aba) ou começa um novo agora. A aba vem da
    // URL (`?aba=`), senão a última usada no caso, senão a primeira.
    effect(() => {
      const id = this.selectedCase()?.id;
      untracked(() => {
        this.startedAt.set(id ? this.attendance.begin(id) : null);
        if (id) {
          const fromUrl = tabFromSlug(this.aba());
          this.tab.set(this.allowedTab(fromUrl ?? this.attendance.tab(id) ?? 0));
          this.closedAgreementId.set(this.attendance.agreement(id));
          this.syncTab(this.tab());
        }
      });
    });
    // Aba trocada: guarda no atendimento do caso e na URL (voltar à estação reabre a mesma aba).
    effect(() => {
      const tab = this.tab();
      untracked(() => this.syncTab(tab));
    });
    // "Próximo caso" do início: pede uma vez e tira o parâmetro da URL
    effect(() => {
      const ask = this.proximo() === '1';
      untracked(() => {
        if (ask) {
          void this.router.navigate([], { queryParams: { proximo: null }, queryParamsHandling: 'merge', replaceUrl: true });
          this.nextCase();
        }
      });
    });
    // painel lateral fecha ao sair da tela (link de dentro da ficha do devedor, por exemplo)
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.panel?.dismiss());
    const timer = setInterval(() => this.clock.set(Date.now()), 1000);
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => clearInterval(timer));

    const keys = inject(KeyboardShortcuts);
    const off = [
      keys.register({ id: 'collection.next', keys: 'alt+n', group: GROUP, description: 'Próximo caso', handler: () => this.nextCase() }),
      keys.register({ id: 'collection.interaction', keys: 'alt+r', group: GROUP, description: 'Registrar interação', handler: () => this.interactionPanel()?.focus() }),
      keys.register({ id: 'collection.callback', keys: 'alt+a', group: GROUP, description: 'Agendar retorno', handler: () => this.schedule() }),
      keys.register({ id: 'collection.simulate', keys: 'alt+s', group: GROUP, description: 'Abrir simulação de negociação', handler: () => this.simulate() }),
      keys.register({ id: 'collection.call', keys: 'alt+l', group: GROUP, description: 'Ligar para o devedor (clique para ligar)', handler: () => this.callButton()?.open() }),
      keys.register({ id: 'collection.message', keys: 'alt+w', group: GROUP, description: 'Enviar mensagem ao devedor (WhatsApp, SMS ou e-mail)', handler: () => this.messageButton()?.open() }),
      keys.register({ id: 'collection.queue', keys: 'alt+q', group: GROUP, description: 'Recolher ou expandir a fila', handler: () => this.queueCollapsed.update((v) => !v) }),
      ...SHEET_TABS.map((label, i) =>
        keys.register({
          id: `collection.tab-${i + 1}`,
          keys: `alt+${i + 1}`,
          group: GROUP,
          description: `Aba ${label}`,
          // aba oculta sem permissão: o atalho não faz nada
          handler: () => this.tab.set(this.allowedTab(i)),
        }),
      ),
    ];
    const palette = inject(CommandPaletteService).register([
      { id: 'collection.next-case', label: 'Próximo caso', group: 'Ações', icon: 'skip-forward', hint: 'Alt + N', keywords: ['fila', 'cobrança'], run: () => this.nextCase() },
      { id: 'collection.register', label: 'Registrar interação', group: 'Ações', icon: 'check2-circle', hint: 'Alt + R', keywords: ['tabulação'], run: () => this.interactionPanel()?.focus() },
      { id: 'collection.schedule', label: 'Agendar retorno', group: 'Ações', icon: 'calendar-event', hint: 'Alt + A', run: () => this.schedule() },
    ]);
    destroy.onDestroy(() => {
      off.forEach((fn) => fn());
      palette();
      this.panel?.dismiss();
      // saiu da estação: o teto de pausa do atendimento conta a partir daqui
      const id = this.selectedCase()?.id;
      if (id) {
        this.attendance.touch(id);
      }
    });
  }

  private syncTab(tab: number): void {
    const id = this.selectedCase()?.id;
    if (!id) {
      return;
    }
    this.attendance.setTab(id, tab);
    if (SHEET_TAB_SLUGS[tab] !== (this.aba() ?? SHEET_TAB_SLUGS[0])) {
      // gravação de estado de tela (shared/data/url-state): replaceUrl, não rola a tela nem cancela um clique
      this.urlState.write({ aba: tab === 0 ? null : SHEET_TAB_SLUGS[tab] });
    }
  }

  /** Aba oculta sem permissão (Conversas) volta para a primeira. */
  private allowedTab(i: number): number {
    return i === CONVERSATIONS_TAB && !this.canSeeConversations() ? 0 : i;
  }

  // ------------------------------------------------------------------ ações

  /** Mensagem enviada pela estação: atualiza as conversas e o histórico do caso. */
  protected messageSent(): void {
    this.conversationsRevision.update((v) => v + 1);
    this.sheet.reload();
  }

  protected select(c: CaseView): void {
    this.open(c.id);
  }

  protected nextCase(): void {
    if (!this.canWork() || this.nextLoading()) {
      return;
    }
    this.nextLoading.set(true);
    this.workApi
      .nextCase(this.queueId() || null)
      .pipe(finalize(() => this.nextLoading.set(false)))
      .subscribe((next) => {
        if (!next) {
          this.notifications.info('Nenhum caso disponível agora nas suas filas.');
          return;
        }
        this.myCases.reload();
        this.open(next.id);
      });
  }

  protected schedule(): void {
    const c = this.selectedCase();
    if (!c || !this.canSchedule() || !this.workable()) {
      return;
    }
    this.dialog
      .open<ScheduleDialog, ScheduleDialogData, TaskView>(ScheduleDialog, {
        data: { caseId: c.id, canChooseAssignee: this.canAssign() },
      })
      .afterClosed()
      .subscribe((task) => task && this.refreshAll());
  }

  protected openDispute(): void {
    const sheet = this.current();
    if (!sheet) {
      return;
    }
    this.dialog
      .open<DisputeDialog, DisputeDialogData, DisputeView>(DisputeDialog, {
        data: {
          caseId: sheet.collectionCase.id,
          debtorName: sheet.debtor.name,
          titles: sheet.titles.filter((t) => (t.status === 'OPEN' || t.status === 'SUSPENDED') && !t.disputeBlocked),
        },
        size: 'lg',
      })
      .afterClosed()
      .subscribe((dispute) => dispute && this.refreshAll());
  }

  /**
   * Canal do atendimento no momento em que o simulador abre (o escolhido no registro de interação).
   * Lido do atendimento a cada abertura do painel: a simulação e o aceite saem com o canal atual.
   */
  protected readonly simulatorChannel = signal<string | undefined>(undefined);

  /** Simulador no painel lateral: simula sozinho com o caso e entrega a proposta criada à estação. */
  protected simulate(): void {
    const c = this.selectedCase();
    if (c && this.canSimulate()) {
      this.simulatorChannel.set(this.attendance.channel(c.id) ?? undefined);
      this.openPanel(this.simulatorPanel(), 'st-sim-title');
    }
  }

  /** Ficha completa do devedor no painel lateral (o atendimento continua por baixo). */
  protected openDebtor(): void {
    if (this.current() && this.canViewDebtor()) {
      this.openPanel(this.debtorPanel(), 'st-debtor-title');
    }
  }

  private openPanel(template: TemplateRef<unknown>, titleId: string): void {
    this.panel?.dismiss();
    const ref = this.offcanvas.open(template, { position: 'end', panelClass: 'app-station-panel', ariaLabelledBy: titleId });
    this.panel = ref;
    const clear = () => this.panel === ref && (this.panel = null);
    ref.result.then(clear, clear);
  }

  protected closePanel(): void {
    this.panel?.dismiss();
  }

  /**
   * Proposta criada no simulador: dentro da alçada, segue direto para o aceite e a formalização sem
   * sair do caso; acima dela, o caso mostra a proposta aguardando aprovação.
   */
  protected onProposed(p: ProposalView): void {
    this.closePanel();
    this.dealRevision.update((v) => v + 1);
    this.tab.set(NEGOTIATION_TAB);
    if (p.status === 'DRAFT' || p.status === 'APPROVED') {
      this.closeAgreement(p);
    }
  }

  /** Aceite e formalização num fluxo só (D7), com o canal do atendimento pré-preenchido. */
  protected closeAgreement(p: ProposalView): void {
    const sheet = this.current();
    if (!sheet || !this.auth.hasPermission(Permission.AGREEMENT_FORMALIZE)) {
      return;
    }
    const caseId = sheet.collectionCase.id;
    this.authorityApi
      .mine(p.creditorId)
      .pipe(catchError(() => of(undefined)))
      .subscribe((authority) => {
        // a resposta chegou depois de o operador abrir outro caso: o diálogo não abre sobre o caso errado
        if (this.selectedCase()?.id !== caseId || (p.caseId && p.caseId !== caseId)) {
          return;
        }
        const schedule = p.offer.schedule;
        const date = (iso: string) => iso.split('-').reverse().join('/');
        this.dialog
          .open<CloseAgreementDialog, CloseAgreementData, CloseAgreementResult>(CloseAgreementDialog, {
            data: {
              proposal: p,
              details: [
                { label: 'Devedor', value: `${sheet.debtor.name}${sheet.debtor.maskedDocument ? ' · ' + sheet.debtor.maskedDocument : ''}` },
                { label: 'Credor', value: this.creditors.name(p.creditorId) },
                { label: 'Total', value: formatAmount(p.totalAmount) },
                { label: 'Forma de pagamento', value: planText({ installments: p.installments, downPayment: p.downPayment }) },
                { label: 'Vencimentos', value: schedule.length ? `${date(schedule[0].dueDate)} a ${date(schedule[schedule.length - 1].dueDate)}` : '—' },
              ],
              channel: this.attendance.channel(caseId) ?? p.channel,
              canFormalize: !!authority?.profile.canFormalize,
              canPix: this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE),
            },
            disableClose: false,
          })
          .afterClosed()
          .subscribe((result) => {
            this.dealRevision.update((v) => v + 1);
            const agreement = result?.agreement ?? null;
            if (agreement) {
              this.attendance.setAgreement(caseId, agreement.id);
              if (this.selectedCase()?.id === caseId) {
                this.closedAgreementId.set(agreement.id);
              }
              this.notifications.success('Acordo formalizado.');
              const next = scheduleSummary(agreement).next;
              if (result?.pix && next) {
                installmentPix(this.paymentLauncher, agreement, next, agreement.debtorId).subscribe(() => this.refreshAll());
              }
            } else if (result?.proposal.status === 'ACCEPTED') {
              this.notifications.success('Aceite registrado. O termo do acordo foi gerado.');
            }
            this.refreshAll();
          });
      });
  }

  protected takeOver(): void {
    const c = this.selectedCase();
    if (c) {
      this.workApi.takeOver(c.id, null).subscribe(() => {
        this.notifications.success('Você assumiu o atendimento deste caso.');
        this.refreshAll();
      });
    }
  }

  protected release(): void {
    const c = this.selectedCase();
    if (!c) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Devolver caso à fila',
        message: 'O caso volta à fila e pode ser entregue a outro operador.',
        confirmLabel: 'Devolver',
        reason: 'optional',
        details: [{ label: 'Devedor', value: this.current()?.debtor.name ?? '' }],
      })
      .subscribe((reason) =>
        this.workApi.release(c.id, reason || null).subscribe(() => {
          this.attendance.discard(c.id);
          this.startedAt.set(null);
          this.notifications.success('Caso devolvido à fila.');
          this.myCases.reload();
          this.sheet.reload();
        }),
      );
  }

  protected assign(): void {
    const c = this.selectedCase();
    if (!c) {
      return;
    }
    this.dialog
      .open<AssignDialog, AssignDialogData, CaseView>(AssignDialog, { data: { caseView: c, debtorName: this.current()?.debtor.name ?? '' } })
      .afterClosed()
      .subscribe((saved) => saved && this.refreshAll());
  }

  protected runLifecycle(action: CaseAction): void {
    const c = this.selectedCase();
    if (!c) {
      return;
    }
    const meta = CASE_ACTIONS[action];
    this.confirm
      .confirm({
        title: meta.label,
        message: meta.message,
        confirmLabel: meta.confirm,
        destructive: action === 'close',
        reason: 'required',
        details: [
          { label: 'Devedor', value: this.current()?.debtor.name ?? '' },
          { label: 'Credor', value: this.creditors.name(c.creditorId) },
        ],
        confirmationText: action === 'close' ? 'ENCERRAR' : undefined,
        warning: action === 'close' ? 'O encerramento manual não pode ser desfeito.' : undefined,
      })
      .subscribe((reason) =>
        this.casesApi.lifecycle(c.id, action, reason).subscribe(() => {
          this.notifications.success(`${meta.label}: concluído.`);
          this.refreshAll();
        }),
      );
  }

  protected refreshCase(): void {
    const c = this.selectedCase();
    if (c) {
      this.casesApi.refresh(c.id).subscribe(() => this.refreshAll());
    }
  }

  /** Interação registrada: o rascunho foi limpo e a próxima contagem começa agora. */
  protected onRecorded(_result: RecordResult): void {
    const id = this.selectedCase()?.id;
    if (id) {
      this.startedAt.set(this.attendance.recorded(id));
    }
    this.refreshAll();
  }

  protected refreshAll(): void {
    this.sheet.reload();
    if (this.canWork()) {
      this.myCases.reload();
    }
  }

  private open(id: string): void {
    this.selectedId.set(id);
    const target = this.id() ? ['/cobranca/casos', id] : ['/cobranca/fila'];
    // outro caso abre na aba guardada dele (ou na primeira)
    void this.router.navigate(target, { queryParams: this.id() ? {} : { caso: id }, replaceUrl: true });
  }
}
