import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Observable, finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { Icon } from '../../../../shared/components/icon/icon';
import { DialogService } from '../../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { TOKEN_STATE_LABELS, tokenStateTone } from '../../../public-api/data/public-api.labels';
import { TokenState } from '../../../public-api/data/public-api.models';
import { PlatformBillingApi } from '../../data/platform-billing.api';
import { BulkTokenResult, PlatformTokenView } from '../../data/platform-billing.models';
import { PLATFORM_BILLING_ERRORS } from '../platform-billing.labels';
import { PlatformTokenPauseDialog, PlatformTokenPauseDialogData } from '../platform-token-pause-dialog';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Tokens de integração da assessoria vistos pela plataforma: nome, credor, escopos, situação, último uso e tráfego do
 * ciclo, com as ações de controle (pausar com retomada opcional, retomar, revogar; pausar e retomar todos) para quem
 * tem {@code PLATFORM_API_TOKENS_MANAGE}. Motivo obrigatório em toda ação e confirmação forte na revogação.
 */
@Component({
  selector: 'app-platform-tokens-panel',
  imports: [AppDatePipe, CellDef, DataTable, Icon, NgbTooltip],
  template: `
    @if (canManage()) {
      <div class="ptk-bulk" data-tour="tokens-bulk">
        <button class="btn btn-sm btn-outline-warning d-inline-flex align-items-center gap-2" type="button" (click)="pause()" [disabled]="busy() || !activeCount()">
          <app-icon name="pause-circle" /> Pausar todos ({{ activeCount() }})
        </button>
        <button class="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="resumeAll()" [disabled]="busy() || !pausedCount()">
          <app-icon name="play-circle" /> Retomar todos ({{ pausedCount() }})
        </button>
      </div>
    }
    <app-data-table
      caption="Tokens de integração da assessoria"
      [rows]="rows()"
      [columns]="columns"
      [loading]="tokens.isLoading()"
      [failed]="tokens.status() === 'error'"
      (retry)="tokens.reload()"
      emptyIcon="key"
      emptyMessage="A assessoria não tem tokens de integração."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-t>
        <strong>{{ t.name }}</strong>
        <div class="app-muted app-mono ptk-small">{{ t.prefix }}</div>
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="rows()" let-t>{{ t.creditorName || '—' }}</ng-template>
      <ng-template appCell="scopes" [appCellOf]="rows()" let-t>
        <span class="ptk-small" [ngbTooltip]="t.scopes.join(', ')">{{ t.scopes.length }} escopo(s)</span>
      </ng-template>
      <ng-template appCell="state" [appCellOf]="rows()" let-t>
        <span class="badge rounded-pill border fw-normal" [class]="stateClass(t.state)">{{ stateLabels[t.state] }}</span>
        @if (t.state === 'PAUSED') {
          <div class="app-muted ptk-small">desde {{ t.pausedAt | appDate }}{{ t.resumeAt ? ' · retoma em ' + (t.resumeAt | appDate) : '' }}</div>
          <div class="ptk-small ptk-reason">{{ t.pauseReason }}</div>
        } @else if (t.state === 'REVOKED') {
          <div class="app-muted ptk-small">{{ t.revokedByPlatform ? 'pela plataforma' : 'pela assessoria' }} em {{ t.revokedAt | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="lastUsed" [appCellOf]="rows()" let-t>{{ t.lastUsedAt | appDate }}</ng-template>
      <ng-template appCell="cycle" [appCellOf]="rows()" let-t>
        <span class="ptk-small">{{ n(t.cycle.billableRequests) }} cobráveis · {{ n(t.cycle.nonBillableRequests) }} não cobráveis</span>
        <div class="ptk-small" [class.text-warning-emphasis]="t.cycle.rejectedRequests > 0">{{ n(t.cycle.rejectedRequests) }} recusadas · {{ n(t.cycle.mbIn) }}/{{ n(t.cycle.mbOut) }} MB</div>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-t>
        @if (canManage()) {
          @if (t.state === 'ACTIVE') {
            <button class="btn btn-sm btn-link" type="button" (click)="pause(t)" [disabled]="busy()">Pausar</button>
          }
          @if (t.state === 'PAUSED') {
            <button class="btn btn-sm btn-link" type="button" (click)="resume(t)" [disabled]="busy()">Retomar</button>
          }
          @if (t.state === 'ACTIVE' || t.state === 'PAUSED') {
            <button class="btn btn-sm btn-link text-danger" type="button" (click)="revoke(t)" [disabled]="busy()">Revogar</button>
          }
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .ptk-bulk {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      justify-content: flex-end;
      margin-bottom: 8px;
    }
    .ptk-small {
      font-size: var(--app-text-xs);
    }
    .ptk-reason {
      max-width: 28ch;
      overflow-wrap: anywhere;
    }
  `,
})
export class PlatformTokensPanel {
  readonly tenantId = input.required<string>();
  readonly tenantName = input.required<string>();
  /** Alguma ação mudou os tokens (a tela recarrega a posição). */
  readonly changed = output<void>();

  private readonly api = inject(PlatformBillingApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly stateLabels = TOKEN_STATE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PLATFORM_API_TOKENS_MANAGE));
  protected readonly busy = signal(false);
  protected readonly tokens = rxResource({ params: () => this.tenantId(), stream: ({ params }) => this.api.tokens(params) });
  protected readonly rows = computed<PlatformTokenView[]>(() => (this.tokens.hasValue() ? this.tokens.value() : []));
  protected readonly activeCount = computed(() => this.rows().filter((t) => t.state === 'ACTIVE').length);
  protected readonly pausedCount = computed(() => this.rows().filter((t) => t.state === 'PAUSED').length);

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Token' },
    { key: 'creditor', header: 'Credor' },
    { key: 'scopes', header: 'Escopos' },
    { key: 'state', header: 'Situação' },
    { key: 'lastUsed', header: 'Último uso' },
    { key: 'cycle', header: 'Tráfego no ciclo', info: 'platform.tokens.cycle-traffic' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected stateClass(state: TokenState): string {
    const tone = tokenStateTone(state);
    return `bg-${tone}-subtle text-${tone}-emphasis border-${tone}-subtle`;
  }

  /** Pausa um token ou, sem argumento, todos os ativos. */
  protected pause(token?: PlatformTokenView): void {
    this.dialog
      .open<PlatformTokenPauseDialog, PlatformTokenPauseDialogData, PlatformTokenView | BulkTokenResult>(PlatformTokenPauseDialog, {
        data: { tenantId: this.tenantId(), tenantName: this.tenantName(), token },
      })
      .afterClosed()
      .subscribe((result) => {
        if (!result) {
          return;
        }
        this.notifications.success('affected' in result ? `${result.affected} token(s) pausado(s).` : 'Token pausado. A assessoria foi avisada.');
        this.refresh();
      });
  }

  protected resume(t: PlatformTokenView): void {
    this.confirm
      .confirm({
        title: 'Retomar token',
        message: 'As chamadas com este token voltam a ser aceitas na próxima requisição. A assessoria é avisada.',
        confirmLabel: 'Retomar',
        reason: 'required',
        reasonLabel: 'Motivo da retomada',
        reasonMaxLength: 500,
        details: [
          { label: 'Token', value: `${t.name} (${t.prefix})` },
          { label: 'Motivo da pausa', value: t.pauseReason ?? '—' },
        ],
      })
      .subscribe((reason) => this.run(this.api.resumeToken(this.tenantId(), t.id, reason), 'Token retomado.'));
  }

  protected resumeAll(): void {
    this.confirm
      .confirm({
        title: 'Retomar todos os tokens pausados',
        message: 'Todas as pausas desta assessoria terminam e as chamadas voltam a ser aceitas. Revogações não são desfeitas.',
        confirmLabel: 'Retomar todos',
        reason: 'required',
        reasonLabel: 'Motivo da retomada',
        reasonMaxLength: 500,
        details: [{ label: 'Tokens pausados', value: String(this.pausedCount()) }],
      })
      .subscribe((reason) => this.run(this.api.resumeAllTokens(this.tenantId(), reason), 'Tokens retomados.'));
  }

  protected revoke(t: PlatformTokenView): void {
    this.confirm
      .confirm({
        title: 'Revogar token',
        message: 'A revogação é imediata e definitiva: o token não volta a funcionar e a assessoria precisará criar outro.',
        warning: 'Prefira pausar quando a situação puder ser corrigida.',
        confirmLabel: 'Revogar',
        destructive: true,
        confirmationText: 'REVOGAR',
        reason: 'required',
        reasonLabel: 'Motivo da revogação',
        reasonMaxLength: 500,
        details: [
          { label: 'Token', value: `${t.name} (${t.prefix})` },
          { label: 'Credor', value: t.creditorName ?? '—' },
          { label: 'Assessoria', value: this.tenantName() },
        ],
      })
      .subscribe((reason) => this.run(this.api.revokeToken(this.tenantId(), t.id, reason), 'Token revogado. A assessoria foi avisada.'));
  }

  private run(call: Observable<unknown>, success: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => {
        this.notifications.success(success);
        this.refresh();
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.notifications.error((e.code && PLATFORM_BILLING_ERRORS[e.code]) || userMessage(e));
      },
    });
  }

  private refresh(): void {
    this.tokens.reload();
    this.changed.emit();
  }
}
