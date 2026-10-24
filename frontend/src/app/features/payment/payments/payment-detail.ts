import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, catchError, finalize, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { NameSource, nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { Option } from '../../../shared/models/lookup';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { TitlesApi } from '../../debt/data/debt.api';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { AgreementsApi } from '../../negotiation/data/agreement.api';
import { AgreementView } from '../../negotiation/data/agreement.models';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentsApi } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS, OUTCOME_LABELS, PAYMENT_ERROR_LABELS, REVIEW_MODE_LABELS, SOURCE_LABELS } from '../data/payment.labels';
import { AllocationView, LineView, PaymentView } from '../data/payment.models';
import { allocationTarget, paymentActions, paymentTimeline, requestedTargetText } from '../data/payment.rules';
import { ReallocateDialog, ReallocateDialogData } from '../reallocation/reallocate-dialog';
import { RequestedTarget } from '../components/requested-target';
import { ReverseDialog, ReverseDialogData } from './reverse-dialog';

/**
 * Ficha do pagamento: valores e destinos (alocações com o resultado da baixa), linha do tempo dos
 * estados, comprovante, revisão por segunda pessoa, realocação e estorno.
 */
@Component({
  selector: 'app-payment-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, Amount, AppDatePipe, EmptyState, PageHeader, Skeleton, StatusChip, RequestedTarget],
  templateUrl: './payment-detail.html',
  styleUrls: ['../../negotiation/detail-page.scss', './payment-detail.scss'],
})
export class PaymentDetail {
  readonly id = input.required<string>();

  private readonly api = inject(PaymentsApi);
  private readonly agreementsApi = inject(AgreementsApi);
  private readonly titlesApi = inject(TitlesApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly methods = METHOD_LABELS;
  protected readonly origins = ORIGIN_LABELS;
  protected readonly sources = SOURCE_LABELS;
  protected readonly reviewModes = REVIEW_MODE_LABELS;
  protected readonly outcomes = OUTCOME_LABELS;
  protected readonly target = allocationTarget;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly payment = valueOr<PaymentView | undefined>(this.resource, undefined);
  protected readonly busy = signal(false);
  protected readonly reviewError = signal<string | null>(null);

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.payment()?.creditorId]);
  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.payment()?.debtorId], { batch: 100 });
  protected readonly users = nameMap(
    inject(UserNames),
    () => {
      const p = this.payment();
      return p ? [p.informedBy, p.reviewedBy, p.reversal?.reversedBy] : [];
    },
    { batch: USER_NAMES_BATCH },
  );
  protected readonly canViewAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  protected readonly canViewTitles = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));

  /** Número das parcelas: lido dos acordos das alocações (a alocação traz só o id da parcela). */
  private readonly agreements = rxResource({
    params: () => {
      const p = this.payment();
      const ids = [...new Set([...(p?.allocations ?? []).map((a) => a.agreementId), p?.requestedTarget?.agreementId].filter((x): x is string => !!x))];
      return this.canViewAgreements() && ids.length ? ids : undefined;
    },
    stream: ({ params }) => forkJoin(params.map((id) => this.agreementsApi.get(id).pipe(catchError(() => of(null))))),
  });
  private readonly agreementList = valueOr(this.agreements, []);
  private readonly installmentNumbers = computed(() => {
    const map = new Map<string, number>();
    for (const a of this.agreementList().filter((x): x is AgreementView => !!x)) {
      for (const i of a.installments) map.set(i.id, i.number);
    }
    return map;
  });
  private readonly titleSource: NameSource = {
    resolve: (ids: string[]): Observable<Option[]> =>
      forkJoin(
        ids.map((id) =>
          this.titlesApi.get(id).pipe(
            map((t): Option => ({ value: id, label: `Título ${t.externalId}${t.installmentNumber ? '/' + t.installmentNumber : ''}` })),
            catchError(() => of<Option>({ value: id, label: 'Título' })),
          ),
        ),
      ),
  };
  protected readonly titles = nameMap(this.titleSource, () =>
    this.canViewTitles()
      ? [
          ...(this.payment()?.allocations ?? []).flatMap((a) => a.lines.map((l) => l.titleId)),
          ...(this.payment()?.requestedTarget?.kind === 'TITLES' ? this.payment()!.requestedTarget!.itemIds : []),
        ]
      : [],
  );

  /** Destino solicitado em texto (fila de aprovação e resumo do impacto antes de aprovar). */
  protected readonly requestedText = computed(() => {
    const t = this.payment()?.requestedTarget;
    if (!t) {
      return null;
    }
    const agreement = this.agreementList().find((a) => a?.id === t.agreementId);
    return requestedTargetText(t, {
      agreementLabel: agreement?.formalizedAt ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' }).format(new Date(agreement.formalizedAt)) : null,
      installmentNumbers: this.installmentNumbers(),
      titleLabels: new Map(t.itemIds.map((id) => [id, this.titles.name(id)] as const).filter(([, label]) => !!label && label !== '…' && label !== 'Título').map(([id, label]) => [id, label.replace(/^Título /, '')])),
    });
  });

  protected readonly actions = computed(() => {
    const p = this.payment();
    return p
      ? paymentActions(p, {
          userId: this.auth.user()?.id ?? null,
          canReview: this.auth.hasPermission(Permission.PAYMENT_REVIEW),
          canReverse: this.auth.hasPermission(Permission.PAYMENT_REVERSE),
          canReallocate: this.auth.hasPermission(Permission.RECONCILIATION_MANAGE),
        })
      : null;
  });
  protected readonly timeline = computed(() => (this.payment() ? paymentTimeline(this.payment()!) : []));
  protected readonly debtorName = computed(() => {
    const p = this.payment();
    const name = p ? this.debtors.name(p.debtorId) : '';
    return name && name !== '…' ? name : null;
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.debtorName()));
  }

  protected userName(id: string | null): string {
    if (!id) {
      return 'Sistema';
    }
    return id === this.auth.user()?.id ? 'Você' : this.users.name(id);
  }

  protected lineText(line: LineView): string {
    if (line.installmentId) {
      const n = this.installmentNumbers().get(line.installmentId);
      return n ? `${n}ª parcela` : 'Parcela';
    }
    return line.titleId ? this.titles.name(line.titleId) || 'Título' : '—';
  }

  protected outcomeText(a: AllocationView): string {
    if (a.kind === 'REFUND') {
      return 'Devolvido ao pagador';
    }
    return this.outcomes[a.outcome] ?? a.outcome;
  }

  protected downloadEvidence(): void {
    const p = this.payment();
    if (p) {
      this.api.downloadEvidence(p.id).subscribe((name) => this.notifications.success(`Comprovante baixado: ${name}`));
    }
  }

  protected approve(): void {
    const p = this.payment();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Aprovar pagamento informado',
        message: 'O valor passa a abater a dívida nos destinos informados. Confira o comprovante antes de aprovar.',
        confirmLabel: 'Aprovar',
        reason: 'optional',
        reasonLabel: 'Observação (opcional)',
        details: this.impact(p),
      })
      .subscribe((note) => this.review(p, 'approve', note || null));
  }

  protected reject(): void {
    const p = this.payment();
    if (!p) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Recusar pagamento informado',
        message: 'O pagamento não baixa a dívida e fica registrado como recusado, com o seu motivo.',
        confirmLabel: 'Recusar',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo da recusa',
        details: this.impact(p),
      })
      .subscribe((note) => this.review(p, 'reject', note));
  }

  protected reverse(): void {
    const p = this.payment();
    if (!p) {
      return;
    }
    this.dialog
      .open<ReverseDialog, ReverseDialogData, PaymentView>(ReverseDialog, { data: { payment: p, debtorName: this.debtorName() ?? 'Devedor' }, width: '520px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success('Pagamento estornado.');
          this.resource.set(updated);
        }
      });
  }

  protected reallocate(): void {
    const p = this.payment();
    if (!p) {
      return;
    }
    this.dialog
      .open<ReallocateDialog, ReallocateDialogData, PaymentView>(ReallocateDialog, { data: { payment: p, debtorName: this.debtorName() ?? 'Devedor' }, width: '720px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success('Valor realocado.');
          this.resource.set(updated);
        }
      });
  }

  private review(p: PaymentView, action: 'approve' | 'reject', note: string | null): void {
    this.busy.set(true);
    this.reviewError.set(null);
    const call = action === 'approve' ? this.api.approve(p.id, note) : this.api.reject(p.id, note ?? '');
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (updated) => {
        this.notifications.success(action === 'approve' ? 'Pagamento aprovado e baixado.' : 'Pagamento recusado.');
        this.resource.set(updated);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.reviewError.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }

  private impact(p: PaymentView): { label: string; value: string }[] {
    return [
      { label: 'Devedor', value: this.debtorName() ?? 'Devedor' },
      { label: 'Valor', value: formatAmount(p.amount) },
      { label: 'Informado por', value: this.userName(p.informedBy) },
      { label: 'Destino', value: p.allocations.map(allocationTarget).join('; ') || this.requestedText() || 'Definido por quem informou (aplicado na aprovação)' },
    ];
  }
}
