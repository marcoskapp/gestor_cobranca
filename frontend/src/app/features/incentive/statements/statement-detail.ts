import { Component, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { LedgerTable } from '../components/ledger-table';
import { ReasonDialog, ReasonDialogData } from '../components/reason-dialog';
import { StatementLines } from '../components/statement-lines';
import { CommissionStatementsApi } from '../data/incentive.api';
import { AdjustmentView, DisputeView, LedgerEntryView, StatementDetail, StatementLine } from '../data/incentive.models';
import { canDecide, canResolve, disputeTarget, linesTotal, monthLabel, statementActions } from '../data/incentive.rules';
import { AdjustDialog, AdjustDialogData, DisputeDialog, DisputeDialogData, ResolveDialog, ResolveDialogData, incentiveError } from './statement-dialogs';

/**
 * Extrato de comissão do negociador: valores, memória de cálculo linha a linha (contestável),
 * contestações e resolução, ajustes com segunda aprovação, lançamentos do período e aprovação
 * financeira com a segregação de funções explicada.
 */
@Component({
  selector: 'app-incentive-statement-detail',
  imports: [RouterLink, Amount, AppDatePipe, EmptyState, Icon, LedgerTable, PageHeader, Skeleton, StatementLines, StatusChip],
  templateUrl: './statement-detail.html',
  styleUrl: '../incentive.scss',
})
export class StatementDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(CommissionStatementsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly ledger = viewChild(LedgerTable);

  protected readonly month = monthLabel;
  protected readonly target = disputeTarget;
  protected readonly userId = computed(() => this.auth.user()?.id ?? null);
  private readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_STATEMENT_MANAGE));
  private readonly canApprove = computed(() => this.auth.hasPermission(Permission.COMMISSION_STATEMENT_APPROVE));

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly detail = valueOr(this.resource, undefined);
  protected readonly actions = computed(() => {
    const d = this.detail();
    return d ? statementActions(d, { userId: this.userId(), canManage: this.canManage(), canApprove: this.canApprove() }) : null;
  });
  protected readonly linesSum = computed(() => linesTotal(this.detail()?.lines ?? []));
  protected readonly openDisputes = computed(() => (this.detail()?.disputes ?? []).filter((d) => d.status === 'OPEN').length);
  protected readonly pendingAdjustments = computed(() => (this.detail()?.adjustments ?? []).filter((a) => a.status === 'PENDING_SECOND_APPROVAL').length);
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const d = this.detail();
      return [d?.approvedBy, ...(d?.disputes ?? []).flatMap((x) => [x.openedBy, x.resolvedBy]), ...(d?.adjustments ?? []).flatMap((a) => [a.requestedBy, a.decidedBy])];
    },
    { batch: USER_NAMES_BATCH },
  );
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  /** Ligação estável para o `app-ledger-table` (contestação de lançamento). */
  protected readonly disputeEntryFn = (entry: LedgerEntryView) => this.openDispute({ entry });

  constructor() {
    effect(() => {
      const d = this.detail();
      this.breadcrumbs.setCurrentLabel(d ? `${d.summary.userName ?? 'Negociador'} · ${monthLabel(d.summary.period)}` : null);
    });
  }

  protected person(id: string | null | undefined): string {
    if (!id) {
      return '—';
    }
    return id === this.userId() ? 'você' : this.users.name(id);
  }

  protected resolvable(d: DisputeView): boolean {
    return canResolve(d, this.detail()!.summary.status, this.userId(), this.canManage());
  }

  protected decidable(a: AdjustmentView): boolean {
    return canDecide(a, this.detail()!.summary.status, this.userId(), this.canManage());
  }

  // ------------------------------------------------------------------ contestação

  protected openDispute(target: { line?: StatementLine; entry?: LedgerEntryView } = {}): void {
    const d = this.detail()!;
    this.dialog
      .open<DisputeDialog, DisputeDialogData, DisputeView>(DisputeDialog, { data: { statementId: d.summary.id, lines: d.lines, ...target } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Contestação enviada. A gestão vai analisar e responder.');
          this.resource.reload();
        }
      });
  }

  protected resolve(dispute: DisputeView): void {
    const d = this.detail()!;
    this.dialog
      .open<ResolveDialog, ResolveDialogData, DisputeView>(ResolveDialog, {
        data: { statementId: d.summary.id, dispute, target: disputeTarget(dispute), openedBy: this.person(dispute.openedBy) },
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(saved.status === 'ACCEPTED' ? 'Contestação aceita.' : 'Contestação recusada.');
          this.resource.reload();
        }
      });
  }

  // ------------------------------------------------------------------ ajustes

  protected adjust(): void {
    const s = this.detail()!.summary;
    this.dialog
      .open<AdjustDialog, AdjustDialogData, AdjustmentView>(AdjustDialog, {
        data: { statementId: s.id, beneficiary: s.userName ?? 'Negociador', period: monthLabel(s.period), payable: s.payableAmount },
      })
      .afterClosed()
      .subscribe((a) => {
        if (a) {
          this.notifications.success(a.status === 'PENDING_SECOND_APPROVAL' ? 'Ajuste aguardando a segunda aprovação.' : 'Ajuste aplicado.');
          this.resource.reload();
        }
      });
  }

  protected decide(a: AdjustmentView, approve: boolean): void {
    const s = this.detail()!.summary;
    const data: ReasonDialogData = {
      title: approve ? 'Aprovar ajuste (segunda aprovação)' : 'Recusar ajuste',
      message: approve ? 'O ajuste é aplicado ao extrato. Você passa a não poder aprovar este extrato (segregação).' : 'O ajuste não é aplicado; o motivo fica registrado.',
      label: approve ? 'Observação (opcional)' : 'Motivo da recusa',
      confirmLabel: approve ? 'Aprovar ajuste' : 'Recusar ajuste',
      min: approve ? 0 : undefined,
      destructive: !approve,
      details: [
        { label: 'Negociador', value: s.userName ?? '—' },
        { label: 'Período', value: monthLabel(s.period) },
        { label: 'Valor', value: formatAmount(a.amount) },
        { label: 'Pedido por', value: this.person(a.requestedBy) },
        { label: 'Motivo', value: a.reason },
      ],
    };
    this.dialog
      .open<ReasonDialog, ReasonDialogData, string>(ReasonDialog, { data, role: approve ? 'dialog' : 'alertdialog' })
      .afterClosed()
      .subscribe((note) => {
        if (note !== undefined) {
          this.run(this.api.decide(s.id, a.id, approve, note || null), approve ? 'Ajuste aprovado e aplicado.' : 'Ajuste recusado.');
        }
      });
  }

  // ------------------------------------------------------------------ ciclo

  protected recalculate(): void {
    const s = this.detail()!.summary;
    this.confirm
      .confirm({
        title: 'Recalcular extrato',
        message: 'Aplica de novo o plano sobre os lançamentos liberados do período (por exemplo, depois de nova monitoria ou de ocorrência dispensada). Contestações e ajustes são mantidos.',
        confirmLabel: 'Recalcular',
        details: [
          { label: 'Negociador', value: s.userName ?? '—' },
          { label: 'Período', value: monthLabel(s.period) },
          { label: 'Calculado hoje', value: formatAmount(s.calculatedAmount) },
        ],
      })
      .subscribe(() => this.run(this.api.recalculate(s.id), 'Extrato recalculado.', true));
  }

  protected approve(): void {
    const s = this.detail()!.summary;
    this.confirm
      .confirm({
        title: 'Aprovar extrato',
        message: 'Depois de aprovado, o extrato não muda: correções entram no período seguinte como ajuste. O valor a pagar segue para a exportação da folha.',
        confirmLabel: 'Aprovar',
        details: [
          { label: 'Negociador', value: s.userName ?? '—' },
          { label: 'Período', value: monthLabel(s.period) },
          { label: 'Plano', value: s.planCode ? `${s.planCode} v${s.planVersion}` : 'Sem plano' },
          { label: 'Calculado', value: formatAmount(s.calculatedAmount) },
          { label: 'Ajustes', value: formatAmount(s.adjustmentsAmount) },
          { label: 'A pagar', value: formatAmount(s.payableAmount) },
        ],
        warning: 'Ação financeira irreversível.',
        confirmationText: 'APROVAR',
      })
      .subscribe(() => this.run(this.api.approve(s.id), 'Extrato aprovado.', true));
  }

  private run(call: Observable<unknown>, message: string, isDetail = false): void {
    this.busy.set(true);
    this.actionError.set(null);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (result) => {
        if (isDetail) {
          this.resource.set(result as StatementDetail);
        } else {
          this.resource.reload();
        }
        this.ledger()?.reload();
        this.notifications.success(message);
      },
      error: (err: unknown) => this.actionError.set(incentiveError(err)),
    });
  }
}
