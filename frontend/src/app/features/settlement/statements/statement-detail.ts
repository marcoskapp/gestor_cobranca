import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, finalize, map } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { ADVANCER_LABELS, EXPENSE_TYPE_LABELS } from '../../legal/data/legal.labels';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ReceiptTable } from '../components/receipt-table';
import { StatementsApi, TransfersApi } from '../data/settlement.api';
import { ADJUSTMENT_CATEGORY_LABELS, EXPORT_FORMAT_HINTS, EXPORT_FORMAT_LABELS, SEND_CHANNEL_LABELS, SETTLEMENT_ERROR_LABELS, STATEMENT_CYCLE } from '../data/settlement.labels';
import { AdjustmentView, ExportFormat, ExportView, StatementView } from '../data/settlement.models';
import { canDecideAdjustment, cycleIndex, groupedHash, isApproved, periodText, receiptsEffect, round2, statementActions } from '../data/settlement.rules';
import { TransferDialog, TransferDialogData } from '../transfers/transfer-dialog';
import { AdjustmentDialog, AdjustmentDialogData, SendDialog, SendDialogData } from './statement-dialogs';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbNavModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../shared/dialog/dialog.service';

/**
 * Demonstrativo do credor: totais (RB08) com a conferência do líquido, ciclo com ações e a
 * segregação de funções explicada (quem calcula ou pede ajuste não aprova), recebimentos, custas
 * reembolsáveis, ajustes (segunda aprovação acima do limite), arquivos com SHA-256, envio, resposta
 * do credor e repasses.
 */
@Component({
  selector: 'app-statement-detail',
  imports: [RouterLink, NgbNavModule, NgbTooltip, Icon, Amount, AppDatePipe, EmptyState, PageHeader, ReceiptTable, Skeleton, StatusChip],
  templateUrl: './statement-detail.html',
  styleUrl: '../settlement.scss',
})
export class StatementDetail {
  readonly id = input.required<string>();

  private readonly api = inject(StatementsApi);
  private readonly transfersApi = inject(TransfersApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly cycle = STATEMENT_CYCLE;
  protected readonly categories = ADJUSTMENT_CATEGORY_LABELS;
  protected readonly formats = EXPORT_FORMAT_LABELS;
  protected readonly formatHints = EXPORT_FORMAT_HINTS;
  protected readonly channels = SEND_CHANNEL_LABELS;
  protected readonly expenseTypes = EXPENSE_TYPE_LABELS;
  protected readonly advancers = ADVANCER_LABELS;
  protected readonly exportFormats: ExportFormat[] = ['CSV', 'XLSX', 'PDF'];
  protected readonly groupedHash = groupedHash;
  private readonly userId = computed(() => this.auth.user()?.id ?? null);
  protected readonly actorId = this.userId;
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.SETTLEMENT_APPROVE));

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly statement = valueOr(this.resource, undefined);
  /** Recarrega listas auxiliares depois de cada ação. */
  private readonly round = signal(0);
  private readonly adjustmentList = rxResource({ params: () => ({ id: this.id(), r: this.round() }), stream: ({ params }) => this.api.adjustments(params.id) });
  protected readonly adjustments = valueOr(this.adjustmentList, []);
  private readonly expenseList = rxResource({ params: () => ({ id: this.id(), r: this.round() }), stream: ({ params }) => this.api.expenses(params.id) });
  protected readonly expenses = valueOr(this.expenseList, []);
  private readonly exportList = rxResource({ params: () => ({ id: this.id(), r: this.round() }), stream: ({ params }) => this.api.exports(params.id) });
  private readonly exportsRaw = valueOr(this.exportList, []);
  protected readonly exportRows = computed(() => [...this.exportsRaw()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
  private readonly transferList = rxResource({
    params: () => (this.statement() && isApproved(this.statement()!) ? { creditorId: this.statement()!.creditorId, r: this.round() } : undefined),
    stream: ({ params }) => this.transfersApi.search(params.creditorId, { page: 0, size: 200 }).pipe(map((p) => p.content)),
  });
  private readonly transfersRaw = valueOr(this.transferList, []);
  protected readonly transfers = computed(() => this.transfersRaw().filter((t) => t.statementId === this.id()));
  private readonly previousList = rxResource({
    params: () => this.statement()?.creditorId,
    stream: ({ params }) => this.api.search({ creditorId: params, status: null }, { page: 0, size: 50 }).pipe(map((p) => p.content)),
  });
  private readonly previousRaw = valueOr(this.previousList, []);
  protected readonly receiptQuery = pagedQuery(
    computed(() => `${this.id()}:${this.statement()?.calculatedAt ?? ''}:${this.round()}`),
    (_, page) => this.api.receipts(this.id(), page),
  );

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.statement()?.creditorId]);
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const s = this.statement();
      return [
        s?.calculatedBy,
        s?.reviewedBy,
        s?.approvedBy,
        s?.sentBy,
        s?.creditorResponseBy,
        ...this.adjustments().flatMap((a) => [a.requestedBy, a.decidedBy]),
        ...this.exportRows().map((x) => x.generatedBy),
        ...this.transfers().map((t) => t.registeredBy),
      ];
    },
    { batch: USER_NAMES_BATCH },
  );

  protected readonly actions = computed(() => {
    const s = this.statement();
    return s
      ? statementActions(s, {
          userId: this.userId(),
          canClose: this.auth.hasPermission(Permission.SETTLEMENT_CLOSE),
          canApprove: this.canApprove(),
          canTransfer: this.auth.hasPermission(Permission.SETTLEMENT_TRANSFER),
          today: zonedNow().date,
          adjustments: this.adjustments(),
          exportsCount: this.exportRows().length,
        })
      : null;
  });
  protected readonly stepIndex = computed(() => cycleIndex(this.statement()?.status ?? 'DRAFT'));
  protected readonly effectSum = computed(() => {
    const t = this.statement()?.totals;
    return t ? receiptsEffect(t) : 0;
  });
  protected readonly deducted = computed(() => round2(this.expenses().filter((e) => e.effect === 'DEDUCT').reduce((s, e) => s + e.amount, 0)));
  protected readonly informative = computed(() => round2(this.expenses().filter((e) => e.effect === 'INFORMATIVE').reduce((s, e) => s + e.amount, 0)));
  protected readonly pendingAdjustments = computed(() => this.adjustments().filter((a) => a.status === 'PENDING_SECOND_APPROVAL').length);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly downloading = signal<string | null>(null);
  protected readonly verified = signal<ReadonlyMap<string, boolean>>(new Map());

  constructor() {
    effect(() => {
      const s = this.statement();
      this.breadcrumbs.setCurrentLabel(s ? `nº ${s.sequenceNumber} · ${periodText(s.periodStart, s.periodEnd)}` : null);
    });
  }

  protected period(s: StatementView): string {
    return periodText(s.periodStart, s.periodEnd);
  }

  protected userName(id: string | null | undefined): string {
    if (!id) {
      return '—';
    }
    return id === this.userId() ? 'você' : this.users.name(id);
  }

  protected canDecide(a: AdjustmentView): boolean {
    return canDecideAdjustment(a, this.userId(), this.canApprove());
  }

  protected size(bytes: number): string {
    return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1).replace('.', ',')} KB` : `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
  }

  // ------------------------------------------------------------------ ciclo

  protected calculate(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: s.status === 'DRAFT' ? 'Calcular demonstrativo' : 'Recalcular demonstrativo',
        message: 'Inclui os recebimentos apurados e as custas ainda não prestados com competência até o fim do período (inclusive atrasados de períodos anteriores). Recalcular desfaz a revisão.',
        confirmLabel: s.status === 'DRAFT' ? 'Calcular' : 'Recalcular',
        details: [
          { label: 'Credor', value: this.creditors.name(s.creditorId) },
          { label: 'Período', value: this.period(s) },
        ],
      })
      .subscribe(() => this.run(this.api.action(s.id, 'calculate'), 'Demonstrativo calculado.'));
  }

  protected review(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: 'Marcar como revisado',
        message: 'Confirma que conferiu recebimentos, custas e ajustes. A aprovação será de outra pessoa (gestor), nunca de quem calculou.',
        confirmLabel: 'Revisado',
        details: this.impact(s),
      })
      .subscribe(() => this.run(this.api.action(s.id, 'review'), 'Demonstrativo revisado. Aguarda a aprovação.'));
  }

  protected approve(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: 'Aprovar demonstrativo',
        message: 'Depois de aprovado, o demonstrativo não muda: estornos, custas canceladas e correções entram no período seguinte. O valor líquido passa a compor o saldo a repassar.',
        confirmLabel: 'Aprovar',
        details: [...this.impact(s), { label: 'Calculado por', value: this.userName(s.calculatedBy) }, { label: 'Revisado por', value: this.userName(s.reviewedBy) }],
        warning: 'Ação financeira irreversível.',
        confirmationText: 'APROVAR',
      })
      .subscribe(() => this.run(this.api.action(s.id, 'approve'), 'Demonstrativo aprovado.'));
  }

  protected send(): void {
    const s = this.statement()!;
    this.dialog
      .open<SendDialog, SendDialogData, StatementView>(SendDialog, { data: { statement: s, exports: this.exportRows() } })
      .afterClosed()
      .subscribe((saved) => this.applied(saved, 'Envio registrado. Registre a resposta do credor quando ela chegar.'));
  }

  protected acknowledge(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: 'Registrar aceite do credor',
        message: 'O credor aceitou o demonstrativo. O aceite é registrado pela assessoria em nome do credor.',
        confirmLabel: 'Registrar aceite',
        reason: 'optional',
        reasonLabel: 'Observação (ex.: e-mail de aceite)',
        details: this.impact(s),
      })
      .subscribe((note) => this.run(this.api.acknowledge(s.id, note || null), 'Aceite do credor registrado.'));
  }

  protected contest(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: 'Registrar contestação do credor',
        message: 'A contestação é resolvida com ajuste no demonstrativo seguinte (acerto de contestação) e o aceite registrado depois da correção.',
        confirmLabel: 'Registrar contestação',
        destructive: true,
        reason: 'required',
        reasonLabel: 'O que o credor contestou',
        details: this.impact(s),
      })
      .subscribe((reason) => this.run(this.api.contest(s.id, reason), 'Contestação registrada.'));
  }

  protected cancel(): void {
    const s = this.statement()!;
    this.confirm
      .confirm({
        title: 'Cancelar demonstrativo',
        message: 'Os recebimentos e custas voltam a ficar disponíveis para o próximo demonstrativo do credor.',
        confirmLabel: 'Cancelar demonstrativo',
        destructive: true,
        reason: 'required',
        details: [
          { label: 'Credor', value: this.creditors.name(s.creditorId) },
          { label: 'Período', value: this.period(s) },
        ],
      })
      .subscribe((reason) => this.run(this.api.cancel(s.id, reason), 'Demonstrativo cancelado.'));
  }

  // ------------------------------------------------------------------ ajustes

  protected requestAdjustment(): void {
    const s = this.statement()!;
    const previous = this.previousRaw().filter((x) => x.id !== s.id && isApproved(x));
    this.dialog
      .open<AdjustmentDialog, AdjustmentDialogData, AdjustmentView>(AdjustmentDialog, { data: { statement: s, previous } })
      .afterClosed()
      .subscribe((a) => {
        if (a) {
          this.notifications.success(a.status === 'PENDING_SECOND_APPROVAL' ? 'Ajuste aguardando a segunda aprovação.' : 'Ajuste aplicado. O demonstrativo precisa de nova revisão.');
          this.refresh();
        }
      });
  }

  protected decide(a: AdjustmentView, decision: 'approve' | 'reject'): void {
    this.confirm
      .confirm({
        title: decision === 'approve' ? 'Aprovar ajuste' : 'Recusar ajuste',
        message: decision === 'approve' ? 'O ajuste é aplicado e o demonstrativo volta a calculado (nova revisão).' : 'O ajuste não é aplicado; o motivo fica registrado.',
        confirmLabel: decision === 'approve' ? 'Aprovar ajuste' : 'Recusar ajuste',
        destructive: decision === 'reject',
        reason: decision === 'approve' ? 'optional' : 'required',
        reasonLabel: decision === 'approve' ? 'Observação (opcional)' : 'Motivo da recusa',
        details: [
          { label: 'Categoria', value: this.categories[a.category] },
          { label: 'Efeito', value: formatAmount(a.amount) },
          { label: 'Pedido por', value: this.userName(a.requestedBy) },
          { label: 'Motivo', value: a.reason },
        ],
      })
      .subscribe((note) => this.run(this.api.decideAdjustment(a.id, decision, note || null), decision === 'approve' ? 'Ajuste aprovado e aplicado.' : 'Ajuste recusado.', false));
  }

  // ------------------------------------------------------------------ arquivos

  protected generate(format: ExportFormat): void {
    const s = this.statement()!;
    this.busy.set(true);
    this.actionError.set(null);
    this.api
      .export(s.id, format)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (x) => {
          this.notifications.success(`${this.formats[x.format]} gerado (${x.rows} registros).`);
          this.refresh();
        },
        error: (err: unknown) => this.fail(err),
      });
  }

  protected download(x: ExportView): void {
    this.downloading.set(x.id);
    this.api
      .download(x)
      .pipe(finalize(() => this.downloading.set(null)))
      .subscribe((result) => {
        this.verified.update((m) => new Map([...m, [x.id, result.verified]]));
        if (result.verified) {
          this.notifications.success(`${result.fileName} baixado. SHA-256 conferido.`);
        } else {
          this.notifications.error(`${result.fileName} baixado, mas o SHA-256 não confere com o registrado. Não envie este arquivo; baixe de novo.`);
        }
      });
  }

  // ------------------------------------------------------------------ repasse

  protected registerTransfer(): void {
    const s = this.statement()!;
    const t = s.totals;
    if (!t) {
      return;
    }
    this.dialog
      .open<TransferDialog, TransferDialogData>(TransferDialog, {
        data: { creditorName: this.creditors.name(s.creditorId), statement: { statementId: s.id, sequenceNumber: s.sequenceNumber, periodStart: s.periodStart, periodEnd: s.periodEnd, outstanding: t.outstanding } },
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Repasse registrado com o comprovante.');
          this.resource.reload();
          this.refresh();
        }
      });
  }

  protected downloadEvidence(id: string): void {
    this.transfersApi.downloadEvidence(id).subscribe((name) => this.notifications.success(`Comprovante baixado: ${name}`));
  }

  private impact(s: StatementView): { label: string; value: string }[] {
    const t = s.totals;
    return [
      { label: 'Credor', value: this.creditors.name(s.creditorId) },
      { label: 'Período', value: this.period(s) },
      { label: 'Recuperado', value: formatAmount(t?.recovered ?? 0) },
      { label: 'Comissão + fixo', value: formatAmount((t?.commission ?? 0) + (t?.fixedFees ?? 0)) },
      { label: (t?.netTransfer ?? 0) < 0 ? 'Credor deve à assessoria' : 'Líquido a repassar', value: formatAmount(Math.abs(t?.netTransfer ?? 0)) },
    ];
  }

  private run(call: Observable<unknown>, message: string, isStatement = true): void {
    this.busy.set(true);
    this.actionError.set(null);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (result) => {
        if (isStatement) {
          this.resource.set(result as StatementView);
        } else {
          this.resource.reload();
        }
        this.refresh();
        this.notifications.success(message);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  private applied(saved: StatementView | undefined, message: string): void {
    if (saved) {
      this.resource.set(saved);
      this.refresh();
      this.notifications.success(message);
    }
  }

  private refresh(): void {
    this.round.update((n) => n + 1);
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.actionError.set((e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e));
  }
}
