import { Component, DestroyRef, computed, effect, inject, input, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { KeyboardShortcuts } from '../../../core/keyboard/keyboard-shortcuts.service';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { CommandPaletteService } from '../../../core/layout/command-palette/command-palette.service';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
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
import { CaseSheetPanel, SHEET_TABS } from './case-sheet-panel';
import { ContactWindowPanel } from './contact-window';
import { DensityInvite } from './density-invite';
import { DisputeDialog, DisputeDialogData } from './dispute-dialog';
import { InteractionPanel } from './interaction-panel';
import { ScheduleDialog, ScheduleDialogData } from './schedule-dialog';
import { CallButton } from '../../telephony/station/call-button';
import { PresenceBar } from '../../telephony/station/presence-bar';

const GROUP = 'Estação de cobrança';

/**
 * Estação de cobrança em três zonas com rolagem própria: fila e "próximo caso" (esquerda,
 * recolhível), ficha do caso (centro) e painel de ação (direita) com o "posso contatar agora?" sempre
 * visível. Atalhos do grupo "Estação de cobrança" (`Alt + N`, `Alt + R`, `Alt + A`, `Alt + S`,
 * `Alt + 1…6`) ficam ativos só nesta tela.
 *
 * Rotas: `/cobranca/fila[?caso=id]` (operador) e `/cobranca/casos/:id` (abrir um caso específico).
 */
@Component({
  selector: 'app-collection-station',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
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
    PresenceBar,
  ],
  templateUrl: './collection-station.html',
  styleUrl: './collection-station.scss',
})
export class CollectionStation {
  /** `/cobranca/casos/:id` */
  readonly id = input<string>();
  /** `/cobranca/fila?caso=` */
  readonly caso = input<string>();

  private readonly auth = inject(AuthService);
  private readonly casesApi = inject(CasesApi);
  private readonly workApi = inject(CaseWorkApi);
  private readonly tabulationsApi = inject(TabulationsApi);
  private readonly settingsApi = inject(CollectionSettingsApi);
  private readonly queueNames = inject(QueueNames);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly interactionPanel = viewChild(InteractionPanel);
  private readonly callButton = viewChild(CallButton);

  protected readonly caseActions = CASE_ACTIONS;
  protected readonly nextActionText = nextActionText;

  protected readonly canWork = computed(() => this.auth.hasPermission(Permission.CASE_WORK));
  protected readonly canAssign = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CASE_MANAGE));
  protected readonly canRegister = computed(() => this.auth.hasPermission(Permission.INTERACTION_REGISTER));
  protected readonly canSchedule = computed(() => this.auth.hasPermission(Permission.TASK_MANAGE));
  protected readonly canDispute = computed(() => this.auth.hasAnyPermission([Permission.DISPUTE_MANAGE, Permission.CASE_WORK]));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  private readonly userId = computed(() => this.auth.user()?.id ?? null);

  /** Zona da fila recolhida (mais espaço para a ficha). */
  protected readonly queueCollapsed = signal(false);
  protected readonly queueId = signal<string>('');
  protected readonly nextLoading = signal(false);
  protected readonly tab = signal(0);

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
    // Novo caso aberto: cronômetro reinicia e a ficha volta à primeira aba.
    effect(() => {
      const id = this.selectedCase()?.id;
      untracked(() => {
        this.startedAt.set(id ? new Date() : null);
        if (id) {
          this.tab.set(0);
        }
      });
    });
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
      keys.register({ id: 'collection.queue', keys: 'alt+q', group: GROUP, description: 'Recolher ou expandir a fila', handler: () => this.queueCollapsed.update((v) => !v) }),
      ...SHEET_TABS.map((label, i) =>
        keys.register({ id: `collection.tab-${i + 1}`, keys: `alt+${i + 1}`, group: GROUP, description: `Aba ${label}`, handler: () => this.tab.set(i) }),
      ),
    ];
    const palette = inject(CommandPaletteService).register([
      { id: 'collection.next-case', label: 'Próximo caso', group: 'Ações', icon: 'skip_next', hint: 'Alt + N', keywords: ['fila', 'cobrança'], run: () => this.nextCase() },
      { id: 'collection.register', label: 'Registrar interação', group: 'Ações', icon: 'task_alt', hint: 'Alt + R', keywords: ['tabulação'], run: () => this.interactionPanel()?.focus() },
      { id: 'collection.schedule', label: 'Agendar retorno', group: 'Ações', icon: 'event', hint: 'Alt + A', run: () => this.schedule() },
    ]);
    destroy.onDestroy(() => {
      off.forEach((fn) => fn());
      palette();
    });
  }

  // ------------------------------------------------------------------ ações

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
        width: '520px',
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
        width: '640px',
      })
      .afterClosed()
      .subscribe((dispute) => dispute && this.refreshAll());
  }

  protected simulate(): void {
    const c = this.selectedCase();
    if (c && this.canSimulate()) {
      void this.router.navigate(['/negociacao/simulador'], { queryParams: { credor: c.creditorId, devedor: c.debtorId, caso: c.id } });
    }
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
      .open<AssignDialog, AssignDialogData, CaseView>(AssignDialog, { data: { caseView: c, debtorName: this.current()?.debtor.name ?? '' }, width: '520px' })
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

  protected onRecorded(_result: RecordResult): void {
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
    void this.router.navigate(target, { queryParams: this.id() ? {} : { caso: id }, replaceUrl: true });
  }
}
