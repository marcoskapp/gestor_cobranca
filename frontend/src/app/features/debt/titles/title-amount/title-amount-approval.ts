import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { recordLabel } from '../../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ApprovalDecision, ApprovalParty, ApprovalReason } from '../../../../shared/approval/approval-decision';
import { approvalQueue } from '../../../../shared/approval/approval-queue';
import { Amount, formatAmount } from '../../../../shared/components/amount/amount';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../../shared/components/icon/icon';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../../organization/data/organization.lookups';
import { TitleAmountApi } from '../../data/debt.api';
import { AMOUNT_REASON_LABELS, LEDGER_COMPONENT_LABELS } from '../../data/debt.labels';
import { AmountAdjustmentApproval } from '../../data/debt.models';

/** "+R$ 100,00 (50% acima)". */
function excessText(excess: number, limit: number): string | null {
  if (excess <= 0) {
    return null;
  }
  const share = limit > 0 ? ` (${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 }).format((excess / limit) * 100)}% acima)` : '';
  return `+${formatAmount(excess)}${share}`;
}

/**
 * Por que a correção precisa de segunda pessoa, com quanto passa do limite: aumento de componente (sempre), redução
 * acima do limite sozinha ou somada às reduções já aplicadas sem segunda pessoa no título. Função pura.
 */
export function adjustmentApprovalReasons(a: AmountAdjustmentApproval): ApprovalReason[] {
  const reasons: ApprovalReason[] = [];
  if (a.increases > 0) {
    reasons.push({ label: 'Aumento de componente', hint: 'qualquer aumento passa por outra pessoa', requested: `+${formatAmount(a.increases)}`, limit: 'nenhum aumento', excess: null });
  }
  if (a.decreases > 0 && (a.reason !== 'INCREASE' || a.excess > 0)) {
    const cumulative = a.recentReductions > 0;
    reasons.push({
      label: cumulative ? 'Reduções no título' : 'Redução',
      hint: cumulative ? `esta (${formatAmount(a.decreases)}) e as aplicadas sem segunda pessoa nos últimos ${a.cumulativeWindowDays} dias (${formatAmount(a.recentReductions)})` : 'soma das reduções de componente',
      requested: formatAmount(a.decreases + a.recentReductions),
      limit: formatAmount(a.threshold),
      excess: excessText(a.excess, a.threshold),
    });
  }
  return reasons;
}

/**
 * Aprovação de uma correção de valor (`/titulos/correcoes-de-valor/:id`), no padrão único das filas de aprovação
 * (ux.md §17): quanto passa do limite, quem pediu × quem aprova, componentes antes → depois, evidência, "Aprovar e
 * abrir a próxima" e volta à fila com o filtro. O servidor revalida a segregação (quem pede não decide) e o saldo.
 */
@Component({
  selector: 'app-title-amount-approval',
  imports: [RouterLink, ApprovalDecision, Amount, AppDatePipe, EmptyState, Icon, PageHeader, Skeleton],
  template: `
    @if (resource.isLoading() && !approval()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a correção." message="Ela pode estar fora do seu escopo de credores ou ter sido aplicada dentro do limite.">
        <button class="btn btn-outline-primary" type="button" (click)="queue.backToQueue()">Voltar para a fila</button>
      </app-empty-state>
    } @else if (approval(); as v) {
      @let a = v.adjustment;
      <app-page-header [title]="'Aprovação · Título ' + (a.titleLabel ?? '')" [subtitle]="'Pedida em ' + (a.requestedAt | appDate) + ' · ' + creditors.name(a.creditorId)">
        @if (a.evidence) {
          <button class="btn btn-outline-secondary" type="button" (click)="download()">
            <app-icon name="paperclip" />
            Baixar evidência
          </button>
        }
        <a class="btn btn-outline-secondary" [routerLink]="['/titulos', a.titleId]">Ficha do título</a>
      </app-page-header>

      @if (a.status !== 'PENDING_APPROVAL') {
        <div class="alert alert-secondary d-flex gap-2 align-items-start" role="note">
          <app-icon name="check2-circle" />
          <span>Este pedido já foi decidido ou retirado. Volte para a fila para ver os que faltam.</span>
        </div>
      } @else {
        <app-approval-decision
          class="ta-decision"
          [queueLink]="queue.path"
          [queueParams]="queue.filters()"
          queueLabel="Voltar para Correções de valor"
          itemLabel="Correção"
          [position]="queue.position()"
          [hasNext]="queue.hasNext()"
          [reasons]="reasons()"
          [requester]="requester()"
          [approver]="approver()"
          [canDecide]="canApprove()"
          [blockedReason]="v.ownRequest ? 'Você pediu esta correção: a decisão é de outra pessoa (segregação de funções, inclusive para administradores).' : null"
          [busy]="busy()"
          rejectLabel="Recusar"
          (approve)="approve(v)"
          (reject)="reject(v)"
        />
      }

      <section class="app-card" aria-labelledby="ta-change">
        <h2 class="app-card__title" id="ta-change">O que muda no título</h2>
        <div class="table-responsive">
          <table class="table table-sm align-middle mb-0" aria-labelledby="ta-change">
            <thead>
              <tr>
                <th scope="col">Componente</th>
                <th scope="col" class="text-end">Antes</th>
                <th scope="col" class="text-end">Depois</th>
                <th scope="col" class="text-end">Diferença</th>
              </tr>
            </thead>
            <tbody>
              @for (l of a.lines; track l.component) {
                <tr>
                  <th scope="row" class="fw-normal">{{ components[l.component] }}</th>
                  <td class="text-end"><app-amount [value]="l.previousBalance" /></td>
                  <td class="text-end"><app-amount [value]="l.newBalance" /></td>
                  <td class="text-end"><app-amount [value]="l.delta" /></td>
                </tr>
              }
              <tr>
                <th scope="row">Saldo em aberto</th>
                <td class="text-end"><app-amount [value]="a.previousOpenBalance" /></td>
                <td class="text-end"><app-amount [value]="a.newOpenBalance" /></td>
                <td class="text-end"><strong><app-amount [value]="a.delta" /></strong></td>
              </tr>
            </tbody>
          </table>
        </div>
        <dl class="app-definition-list mt-3">
          <dt>Motivo</dt>
          <dd>{{ reasonLabels[a.reason] }}</dd>
          <dt>Descrição de quem pediu</dt>
          <dd>{{ a.note }}</dd>
          <dt>Evidência</dt>
          <dd>{{ a.evidence ? a.evidence.fileName : a.disputeId ? 'Contestação vinculada' : '—' }}</dd>
        </dl>
      </section>
    }
  `,
  styles: `
    .ta-decision {
      display: block;
      margin-bottom: 16px;
    }
  `,
})
export class TitleAmountApproval {
  readonly id = input.required<string>();

  private readonly api = inject(TitleAmountApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly components = LEDGER_COMPONENT_LABELS;
  protected readonly reasonLabels = AMOUNT_REASON_LABELS;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.approval(params) });
  protected readonly approval = valueOr<AmountAdjustmentApproval | undefined>(this.resource, undefined);
  protected readonly busy = signal(false);
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_APPROVE));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.approval()?.adjustment.creditorId]);
  protected readonly crumb = recordLabel(() => {
    const label = this.approval()?.adjustment.titleLabel;
    return label ? `Título ${label}` : null;
  });

  /** Fila filtrada de onde o pedido foi aberto (`?credor=` vem da lista). */
  protected readonly queue = approvalQueue({
    path: '/titulos/correcoes-de-valor',
    filterParams: ['credor'],
    load: (f, size) => this.api.pending({ page: 0, size }, { creditorId: f['credor'] ?? null }),
    currentId: () => this.id(),
  });

  protected readonly reasons = computed(() => {
    const v = this.approval();
    return v ? adjustmentApprovalReasons(v) : [];
  });
  protected readonly requester = computed<ApprovalParty>(() => ({ name: this.approval()?.adjustment.requestedByName ?? 'Usuário', role: 'pediu a correção' }));
  protected readonly approver = computed<ApprovalParty | null>(() => (this.canApprove() ? { name: 'Você', role: 'aprovação por segunda pessoa' } : null));

  private details(v: AmountAdjustmentApproval): { label: string; value: string }[] {
    const a = v.adjustment;
    return [
      { label: 'Título', value: a.titleLabel ?? '—' },
      { label: 'Motivo', value: AMOUNT_REASON_LABELS[a.reason] },
      { label: 'Pedida por', value: a.requestedByName ?? '—' },
      { label: 'Saldo', value: `${formatAmount(a.previousOpenBalance)} → ${formatAmount(a.newOpenBalance)}` },
    ];
  }

  protected approve(v: AmountAdjustmentApproval): void {
    this.confirm
      .confirm({
        title: 'Aprovar correção de valor',
        message: 'O razão do título recebe o ajuste e o saldo muda. A aprovação fica registrada com o seu nome.',
        confirmLabel: 'Aprovar',
        reason: 'optional',
        reasonLabel: 'Observação (opcional)',
        details: this.details(v),
      })
      .subscribe((note) => this.decide(this.api.approve(v.adjustment.id, note || null), 'Correção aprovada e aplicada.'));
  }

  protected reject(v: AmountAdjustmentApproval): void {
    this.confirm
      .confirm({
        title: 'Recusar correção de valor',
        message: 'O título não muda. Quem pediu verá o motivo.',
        confirmLabel: 'Recusar',
        destructive: true,
        reason: 'required',
        details: this.details(v),
      })
      .subscribe((note) => this.decide(this.api.reject(v.adjustment.id, note), 'Correção recusada.'));
  }

  protected download(): void {
    const a = this.approval()?.adjustment;
    if (a) {
      this.api.downloadEvidence(a).subscribe();
    }
  }

  private decide(call: ReturnType<TitleAmountApi['approve']>, message: string): void {
    // ocupado até a próxima abrir (ou a fila voltar)
    this.busy.set(true);
    call.subscribe({
      next: () => {
        this.notifications.success(message);
        void this.queue.advance().finally(() => this.busy.set(false));
      },
      // a mensagem do servidor já aparece pelo tratamento global de erros; a tela recarrega a situação atual
      error: () => {
        this.busy.set(false);
        this.resource.reload();
      },
    });
  }
}
