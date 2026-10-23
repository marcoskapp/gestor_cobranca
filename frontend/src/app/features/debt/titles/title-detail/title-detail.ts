import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { BreadcrumbService } from '../../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../../shared/components/amount/amount';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { TITLE_MANUAL_ACTIONS, describeStatus } from '../../../../shared/status/status';
import { CreditorNames, PortfolioNames } from '../../../organization/data/organization.lookups';
import { OriginContractsApi, TitlesApi } from '../../data/debt.api';
import { OverdueBadge } from '../../components/overdue-badge';
import { INSTRUMENT_TYPE_LABELS, titleLabel } from '../../data/debt.labels';
import { DebtorNames } from '../../data/debt.lookups';
import { TitleView } from '../../data/debt.models';
import { TitleBalance } from '../title-ledger/title-balance';
import { TitleLedger } from '../title-ledger/title-ledger';
import { TitleOwnership } from '../title-ledger/title-ownership';
import { TitlePrescription } from '../title-ledger/title-prescription';
import { TitleTransfer, TitleTransferData } from '../title-transfer/title-transfer';

type ManualAction = 'suspend' | 'reactivate' | 'cancel' | 'return';

/** Detalhe do título: dados, saldo por componente, razão, titularidade e ações de ciclo de vida. */
@Component({
  selector: 'app-title-detail',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatTooltipModule,
    PageHeader,
    StatusChip,
    Skeleton,
    EmptyState,
    Amount,
    AppDatePipe,
    TitleBalance,
    TitleLedger,
    TitleOwnership,
    TitlePrescription,
    OverdueBadge,
  ],
  templateUrl: './title-detail.html',
  styleUrl: '../../debt.scss',
})
export class TitleDetail {
  readonly id = input.required<string>();

  private readonly api = inject(TitlesApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly title = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.title, undefined);

  private readonly contractsApi = inject(OriginContractsApi);
  /** Contrato de origem (produto e instrumento); indisponível não impede a tela. */
  protected readonly contract = rxResource({
    params: () => this.current()?.originContractId ?? undefined,
    stream: ({ params }) => this.contractsApi.get(params).pipe(catchError(() => of(null))),
  });
  protected readonly contractView = valueOr(this.contract, null);
  protected readonly instruments = INSTRUMENT_TYPE_LABELS;

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DEBT_TITLE_MANAGE));
  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly actions = computed(() => {
    const t = this.current();
    return t && this.canManage() ? (TITLE_MANUAL_ACTIONS[t.status] ?? []) : [];
  });
  protected readonly canTransfer = computed(() => this.actions().includes('return'));

  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.canOpenDebtor() ? this.current()?.debtorId : null]);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.current()?.portfolioId]);

  protected readonly titleLabel = titleLabel;

  constructor() {
    effect(() => {
      const t = this.current();
      this.breadcrumbs.setCurrentLabel(t ? `Título ${titleLabel(t)}` : null);
    });
  }

  protected run(action: ManualAction, t: TitleView): void {
    const details = this.impact(t);
    const configs: Record<ManualAction, () => Observable<string>> = {
      suspend: () =>
        this.confirm.confirm({
          title: 'Suspender cobrança do título',
          message: 'O título deixa de ser cobrado (contatos, réguas e propostas) até ser reativado.',
          confirmLabel: 'Suspender',
          reason: 'required',
          details,
        }),
      reactivate: () =>
        this.confirm.confirm({
          title: 'Reativar cobrança do título',
          message: 'O título volta a ser cobrado normalmente.',
          confirmLabel: 'Reativar',
          details,
        }),
      cancel: () =>
        this.confirm.confirm({
          title: 'Cancelar título',
          message: 'O título deixa de existir para a cobrança. O razão e o histórico são preservados.',
          confirmLabel: 'Cancelar título',
          destructive: true,
          reason: 'required',
          warning: 'Esta ação não pode ser desfeita.',
          confirmationText: 'CANCELAR',
          details,
        }),
      return: () =>
        this.confirm.confirm({
          title: 'Devolver ao credor',
          message: 'A titularidade é encerrada e o título sai da carteira em cobrança.',
          confirmLabel: 'Devolver',
          destructive: true,
          reason: 'required',
          warning: 'Esta ação não pode ser desfeita.',
          confirmationText: 'DEVOLVER',
          details,
        }),
    };
    configs[action]().subscribe((reason) => {
      const request: Observable<TitleView> =
        action === 'suspend'
          ? this.api.suspend(t.id, reason)
          : action === 'reactivate'
            ? this.api.reactivate(t.id)
            : action === 'cancel'
              ? this.api.cancel(t.id, reason)
              : this.api.returnToCreditor([t.id], reason).pipe(map((list) => list[0]));
      request.subscribe((updated) => {
        this.notifications.success(SUCCESS[action]);
        this.title.set(updated);
      });
    });
  }

  protected transfer(t: TitleView): void {
    this.dialog
      .open<TitleTransfer, TitleTransferData, TitleView>(TitleTransfer, { data: { title: t, details: this.impact(t) }, width: '560px' })
      .afterClosed()
      .subscribe((updated) => updated && this.title.set(updated));
  }

  /** Resumo do impacto exibido nas confirmações (devedor sem documento). */
  private impact(t: TitleView): { label: string; value: string }[] {
    return [
      { label: 'Título', value: titleLabel(t) },
      ...(this.canOpenDebtor() ? [{ label: 'Devedor', value: this.debtors.name(t.debtorId) }] : []),
      { label: 'Credor', value: this.creditors.name(t.creditorId) },
      { label: 'Carteira', value: this.portfolios.name(t.portfolioId) },
      { label: 'Saldo em aberto', value: formatAmount(t.openBalance) },
      { label: 'Situação atual', value: describeStatus('title', t.status).label },
    ];
  }
}

const SUCCESS: Record<ManualAction, string> = {
  suspend: 'Cobrança do título suspensa.',
  reactivate: 'Cobrança do título reativada.',
  cancel: 'Título cancelado.',
  return: 'Título devolvido ao credor.',
};
