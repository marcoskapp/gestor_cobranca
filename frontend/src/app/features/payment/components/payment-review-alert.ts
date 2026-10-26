import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { UNRESOLVED_NAME, nameMap } from '../../../shared/data/name-map';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import {
  BreakSuspensionInfo,
  CoverageView,
  REJECTED_WINDOW_DAYS,
  ReviewItem,
  formatLocalDate,
  pendingBreakState,
  rejectionGraceUntil,
} from '../data/review-status';

/**
 * Aviso de pagamentos externos informados que aguardam a aprovação de outra pessoa (decisão 75) e dos
 * recusados recentemente, para quem negocia saber que a parcela ainda não baixou ou que precisa agir.
 * Enquanto há pagamento em revisão, a quebra do acordo fica suspensa até a revisão, por no máximo
 * `maxDays` dias (decisões 494–503); depois da recusa, volta a valer passada a carência.
 * Usado no detalhe do acordo (completo, com as parcelas cobertas) e na ficha do caso (`compact`,
 * com o atalho para o acordo). Link para o pagamento só com `PAYMENT_VIEW`.
 */
@Component({
  selector: 'app-payment-review-alert',
  imports: [RouterLink, Amount, Icon, AppDatePipe],
  template: `
    @let open = pending();
    @if (open.length) {
      <div class="alert alert-warning pra" [class.pra--compact]="compact()" role="status" data-tour="payment-review-pending">
        <app-icon class="pra__icon" name="hourglass-split" />
        <div class="pra__body">
          @if (open.length > 1) {
            <p class="pra__title">{{ open.length }} pagamentos informados aguardando aprovação de outra pessoa · total <app-amount [value]="total(open)" /></p>
          }
          <ul class="pra__list" [class.pra__list--plain]="open.length === 1">
            @for (p of open; track p.paymentId) {
              <li>
                <span>
                  Pagamento de <strong><app-amount [value]="p.amount" /></strong> informado em {{ p.informedAt | appDate }} por {{ person(p.informedBy) }}@if (open.length === 1) { aguardando aprovação de outra pessoa}.
                </span>
                @if (!compact() && coverageText(p); as target) {
                  <span class="pra__target">Destino previsto: {{ target }}.</span>
                }
                <span class="pra__links">
                  @if (canViewPayment()) {
                    <a [routerLink]="['/pagamentos', p.paymentId]" [attr.aria-label]="'Ver pagamento de ' + money(p.amount) + ' aguardando aprovação'">Ver pagamento</a>
                  }
                  @if (compact() && p.agreementId) {
                    <a [routerLink]="['/negociacao/acordos', p.agreementId]">Abrir acordo</a>
                  }
                </span>
              </li>
            }
          </ul>
          <p class="pra__hint" data-testid="pending-break-hint">{{ pendingHint() }}</p>
        </div>
      </div>
    }
    @let refused = rejected();
    @if (refused.length) {
      <div class="alert alert-danger pra" [class.pra--compact]="compact()" role="status" data-tour="payment-review-rejected">
        <app-icon class="pra__icon" name="x-circle" />
        <div class="pra__body">
          @if (refused.length > 1) {
            <p class="pra__title">{{ refused.length }} pagamentos recusados nos últimos {{ windowDays }} dias</p>
          }
          <ul class="pra__list" [class.pra__list--plain]="refused.length === 1">
            @for (p of refused; track p.paymentId) {
              <li>
                <span>
                  Pagamento de <strong><app-amount [value]="p.amount" /></strong> informado por {{ person(p.informedBy) }} foi recusado em {{ p.reviewedAt | appDate }} por {{ person(p.reviewedBy) }}.
                </span>
                @if (p.rejectionReason) {
                  <span class="pra__reason">Motivo: {{ p.rejectionReason }}</span>
                } @else if (p.reasonRestricted) {
                  <span class="pra__reason app-muted">O motivo fica visível para quem informou e para quem consulta pagamentos.</span>
                }
                <span class="pra__links">
                  @if (canViewPayment()) {
                    <a [routerLink]="['/pagamentos', p.paymentId]" [attr.aria-label]="'Ver pagamento recusado de ' + money(p.amount)">Ver pagamento</a>
                  }
                  @if (compact() && p.agreementId) {
                    <a [routerLink]="['/negociacao/acordos', p.agreementId]">Abrir acordo</a>
                  }
                </span>
              </li>
            }
          </ul>
          <p class="pra__hint" data-testid="rejected-break-hint">{{ rejectedHint() }}</p>
        </div>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .pra {
      display: flex;
      gap: 0.5rem;
      align-items: flex-start;
    }
    .pra__icon {
      flex: none;
      margin-top: 0.15rem;
    }
    .pra__body {
      min-width: 0;
      display: grid;
      gap: 0.25rem;
    }
    .pra__title,
    .pra__hint {
      margin: 0;
    }
    .pra__title {
      font-weight: 600;
    }
    .pra__list {
      margin: 0;
      padding-left: 1.1rem;
      display: grid;
      gap: 0.25rem;
    }
    .pra__list--plain {
      padding-left: 0;
      list-style: none;
    }
    .pra__list li > span + span {
      margin-left: 0.25rem;
    }
    .pra__links {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 0.75rem;
    }
    .pra__reason {
      overflow-wrap: anywhere;
    }
    .pra--compact {
      padding: 0.5rem 0.75rem;
      margin-bottom: 0.5rem;
      font-size: var(--app-text-sm);
    }
  `,
})
export class PaymentReviewAlert {
  readonly pending = input<readonly ReviewItem[]>([]);
  readonly rejected = input<readonly ReviewItem[]>([]);
  /** Previsão das parcelas cobertas (detalhe do acordo). */
  readonly coverage = input<readonly CoverageView[]>([]);
  /** Rótulo da parcela por id ("2ª parcela", "Entrada"). */
  readonly installmentLabels = input<ReadonlyMap<string, string>>(new Map());
  /** Ficha do caso: texto curto e atalho para o acordo. */
  readonly compact = input(false);
  /** Suspensão da quebra (prazo máximo, carência e a suspensão mais recente dos acordos envolvidos). */
  readonly breakSuspension = input<BreakSuspensionInfo | null>(null);

  protected readonly pendingHint = computed(() => {
    const state = pendingBreakState(this.breakSuspension(), this.pending().map((p) => p.agreementId));
    if (this.compact()) {
      const lead = 'Só baixa depois da aprovação.';
      switch (state.kind) {
        case 'suspended':
          return `${lead} Quebra suspensa até a revisão (no máximo até ${formatLocalDate(state.until)}).`;
        case 'deadline':
          return `${lead} O prazo de suspensão da quebra terminou em ${formatLocalDate(state.until)}.`;
        case 'potential':
          return `${lead} Se a regra de quebra for atingida, a quebra fica suspensa até a revisão (no máximo ${days(state.maxDays)}).`;
        default:
          return lead;
      }
    }
    const lead = 'A parcela só é baixada depois da aprovação.';
    switch (state.kind) {
      case 'suspended':
        return `${lead} Enquanto isso, a quebra do acordo fica suspensa até a revisão, no máximo até ${formatLocalDate(state.until)}.`;
      case 'deadline':
        return `${lead} O prazo máximo de suspensão da quebra terminou em ${formatLocalDate(state.until)} e a regra de quebra voltou a valer.`;
      case 'potential':
        return `${lead} Se a regra de quebra for atingida antes disso, a quebra fica suspensa até a revisão, por no máximo ${days(state.maxDays)}.`;
      default:
        return lead;
    }
  });

  protected readonly rejectedHint = computed(() => {
    const grace = rejectionGraceUntil(this.breakSuspension(), this.rejected().map((p) => p.agreementId));
    if (!grace) {
      return this.compact() ? 'Nada foi baixado.' : 'Nada foi baixado. Confirme com o devedor e, se ele pagou, informe de novo com um comprovante válido.';
    }
    const lead = `Nada foi baixado. A quebra, suspensa durante a revisão, volta a valer depois de ${formatLocalDate(grace)}.`;
    return this.compact()
      ? lead
      : `${lead} Confirme com o devedor: um novo pagamento informado não suspende a quebra outra vez pela mesma parcela, então a baixa precisa chegar até lá (um Pix baixa na hora).`;
  });

  private readonly auth = inject(AuthService);
  protected readonly windowDays = REJECTED_WINDOW_DAYS;
  protected readonly canViewPayment = computed(() => this.auth.hasPermission(Permission.PAYMENT_VIEW));
  private readonly me = computed(() => this.auth.user()?.id ?? null);
  private readonly names = nameMap(
    inject(UserNames),
    () => [...this.pending(), ...this.rejected()].flatMap((p) => [p.informedBy, p.reviewedBy]).filter((id) => id && id !== this.me()),
    { batch: USER_NAMES_BATCH },
  );

  protected person(id: string | null): string {
    if (!id) {
      return 'um usuário';
    }
    if (id === this.me()) {
      return 'você';
    }
    const name = this.names.name(id);
    return name === UNRESOLVED_NAME ? 'um usuário' : name;
  }

  protected total(items: readonly ReviewItem[]): number {
    return items.reduce((sum, p) => sum + Number(p.amount), 0);
  }

  protected money(value: number): string {
    return formatAmount(value);
  }

  /** Parcelas que o pendente cobrirá se aprovado (previsão), ou o destino indicado. */
  protected coverageText(p: ReviewItem): string | null {
    if (p.targetKind === 'TITLES') {
      return p.requestedItemIds.length === 1 ? 'título avulso' : `${p.requestedItemIds.length} títulos avulsos`;
    }
    const labels = this.installmentLabels();
    const covered = [...new Set(this.coverage().filter((c) => c.paymentId === p.paymentId).map((c) => c.installmentId))];
    const ids = covered.length ? covered : p.requestedItemIds;
    const named = ids.map((id) => labels.get(id)).filter((l): l is string => !!l);
    if (!named.length) {
      return p.requestedItemIds.length ? null : 'parcelas em aberto, a partir da mais antiga';
    }
    return named.length === 1 ? named[0] : `${named.slice(0, -1).join(', ')} e ${named[named.length - 1]}`;
  }
}

function days(n: number): string {
  return n === 1 ? '1 dia' : `${n} dias`;
}
