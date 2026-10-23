import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentsApi } from '../data/payment.api';
import { METHOD_LABELS } from '../data/payment.labels';
import { PaymentView } from '../data/payment.models';
import { reallocationSources } from '../data/payment.rules';
import { ReallocateDialog, ReallocateDialogData } from './reallocate-dialog';

/**
 * Fila de valores recebidos e não aplicados: sobra sem destino (destino quitado, acordo
 * encerrado, valor acima do saldo) e valor que o acordo não aplicou. Cada item vai para outro
 * destino do mesmo devedor ou é registrado como devolução ao pagador.
 */
@Component({
  selector: 'app-reallocation-list',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Sobras e realocação" subtitle="Dinheiro que entrou e ainda não abateu nenhuma dívida: realoque ou registre a devolução." />
    <app-data-table
      caption="Valores a realocar"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="savings"
      emptyMessage="Nenhum valor aguardando realocação."
      emptyHint="Sobras aparecem aqui quando um pagamento passa do saldo do destino ou o acordo já foi encerrado."
    >
      <ng-template appCell="paidAt" [appCellOf]="query.rows()" let-p>{{ p.paidAt | appDate }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-p>
        {{ debtors.name(p.debtorId) }}
        <div class="app-muted rl-small">{{ creditors.name(p.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-p>
        <app-amount [value]="p.amount" />
        <div class="app-muted rl-small">{{ methods[p.method] }}</div>
      </ng-template>
      <ng-template appCell="pending" [appCellOf]="query.rows()" let-p>
        <strong><app-amount [value]="p.pendingReallocation" /></strong>
        <div class="app-muted rl-small">{{ breakdown(p) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p><app-status-chip domain="payment" [status]="p.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        <button mat-stroked-button type="button" (click)="reallocate(p)" data-tour="reallocate-action">
          <mat-icon aria-hidden="true">call_split</mat-icon>
          Realocar
        </button>
        <a mat-icon-button [routerLink]="['/pagamentos', p.id]" aria-label="Abrir pagamento" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .rl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class ReallocationList {
  private readonly api = inject(PaymentsApi);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);
  protected readonly methods = METHOD_LABELS;

  protected readonly query = pagedQuery(signal(null), (_, page) => this.api.pendingReallocation(page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((p) => p.debtorId), { batch: 100 });

  protected readonly columns: TableColumn[] = [
    { key: 'paidAt', header: 'Pago em' },
    { key: 'debtor', header: 'Devedor e credor' },
    { key: 'amount', header: 'Pagamento', align: 'end' },
    { key: 'pending', header: 'A realocar', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected breakdown(p: PaymentView): string {
    const sources = reallocationSources(p);
    const sobra = sources.filter((s) => !s.allocationId).length > 0;
    const unapplied = sources.filter((s) => !!s.allocationId).length;
    return [sobra ? 'sobra sem destino' : null, unapplied ? 'não aplicado no acordo' : null].filter(Boolean).join(' + ') || '—';
  }

  protected reallocate(p: PaymentView): void {
    this.dialog
      .open<ReallocateDialog, ReallocateDialogData, PaymentView>(ReallocateDialog, {
        data: { payment: p, debtorName: this.debtors.name(p.debtorId) || 'Devedor' },
        width: '720px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success('Valor realocado.');
          this.query.reload();
        }
      });
  }
}
