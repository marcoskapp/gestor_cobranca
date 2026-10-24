import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { TransfersApi } from '../data/settlement.api';
import { BalanceView, StatementBalance, TransferView } from '../data/settlement.models';
import { periodText } from '../data/settlement.rules';
import { TransferDialog, TransferDialogData } from './transfer-dialog';

/**
 * Saldo a repassar (e a receber) por credor, com os demonstrativos aprovados em aberto, e os
 * repasses registrados com comprovante. Repasse não é excluído: cancela-se com motivo.
 */
@Component({
  selector: 'app-transfer-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, EmptyState, EntityPicker, PageHeader, Skeleton, StatusChip],
  template: `
    <app-page-header title="Repasses ao credor" subtitle="Saldo dos demonstrativos aprovados e repasses registrados com o comprovante bancário." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar repasses">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
    </form>

    <section aria-labelledby="tl-balances" data-tour="transfer-balances">
      <h2 class="app-section-title" id="tl-balances">Saldos por credor</h2>
      @if (balanceList.isLoading() && !balances().length) {
        <app-skeleton variant="card" [rows]="3" />
      } @else if (!balances().length) {
        <app-empty-state icon="account_balance" title="Nenhum saldo em aberto." message="Demonstrativos aprovados aparecem aqui até o repasse zerar o saldo." [compact]="true" />
      } @else {
        <div class="tl-cards">
          @for (b of balances(); track b.creditorId) {
            <article class="app-card tl-card">
              <h3 class="app-card__title">{{ creditors.name(b.creditorId) }}</h3>
              <div class="tl-totals">
                <div><span class="tl-label">A repassar</span><app-amount class="tl-value" [value]="b.toTransfer" /></div>
                <div><span class="tl-label">A receber do credor</span><app-amount class="tl-value" [value]="b.toReceive" /></div>
              </div>
              <ul class="tl-list">
                @for (s of open(b); track s.statementId) {
                  <li>
                    <a [routerLink]="['/prestacao-de-contas/demonstrativos', s.statementId]">nº {{ s.sequenceNumber }} · {{ period(s) }}</a>
                    <span class="app-numeric">{{ s.outstanding > 0 ? 'repassar' : 'receber' }} <app-amount [value]="abs(s.outstanding)" /></span>
                    @if (canTransfer()) {
                      <button mat-stroked-button type="button" (click)="register(b, s)">{{ s.outstanding > 0 ? 'Repassar' : 'Receber' }}</button>
                    }
                  </li>
                }
              </ul>
            </article>
          }
        </div>
      }
    </section>

    <h2 class="app-section-title">Repasses registrados</h2>
    <app-data-table
      caption="Repasses registrados"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="account_balance"
      emptyMessage="Nenhum repasse registrado."
    >
      <ng-template appCell="date" [appCellOf]="query.rows()" let-t>{{ t.transferredOn | appDate: 'date' }}</ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-t>
        {{ creditors.name(t.creditorId) }}
        <div class="app-muted tl-small"><a [routerLink]="['/prestacao-de-contas/demonstrativos', t.statementId]">demonstrativo</a> · {{ t.bankReference }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-t>
        <app-amount [value]="t.amount" />
        <div class="app-muted tl-small">{{ t.direction === 'AGENCY_TO_CREDITOR' ? 'ao credor' : 'do credor' }}</div>
      </ng-template>
      <ng-template appCell="by" [appCellOf]="query.rows()" let-t>
        {{ users.name(t.registeredBy) }}
        <div class="app-muted tl-small">{{ t.registeredAt | appDate }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-t>
        <app-status-chip domain="settlementTransfer" [status]="t.status" />
        @if (t.cancelReason) {
          <div class="app-muted tl-small">{{ t.cancelReason }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-t>
        @if (t.evidenceName) {
          <button mat-icon-button type="button" (click)="evidence(t)" aria-label="Baixar comprovante" matTooltip="Baixar comprovante"><mat-icon aria-hidden="true">attach_file</mat-icon></button>
        }
        @if (canTransfer() && t.status === 'REGISTERED') {
          <button mat-icon-button type="button" (click)="cancel(t)" aria-label="Cancelar repasse" matTooltip="Cancelar repasse"><mat-icon aria-hidden="true">block</mat-icon></button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .tl-cards {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .tl-card {
      margin: 0 !important;
    }
    .tl-totals {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 24px;
      div {
        display: flex;
        flex-direction: column;
      }
    }
    .tl-label {
      font-size: var(--app-text-xs);
      color: var(--mat-sys-on-surface-variant);
    }
    .tl-value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .tl-list {
      display: grid;
      gap: 6px;
      margin: 12px 0 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: 6px;
      }
    }
    .tl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class TransferList {
  private readonly api = inject(TransfersApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canTransfer = computed(() => this.auth.hasPermission(Permission.SETTLEMENT_TRANSFER));
  protected readonly period = (s: StatementBalance) => periodText(s.periodStart, s.periodEnd);
  protected readonly abs = Math.abs;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]) });
  private readonly creditorId = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(200),
      map(() => this.filters.getRawValue().creditor[0] ?? null),
    ),
    { initialValue: null },
  );
  private readonly round = signal(0);
  protected readonly balanceList = rxResource({ params: () => ({ c: this.creditorId(), r: this.round() }), stream: ({ params }) => this.api.balances(params.c) });
  private readonly balancesRaw = valueOr(this.balanceList, []);
  protected readonly balances = computed(() => this.balancesRaw().filter((b) => b.toTransfer > 0 || b.toReceive > 0));
  protected readonly query = pagedQuery(computed(() => ({ c: this.creditorId(), r: this.round() })), (f, page) => this.api.search(f.c, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [...this.balances().map((b) => b.creditorId), ...this.query.rows().map((t) => t.creditorId)]);
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((t) => t.registeredBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'date', header: 'Data' },
    { key: 'creditor', header: 'Credor e referência' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'by', header: 'Registrado por' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected open(b: BalanceView): StatementBalance[] {
    return b.statements.filter((s) => Math.abs(s.outstanding) > 0.004);
  }

  protected register(b: BalanceView, s: StatementBalance): void {
    this.dialog
      .open<TransferDialog, TransferDialogData, TransferView>(TransferDialog, { data: { creditorName: this.creditors.name(b.creditorId), statement: s }, width: '600px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((t) => {
        if (t) {
          this.notifications.success(`Repasse de ${formatAmount(t.amount)} registrado com o comprovante.`);
          this.round.update((n) => n + 1);
        }
      });
  }

  protected evidence(t: TransferView): void {
    this.api.downloadEvidence(t.id).subscribe((name) => this.notifications.success(`Comprovante baixado: ${name}`));
  }

  protected cancel(t: TransferView): void {
    this.confirm
      .confirm({
        title: 'Cancelar repasse',
        message: 'O repasse fica registrado como cancelado (nunca é excluído) e o saldo do demonstrativo volta a ficar em aberto.',
        confirmLabel: 'Cancelar repasse',
        destructive: true,
        reason: 'required',
        reasonMaxLength: 500,
        details: [
          { label: 'Credor', value: this.creditors.name(t.creditorId) },
          { label: 'Valor', value: formatAmount(t.amount) },
          { label: 'Referência', value: t.bankReference },
        ],
      })
      .subscribe((reason) =>
        this.api.cancel(t.id, reason).subscribe(() => {
          this.notifications.success('Repasse cancelado.');
          this.round.update((n) => n + 1);
        }),
      );
  }
}
