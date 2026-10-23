import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DocumentDisplay } from '../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { DebtorsApi } from '../../debt/data/debt.api';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentLauncher } from '../../payment/data/payment-launcher';
import { AgreementsApi } from '../data/agreement.api';
import {
  ACCEPTANCE_CHANNEL_LABELS,
  AGREEMENT_ORIGIN_LABELS,
  LEDGER_STATUS_LABELS,
  PARTICIPANT_ROLE_LABELS,
  PAYMENT_STATUS_LABELS,
} from '../data/agreement.labels';
import { AcceptanceChannel, InstallmentView } from '../data/agreement.models';
import { actorText, agreementActions, installmentBalance, scheduleSummary, shortHash } from '../data/agreement.rules';
import { percent } from '../data/negotiation.labels';

const date = (iso: string | null | undefined) => (iso ? iso.split('-').reverse().join('/') : '—');

/**
 * Ficha do acordo: cronograma de parcelas com situação, pagamentos aplicados, participantes e
 * origem, regra de quebra fixada na formalização, termo em PDF, cancelamento com motivo e
 * renegociação (volta ao simulador com o acordo a substituir).
 */
@Component({
  selector: 'app-agreement-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, Amount, DocumentDisplay, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe],
  templateUrl: './agreement-detail.html',
  styleUrl: '../detail-page.scss',
})
export class AgreementDetail {
  readonly id = input.required<string>();

  private readonly api = inject(AgreementsApi);
  private readonly debtorsApi = inject(DebtorsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly paymentLauncher = inject(PaymentLauncher);

  protected readonly origins = AGREEMENT_ORIGIN_LABELS;
  protected readonly roles = PARTICIPANT_ROLE_LABELS;
  protected readonly paymentStatus = PAYMENT_STATUS_LABELS;
  protected readonly ledgerStatus = LEDGER_STATUS_LABELS;
  protected readonly balance = installmentBalance;
  protected readonly shortHash = shortHash;
  protected readonly percent = percent;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly agreement = valueOr(this.resource, undefined);
  private readonly paymentList = rxResource({
    params: () => this.agreement()?.id,
    stream: ({ params }) => this.api.payments(params).pipe(catchError(() => of([]))),
  });
  protected readonly payments = valueOr(this.paymentList, []);
  protected readonly busy = signal(false);

  private readonly debtor = rxResource({
    params: () => (this.auth.hasPermission(Permission.DEBTOR_VIEW) ? this.agreement()?.debtorId : undefined),
    stream: ({ params }) => this.debtorsApi.get(params).pipe(catchError(() => of(undefined))),
  });
  protected readonly debtorView = valueOr(this.debtor, undefined);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.agreement()?.creditorId]);
  private readonly users = nameMap(inject(UserNames), () => (this.agreement()?.participants ?? []).filter((p) => p.actorType === 'USER').map((p) => p.actorId), { batch: USER_NAMES_BATCH });

  protected readonly summary = computed(() => (this.agreement() ? scheduleSummary(this.agreement()!) : null));
  protected readonly actions = computed(() =>
    this.agreement()
      ? agreementActions(this.agreement()!, {
          canCancel: this.auth.hasPermission(Permission.AGREEMENT_CANCEL),
          canRenegotiate: this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE) && this.auth.hasPermission(Permission.NEGOTIATION_PROPOSE),
        })
      : null,
  );
  protected readonly canViewProposal = computed(() =>
    this.auth.hasAnyPermission([Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE]),
  );
  protected readonly debtorName = computed(() => this.debtorView()?.name ?? null);
  /** Acordo em vigor recebe instruções de pagamento e pagamentos informados. */
  protected readonly active = computed(() => ['FORMALIZED', 'IN_PAYMENT'].includes(this.agreement()?.status ?? ''));
  protected readonly canPix = computed(() => this.active() && this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE));
  protected readonly canRegisterPayment = computed(() => this.auth.hasPermission(Permission.PAYMENT_REGISTER_EXTERNAL));

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.debtorName()));
  }

  protected participantName(actorType: string, actorId: string | null): string {
    return actorText(actorType, actorId, this.auth.user()?.id ?? null, (id) => this.users.name(id));
  }

  protected channelText(code: string | null): string {
    return code ? (ACCEPTANCE_CHANNEL_LABELS[code as AcceptanceChannel] ?? code) : 'Não informado';
  }

  protected cancel(): void {
    const a = this.agreement();
    if (!a) {
      return;
    }
    const d = this.debtorView();
    const s = this.summary()!;
    this.confirm
      .confirm({
        title: 'Cancelar acordo',
        message: 'As parcelas em aberto são canceladas e os títulos voltam à cobrança. Pagamentos já recebidos continuam abatidos dos títulos.',
        confirmLabel: 'Cancelar acordo',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo do cancelamento',
        details: [
          { label: 'Devedor', value: d ? `${d.name} · ${d.maskedDocument}` : 'Devedor do acordo' },
          { label: 'Total do acordo', value: formatAmount(a.totalAmount) },
          { label: 'Já pago', value: formatAmount(a.paidAmount) },
          { label: 'Parcelas em aberto', value: `${s.total - s.paid} de ${s.total}` },
          { label: 'Próximo vencimento', value: s.next ? date(s.next.dueDate) : '—' },
        ],
        warning: 'O cancelamento não pode ser desfeito. Para mudar as condições, prefira renegociar.',
        confirmationText: 'CANCELAR',
      })
      .subscribe((reason) => {
        this.busy.set(true);
        this.api
          .cancel(a.id, reason)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe(() => {
            this.notifications.success('Acordo cancelado.');
            this.resource.reload();
          });
      });
  }

  protected renegotiate(): void {
    const a = this.agreement();
    if (a) {
      void this.router.navigate(['/negociacao/simulador'], {
        queryParams: { credor: a.creditorId, devedor: a.debtorId, caso: a.caseId ?? undefined, renegociar: a.id },
      });
    }
  }

  protected pixable(i: InstallmentView): boolean {
    return i.status !== 'PAID' && i.status !== 'CANCELLED' && installmentBalance(i) > 0;
  }

  /** Pix da parcela: QR Code e copia e cola no próprio diálogo (carregado sob demanda). */
  protected generatePix(i: InstallmentView): void {
    const a = this.agreement();
    if (!a) {
      return;
    }
    this.paymentLauncher
      .instruction({
        target: { kind: 'INSTALLMENT', creditorId: a.creditorId, agreementId: a.id, installmentId: i.id, installmentNumber: i.number, dueDate: i.dueDate, balance: installmentBalance(i) },
        debtorId: a.debtorId,
        debtorName: this.debtorName(),
        channel: a.channel,
      })
      .subscribe((created) => {
        if (created?.status === 'PAID') {
          this.resource.reload();
        }
      });
  }

  protected registerPayment(): void {
    const a = this.agreement();
    if (a) {
      this.paymentLauncher.external({ debtorId: a.debtorId, debtorName: this.debtorName(), initialKind: 'AGREEMENT' }).subscribe((p) => {
        if (p) {
          this.resource.reload();
          this.paymentList.reload();
        }
      });
    }
  }

  protected downloadTerm(): void {
    const a = this.agreement();
    if (a) {
      this.api.downloadTerm(a.id).subscribe((name) => this.notifications.success(`Termo baixado: ${name}`));
    }
  }
}
