import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { recordLabel } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ApprovalDecision, ApprovalParty, ApprovalReason } from '../../../shared/approval/approval-decision';
import { approvalQueue } from '../../../shared/approval/approval-queue';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { RequestedTarget } from '../components/requested-target';
import { PaymentsApi } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { PaymentView, ReviewAssessmentView } from '../data/payment.models';
import { breakHoldBadge } from '../data/review-status';

/** Filtros da fila que seguem na URL da decisão (os mesmos da lista). */
export const PAYMENT_QUEUE_FILTERS = ['credor', 'origem'] as const;

/** "+R$ 400,00 (80% acima)". */
function excessText(excess: number | null, limit: number | null): string | null {
  if (excess === null || excess <= 0) {
    return null;
  }
  const share = limit && limit > 0 ? ` (${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 }).format((excess / limit) * 100)}% acima)` : '';
  return `+${formatAmount(excess)}${share}`;
}

/**
 * Por que o pagamento informado precisa de segunda pessoa, com quanto passa do limite (pagamento sozinho, soma do
 * devedor no credor ou soma de quem informou, na janela). Pagamento anterior à gravação do motivo: compara o valor com
 * o limite atual por pagamento. Função pura.
 */
export function paymentReviewReasons(p: Pick<PaymentView, 'amount'>, a: ReviewAssessmentView): ApprovalReason[] {
  const window = `informados nas últimas ${a.windowHours} h, com este`;
  switch (a.reason) {
    case 'AMOUNT':
      return [{ label: 'Valor do pagamento', requested: formatAmount(a.consideredAmount ?? p.amount), limit: formatAmount(a.limit ?? 0), excess: excessText(a.excess, a.limit) }];
    case 'DESTINATION_CUMULATIVE':
      return [{ label: 'Soma do devedor neste credor', hint: `pagamentos ${window}`, requested: formatAmount(a.consideredAmount ?? 0), limit: formatAmount(a.limit ?? 0), excess: excessText(a.excess, a.limit) }];
    case 'INFORMER_CUMULATIVE':
      return [{ label: 'Soma de quem informou', hint: `pagamentos ${window}`, requested: formatAmount(a.consideredAmount ?? 0), limit: formatAmount(a.limit ?? 0), excess: excessText(a.excess, a.limit) }];
    default:
      return [
        {
          label: 'Valor do pagamento',
          hint: 'comparado com o limite atual por pagamento (registro anterior ao motivo gravado)',
          requested: formatAmount(p.amount),
          limit: formatAmount(a.currentPerPaymentLimit),
          excess: excessText(p.amount - a.currentPerPaymentLimit, a.currentPerPaymentLimit),
        },
      ];
  }
}

/**
 * Aprovação de um pagamento informado (`/pagamentos/aprovacoes/:id`), no padrão único das filas de aprovação
 * (ux.md §18): quanto passa do limite, quem informou × quem aprova, comprovante e destino solicitado, "Aprovar e abrir
 * a próxima" e volta à fila com os filtros. A ficha completa do pagamento continua em `/pagamentos/:id`. O servidor
 * revalida a segregação (quem informa não aprova) e a situação.
 */
@Component({
  selector: 'app-payment-approval',
  imports: [RouterLink, ApprovalDecision, Amount, AppDatePipe, EmptyState, Icon, KpiCard, PageHeader, RequestedTarget, Skeleton],
  template: `
    @if (resource.isLoading() && !payment()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o pagamento." message="Ele pode já ter sido revisado ou estar fora do seu escopo de credores.">
        <button class="btn btn-outline-primary" type="button" (click)="queue.backToQueue()">Voltar para a fila</button>
      </app-empty-state>
    } @else if (payment(); as p) {
      <app-page-header [title]="'Aprovação · ' + debtors.name(p.debtorId)" [subtitle]="'Pagamento informado em ' + (p.informedAt | appDate) + ' · ' + creditors.name(p.creditorId)">
        @if (p.evidenceAvailable) {
          <button class="btn btn-outline-secondary" type="button" (click)="download(p)" [disabled]="downloading()" data-tour="payment-evidence">
            <app-icon name="paperclip" />
            Baixar comprovante
          </button>
        }
        <a class="btn btn-outline-secondary" [routerLink]="['/pagamentos', p.id]">Ficha do pagamento</a>
      </app-page-header>

      @if (error()) {
        <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      @if (p.status !== 'INFORMED') {
        <div class="alert alert-secondary d-flex gap-2 align-items-start" role="note">
          <app-icon name="check2-circle" />
          <span>Este pagamento já foi revisado. Volte para a fila para ver os que faltam.</span>
        </div>
      } @else {
        <app-approval-decision
          class="pa-decision"
          [queueLink]="queue.path"
          [queueParams]="queue.filters()"
          queueLabel="Voltar para Aprovação de pagamentos"
          itemLabel="Pagamento"
          [position]="queue.position()"
          [hasNext]="queue.hasNext()"
          [reasons]="reasons()"
          [requester]="requester()"
          [approver]="approver()"
          [canDecide]="canReview()"
          [blockedReason]="blockedReason()"
          [busy]="busy()"
          rejectLabel="Recusar"
          (approve)="approve(p)"
          (reject)="reject(p)"
        />
      }

      @if (p.status === 'INFORMED' && holdBadge(p); as badge) {
        <div class="alert d-flex gap-2 align-items-start" [class.alert-warning]="badge.tone === 'warning'" [class.alert-danger]="badge.tone === 'danger'" role="note">
          <app-icon [name]="badge.tone === 'danger' ? 'exclamation-circle' : 'hourglass-split'" />
          <span>{{ badge.text }}. {{ badge.tone === 'warning' ? 'Enquanto este pagamento aguarda a revisão, o acordo não quebra.' : 'Revise com prioridade: o acordo pode quebrar sem esperar esta revisão.' }}</span>
        </div>
      }

      <section class="app-card pa-card" aria-labelledby="pa-data">
        <h2 class="app-card__title" id="pa-data">O que foi informado</h2>
        <div class="app-kpi-grid">
          <app-kpi-card label="Valor pago" info="payment.detail.amount"><app-amount [value]="p.amount" /></app-kpi-card>
          <app-kpi-card label="Pago em" record variant="text">{{ p.paidAt | appDate }}</app-kpi-card>
          <app-kpi-card label="Meio" record variant="text">{{ methods[p.method] }}</app-kpi-card>
          <app-kpi-card label="Onde entrou" record variant="text">{{ p.externalOrigin ? origins[p.externalOrigin] : '—' }}</app-kpi-card>
        </div>
        <dl class="app-definition-list">
          <dt>Devedor</dt>
          <dd>
            @if (canViewDebtor()) {
              <a [routerLink]="['/devedores', p.debtorId]">{{ debtors.name(p.debtorId) }}</a>
            } @else {
              {{ debtors.name(p.debtorId) }}
            }
          </dd>
          <dt>Credor</dt>
          <dd>{{ creditors.name(p.creditorId) }}</dd>
          <dt>Referência</dt>
          <dd>{{ p.externalReference ?? '—' }}</dd>
          <dt>Observação de quem informou</dt>
          <dd>{{ p.note ?? '—' }}</dd>
          <dt>Destino solicitado</dt>
          <dd>
            @if (p.requestedTarget; as t) {
              <app-requested-target [target]="t" />
            } @else {
              <span class="app-muted">Definido por quem informou (aplicado na aprovação)</span>
            }
          </dd>
        </dl>
      </section>
    }
  `,
  styles: `
    .pa-decision {
      display: block;
      margin-bottom: 16px;
    }
    .pa-card {
      margin-top: 16px;
    }
  `,
})
export class PaymentApproval {
  readonly id = input.required<string>();

  private readonly api = inject(PaymentsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly methods = METHOD_LABELS;
  protected readonly origins = ORIGIN_LABELS;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly payment = valueOr<PaymentView | undefined>(this.resource, undefined);
  private readonly assessmentResource = rxResource({
    params: () => (this.payment()?.status === 'INFORMED' ? this.id() : undefined),
    stream: ({ params }) => this.api.reviewAssessment(params).pipe(catchError(() => of(null))),
  });
  protected readonly assessment = valueOr(this.assessmentResource, null);
  protected readonly busy = signal(false);
  protected readonly downloading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canReview = computed(() => this.auth.hasPermission(Permission.PAYMENT_REVIEW));
  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.payment()?.creditorId]);
  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.payment()?.debtorId], { batch: 100 });
  private readonly users = nameMap(inject(UserNames), () => [this.payment()?.informedBy], { batch: USER_NAMES_BATCH });
  protected readonly crumb = recordLabel(() => {
    const p = this.payment();
    return p ? this.debtors.name(p.debtorId) : null;
  });

  /** Fila filtrada de onde o pagamento foi aberto (`?credor=&origem=` vêm da lista). */
  protected readonly queue = approvalQueue({
    path: '/pagamentos/aprovacoes',
    filterParams: PAYMENT_QUEUE_FILTERS,
    load: (f, size) => this.api.reviewQueue({ page: 0, size }, { creditorId: f['credor'] ?? null, origin: f['origem'] ?? null }),
    currentId: () => this.id(),
  });

  protected readonly reasons = computed(() => {
    const p = this.payment();
    const a = this.assessment();
    return p && a ? paymentReviewReasons(p, a) : [];
  });
  protected readonly requester = computed<ApprovalParty>(() => {
    const p = this.payment();
    return { name: p?.informedBy ? (p.informedBy === this.auth.user()?.id ? 'Você' : this.users.name(p.informedBy)) : '—', role: 'informou o pagamento' };
  });
  protected readonly approver = computed<ApprovalParty | null>(() => (this.canReview() ? { name: 'Você', role: 'revisão por segunda pessoa' } : null));
  protected readonly blockedReason = computed(() =>
    this.assessment()?.ownPayment ? 'Você informou este pagamento: a aprovação é de outra pessoa (segregação de funções, inclusive para administradores).' : null,
  );

  protected holdBadge(p: PaymentView) {
    return breakHoldBadge(p.breakSuspension);
  }

  private impact(p: PaymentView): { label: string; value: string }[] {
    return [
      { label: 'Devedor', value: this.debtors.name(p.debtorId) },
      { label: 'Valor', value: formatAmount(p.amount) },
      { label: 'Informado por', value: this.requester().name },
    ];
  }

  protected approve(p: PaymentView): void {
    this.confirm
      .confirm({
        title: 'Aprovar pagamento informado',
        message: 'O valor passa a abater a dívida nos destinos informados. Confira o comprovante antes de aprovar.',
        confirmLabel: 'Aprovar',
        reason: 'optional',
        reasonLabel: 'Observação (opcional)',
        details: this.impact(p),
      })
      .subscribe((note) => this.decide(p, 'approve', note || null));
  }

  protected reject(p: PaymentView): void {
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
      .subscribe((note) => this.decide(p, 'reject', note));
  }

  protected download(p: PaymentView): void {
    this.downloading.set(true);
    this.api
      .downloadEvidence(p.id)
      .pipe(finalize(() => this.downloading.set(false)))
      .subscribe((name) => this.notifications.success(`Comprovante baixado: ${name}`));
  }

  private decide(p: PaymentView, action: 'approve' | 'reject', note: string | null): void {
    this.busy.set(true);
    this.error.set(null);
    const call = action === 'approve' ? this.api.approve(p.id, note) : this.api.reject(p.id, note ?? '');
    // ocupado até a próxima abrir (ou a fila voltar)
    call.subscribe({
      next: () => {
        this.notifications.success(action === 'approve' ? 'Pagamento aprovado e baixado.' : 'Pagamento recusado.');
        void this.queue.advance().finally(() => this.busy.set(false));
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const e = toApiError(err);
        this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        this.resource.reload();
      },
    });
  }
}
