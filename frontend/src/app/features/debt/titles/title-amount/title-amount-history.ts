import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../../shared/components/amount/amount';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { TitleAmountApi } from '../../data/debt.api';
import { AMOUNT_MODE_LABELS, AMOUNT_REASON_LABELS, LEDGER_COMPONENT_LABELS } from '../../data/debt.labels';
import { AmountAdjustmentView, AmountDivergenceView } from '../../data/debt.models';
import { canDecide, canWithdraw } from './title-amount.rules';

/**
 * Histórico de correções de valor no próprio título (append-only): valor anterior e novo, componentes, motivo, quem
 * pediu, quem decidiu e quando; decisão do pedido pendente (aprovador que não pediu) e das divergências com a carga.
 */
@Component({
  selector: 'app-title-amount-history',
  imports: [Amount, Icon, StatusChip, AppDatePipe],
  template: `
    @if (history.error()) {
      <p class="app-muted">Não foi possível carregar as correções de valor.</p>
    } @else if (!data()) {
      <div class="spinner-border spinner-border-sm" role="status" aria-label="Carregando correções"></div>
    } @else {
      @for (d of data()!.divergences; track d.id) {
        @if (d.status === 'OPEN') {
          <div class="alert alert-warning" role="note" data-tour="title-amount-divergence">
            <div class="d-flex gap-2">
              <app-icon name="exclamation-triangle" />
              <span>
                A carga do credor informou principal de <app-amount [value]="d.reportedPrincipal" />, diferente do corrigido (<app-amount [value]="d.currentPrincipal" />). A correção foi mantida;
                decida qual valor vale ({{ d.occurrences }} carga(s) desde {{ d.detectedAt | appDate: 'date' }}).
              </span>
            </div>
            @if (canAdjust()) {
              <div class="d-flex flex-wrap gap-2 mt-2">
                <button class="btn btn-sm btn-outline-primary" type="button" (click)="resolve(d, 'KEEP_MANUAL')" [disabled]="busy()">Manter a correção</button>
                <button class="btn btn-sm btn-outline-secondary" type="button" (click)="resolve(d, 'ACCEPT_CREDITOR')" [disabled]="busy()">Aceitar o valor do credor</button>
              </div>
            }
          </div>
        }
      }
      <ol class="ta-list">
        @for (a of data()!.adjustments; track a.id) {
          <li class="ta-item">
            <header class="ta-item__head">
              <app-status-chip domain="titleAmountAdjustment" [status]="a.status" />
              <strong>{{ reasons[a.reason] }}</strong>
              <span class="app-muted">{{ modes[a.approvalMode] }}</span>
            </header>
            <div>
              Saldo <app-amount [value]="a.previousOpenBalance" /> → <app-amount [value]="a.newOpenBalance" />
              @if (a.previousPrincipal !== a.newPrincipal) {
                · principal <app-amount [value]="a.previousPrincipal" /> → <app-amount [value]="a.newPrincipal" />
              }
            </div>
            <ul class="ta-lines">
              @for (l of a.lines; track l.component) {
                <li>{{ components[l.component] }}: <app-amount [value]="l.previousBalance" /> → <app-amount [value]="l.newBalance" /></li>
              }
            </ul>
            <p class="ta-note">{{ a.note }}</p>
            <div class="app-muted">
              Pedida por {{ a.requestedByName ?? 'usuário' }} em {{ a.requestedAt | appDate }}
              @if (a.decidedAt && a.status !== 'CANCELLED') {
                · {{ a.status === 'REJECTED' ? 'recusada' : 'aprovada' }} por {{ a.decidedByName ?? 'usuário' }} em {{ a.decidedAt | appDate }}
              }
              @if (a.status === 'CANCELLED') {
                · retirada em {{ a.decidedAt | appDate }}
              }
              @if (a.disputeId) {
                · contestação {{ a.disputeId.slice(0, 8).toUpperCase() }}
              }
            </div>
            @if (a.decisionNote) {
              <div class="app-muted">Decisão: {{ a.decisionNote }}</div>
            }
            <div class="d-flex flex-wrap gap-2 mt-1">
              @if (a.evidence) {
                <button class="btn btn-sm btn-outline-secondary" type="button" (click)="download(a)" [attr.aria-label]="'Baixar evidência ' + a.evidence.fileName + ' (acesso registrado)'">
                  <app-icon name="download" />
                  {{ a.evidence.fileName }}
                </button>
              }
              @if (decidable(a)) {
                <button class="btn btn-sm btn-primary" type="button" (click)="approve(a)" [disabled]="busy()">Aprovar</button>
                <button class="btn btn-sm btn-outline-danger" type="button" (click)="reject(a)" [disabled]="busy()">Recusar</button>
              }
              @if (withdrawable(a)) {
                <button class="btn btn-sm btn-outline-secondary" type="button" (click)="cancel(a)" [disabled]="busy()">Retirar pedido</button>
              }
            </div>
          </li>
        } @empty {
          <li class="app-muted">Nenhuma correção manual. O valor é o informado pelo credor nas cargas.</li>
        }
      </ol>
      @for (d of data()!.divergences; track d.id) {
        @if (d.status !== 'OPEN') {
          <div class="app-muted ta-divergence">
            <app-status-chip domain="titleAmountDivergence" [status]="d.status" />
            carga com <app-amount [value]="d.reportedPrincipal" /> em {{ d.detectedAt | appDate: 'date' }}{{ d.resolvedByName ? ' · ' + d.resolvedByName : '' }}{{ d.resolutionNote ? ': ' + d.resolutionNote : '' }}
          </div>
        }
      }
    }
  `,
  styles: `
    .ta-list,
    .ta-lines {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .ta-item {
      padding: 8px 0;
      border-top: var(--bs-border-width) solid var(--app-border);
      &:first-child {
        border-top: 0;
      }
    }
    .ta-item__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
    }
    .ta-note {
      margin: 4px 0;
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .ta-divergence {
      margin-top: 4px;
    }
  `,
})
export class TitleAmountHistory {
  readonly titleId = input.required<string>();
  /** Emite quando uma decisão mudou o título (para recarregar saldo e dados). */
  readonly changed = output<void>();

  private readonly api = inject(TitleAmountApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly reasons = AMOUNT_REASON_LABELS;
  protected readonly modes = AMOUNT_MODE_LABELS;
  protected readonly components = LEDGER_COMPONENT_LABELS;
  protected readonly busy = signal(false);
  protected readonly canAdjust = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_ADJUST));
  private readonly canApprove = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_APPROVE));
  private readonly userId = computed(() => this.auth.user()?.id ?? null);

  protected readonly history = rxResource({ params: () => this.titleId(), stream: ({ params }) => this.api.history(params) });
  protected readonly data = valueOr(this.history, undefined);

  reload(): void {
    this.history.reload();
  }

  protected decidable(a: AmountAdjustmentView): boolean {
    return canDecide(a, this.userId(), this.canApprove());
  }

  protected withdrawable(a: AmountAdjustmentView): boolean {
    return this.canAdjust() && canWithdraw(a, this.userId());
  }

  protected download(a: AmountAdjustmentView): void {
    this.api.downloadEvidence(a).subscribe();
  }

  protected approve(a: AmountAdjustmentView): void {
    this.confirm
      .confirm({
        title: 'Aprovar correção de valor',
        message: 'O razão do título recebe o ajuste e o saldo muda. A aprovação fica registrada com o seu nome.',
        confirmLabel: 'Aprovar',
        reason: 'optional',
        reasonLabel: 'Observação (opcional)',
        details: [
          { label: 'Motivo', value: AMOUNT_REASON_LABELS[a.reason] },
          { label: 'Pedida por', value: a.requestedByName ?? '—' },
          { label: 'Saldo', value: `${formatAmount(a.previousOpenBalance)} → ${formatAmount(a.newOpenBalance)}` },
        ],
      })
      .subscribe((note) => this.run(this.api.approve(a.id, note || null), 'Correção aprovada e aplicada.'));
  }

  protected reject(a: AmountAdjustmentView): void {
    this.confirm
      .confirm({ title: 'Recusar correção de valor', message: 'O título não muda. Quem pediu verá o motivo.', confirmLabel: 'Recusar', destructive: true, reason: 'required' })
      .subscribe((note) => this.run(this.api.reject(a.id, note), 'Correção recusada.'));
  }

  protected cancel(a: AmountAdjustmentView): void {
    this.confirm
      .confirm({ title: 'Retirar pedido de correção', message: 'O pedido deixa de aguardar aprovação. O título não muda.', confirmLabel: 'Retirar', reason: 'optional' })
      .subscribe((note) => this.run(this.api.cancel(a.id, note || null), 'Pedido retirado.'));
  }

  protected resolve(d: AmountDivergenceView, decision: 'KEEP_MANUAL' | 'ACCEPT_CREDITOR'): void {
    this.confirm
      .confirm({
        title: decision === 'KEEP_MANUAL' ? 'Manter a correção' : 'Aceitar o valor do credor',
        message:
          decision === 'KEEP_MANUAL'
            ? 'O valor corrigido continua valendo. Cargas futuras com este mesmo valor do credor não reabrem a divergência.'
            : 'O principal do título passa ao valor informado pelo credor (lançado no razão) e a correção manual deixa de valer.',
        confirmLabel: decision === 'KEEP_MANUAL' ? 'Manter' : 'Aceitar',
        reason: 'required',
      })
      .subscribe((note) => {
        this.busy.set(true);
        this.api
          .resolveDivergence(d.id, decision, note)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe(() => {
            this.notifications.success('Divergência resolvida.');
            this.history.reload();
            this.changed.emit();
          });
      });
  }

  private run(call: ReturnType<TitleAmountApi['approve']>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe(() => {
      this.notifications.success(message);
      this.history.reload();
      this.changed.emit();
    });
  }
}
