import { Clipboard } from '@angular/cdk/clipboard';
import { Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription, finalize, timer } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { InstructionsApi } from '../data/payment.api';
import { INSTRUCTION_TYPE_LABELS } from '../data/payment.labels';
import { InstructionView } from '../data/payment.models';
import { expirationText, instructionTarget, isOpenInstruction } from '../data/payment.rules';
import { PixQr } from './pix-qr';

/** Intervalos de acompanhamento: registro no PSP (rápido) e espera do pagamento (espaçado). */
export const PENDING_POLL_MS = 2_000;
export const ACTIVE_POLL_MS = 15_000;
const MAX_POLLS = 60;

/**
 * Instrução de pagamento pronta para o devedor: QR Code e "copia e cola" com botão copiar,
 * validade e situação. Acompanha o registro no PSP (`PENDING → ACTIVE`) e o pagamento
 * (`ACTIVE → PAID`) sem recarregar a tela; cancela com motivo.
 */
@Component({
  selector: 'app-instruction-panel',
  imports: [Icon, RouterLink, Amount, StatusChip, AppDatePipe, PixQr],
  template: `
    @let i = current();
    <div class="ip" data-tour="instruction-panel">
      <div class="ip__head">
        <app-status-chip domain="paymentInstruction" [status]="i.status" />
        <span class="ip__amount"><app-amount [value]="i.amount" /></span>
        <span class="app-muted">{{ types[i.type] }} · {{ target(i) }}</span>
      </div>

      @switch (i.status) {
        @case ('PENDING') {
          <div class="progress" role="progressbar" aria-label="Registrando no PSP"><div class="progress-bar progress-bar-striped progress-bar-animated w-100"></div></div>
          <p class="app-muted mb-0" role="status">Registrando a cobrança no PSP. O QR Code aparece aqui em instantes.</p>
        }
        @case ('FAILED') {
          <div class="alert alert-danger d-flex gap-2 align-items-start mb-0" role="alert">
            <app-icon name="exclamation-circle" />
            <span>O PSP recusou a cobrança{{ i.failureCode ? ' (' + i.failureCode + ')' : '' }}. Confira a conta Pix com o administrador e gere outra instrução.</span>
          </div>
        }
        @case ('PAID') {
          <div class="alert alert-success d-flex gap-2 align-items-start mb-0" role="status">
            <app-icon name="check-circle" />
            <span>
              Pago em {{ i.paidAt | appDate }}.
              @if (i.paymentId && canViewPayments()) {
                <a [routerLink]="['/pagamentos', i.paymentId]">Ver o pagamento</a>
              }
            </span>
          </div>
        }
        @case ('CANCELLED') {
          <div class="alert alert-secondary d-flex gap-2 align-items-start mb-0" role="note"><app-icon name="slash-circle" /><span>Cancelada em {{ i.cancelledAt | appDate }}{{ i.cancelReason ? ': ' + i.cancelReason : '' }}.</span></div>
        }
        @case ('EXPIRED') {
          <div class="alert alert-secondary d-flex gap-2 align-items-start mb-0" role="note"><app-icon name="stopwatch" /><span>Expirou em {{ i.expiresAt | appDate }}. Gere uma nova instrução se o devedor ainda for pagar.</span></div>
        }
      }

      @if (i.status === 'ACTIVE' && i.pixCopyPaste) {
        <div class="ip__body">
          <app-pix-qr [value]="i.pixCopyPaste" [alt]="'QR Code do Pix de ' + amountText(i)" />
          <div class="ip__copy">
            <span class="app-section-title" id="ip-code-label">Pix copia e cola</span>
            <code class="ip__code" aria-labelledby="ip-code-label">{{ i.pixCopyPaste }}</code>
            <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="copy(i)" data-tour="instruction-copy">
              <app-icon name="copy" />
              Copiar código
            </button>
            <dl class="app-definition-list ip__facts">
              @if (i.dueDate) {
                <dt>Vencimento</dt>
                <dd>{{ i.dueDate | appDate: 'date' }}</dd>
              }
              <dt>Validade</dt>
              <dd>{{ i.expiresAt ? (i.expiresAt | appDate) : '—' }} <span class="app-muted">· {{ expiry() }}</span></dd>
            </dl>
          </div>
        </div>
      }

      @if (open() && canCancel()) {
        <div class="ip__actions">
          <button type="button" class="btn btn-link ip__danger d-inline-flex align-items-center gap-2" (click)="cancel()" [disabled]="busy()">
            <app-icon name="slash-circle" />
            Cancelar instrução
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .ip {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .ip__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 12px;
    }
    .ip__amount {
      font-size: var(--app-text-xl);
      font-weight: 600;
    }
    .ip__body {
      display: flex;
      flex-wrap: wrap;
      gap: 16px 24px;
      align-items: flex-start;
    }
    .ip__copy {
      flex: 1 1 260px;
      min-width: 0;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
    }
    .ip__code {
      display: block;
      width: 100%;
      box-sizing: border-box;
      max-height: 96px;
      overflow: auto;
      padding: 8px 10px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      color: var(--bs-body-color);
      font-size: var(--app-text-xs);
      line-height: 1.5;
      word-break: break-all;
      user-select: all;
    }
    .ip__facts {
      margin: 4px 0 0;
    }
    .ip__actions {
      display: flex;
      justify-content: flex-end;
    }
    .ip__danger {
      --bs-btn-color: var(--bs-danger-text-emphasis);
      --bs-btn-hover-color: var(--bs-danger-text-emphasis);
      --bs-btn-active-color: var(--bs-danger-text-emphasis);
      --bs-btn-disabled-color: var(--bs-danger-text-emphasis);
    }
  `,
})
export class InstructionPanel {
  readonly instruction = input.required<InstructionView>();
  /** A instrução mudou (registro, pagamento, cancelamento). */
  readonly changed = output<InstructionView>();

  private readonly api = inject(InstructionsApi);
  private readonly auth = inject(AuthService);
  private readonly clipboard = inject(Clipboard);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly types = INSTRUCTION_TYPE_LABELS;
  protected readonly target = instructionTarget;
  private readonly latest = signal<InstructionView | null>(null);
  protected readonly current = computed(() => {
    const fromServer = this.latest();
    const given = this.instruction();
    return fromServer && fromServer.id === given.id && fromServer.version >= given.version ? fromServer : given;
  });
  protected readonly open = computed(() => isOpenInstruction(this.current()));
  protected readonly busy = signal(false);
  protected readonly canCancel = computed(() => this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE));
  protected readonly canViewPayments = computed(() => this.auth.hasPermission(Permission.PAYMENT_VIEW));
  private readonly clock = signal(Date.now());
  protected readonly expiry = computed(() => expirationText(this.current(), this.clock()));

  private poll: Subscription | null = null;
  private polls = 0;

  constructor() {
    // acompanha enquanto aberta: registro no PSP a cada 2 s; pagamento a cada 15 s (até ~15 min)
    effect(() => {
      const i = this.current();
      this.schedule(i);
    });
    const tick = timer(30_000, 30_000).subscribe(() => this.clock.set(Date.now()));
    this.destroyRef.onDestroy(() => {
      tick.unsubscribe();
      this.poll?.unsubscribe();
    });
  }

  protected amountText(i: InstructionView): string {
    return formatAmount(i.amount);
  }

  protected copy(i: InstructionView): void {
    if (i.pixCopyPaste && this.clipboard.copy(i.pixCopyPaste)) {
      this.notifications.success('Código Pix copiado. Cole na conversa com o devedor.');
    }
  }

  protected cancel(): void {
    const i = this.current();
    this.confirm
      .confirm({
        title: 'Cancelar instrução de pagamento',
        message: 'O QR Code deixa de valer no PSP. Um pagamento que ainda chegar continua sendo aceito e alocado.',
        confirmLabel: 'Cancelar instrução',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo do cancelamento',
        reasonMaxLength: 200,
        details: [
          { label: 'Valor', value: formatAmount(i.amount) },
          { label: 'Destino', value: instructionTarget(i) },
          { label: 'Tipo', value: INSTRUCTION_TYPE_LABELS[i.type] },
        ],
      })
      .subscribe((reason) => {
        this.busy.set(true);
        this.api
          .cancel(i.id, reason)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe((updated) => {
            this.notifications.success('Instrução cancelada.');
            this.update(updated);
          });
      });
  }

  private schedule(i: InstructionView): void {
    this.poll?.unsubscribe();
    this.poll = null;
    if (!isOpenInstruction(i) || this.polls >= MAX_POLLS) {
      return;
    }
    this.poll = timer(i.status === 'PENDING' ? PENDING_POLL_MS : ACTIVE_POLL_MS).subscribe(() => {
      this.polls++;
      this.api.get(i.id).subscribe({ next: (updated) => this.update(updated), error: () => undefined });
    });
  }

  private update(updated: InstructionView): void {
    const before = this.current();
    this.latest.set(updated);
    if (updated.status !== before.status || updated.version !== before.version) {
      if (updated.status === 'PAID' && before.status !== 'PAID') {
        this.notifications.success(`Pagamento recebido: ${formatAmount(updated.amount)}.`);
      }
      this.changed.emit(updated);
    }
  }
}
