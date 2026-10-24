import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { timer } from 'rxjs';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ReconciliationApi } from '../data/payment.api';
import { StatementImportView } from '../data/payment.models';
import { importIndicators, isImportRunning } from '../data/payment.rules';
import { StatementUploadDialog } from './statement-upload-dialog';

const percent = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 });

/** Extratos e arquivos de retorno importados, com o resultado da conciliação de cada lote. */
@Component({
  selector: 'app-statement-list',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Conciliação" subtitle="Importe o extrato da conta de recebimento: o sistema confere com Pix, pagamentos e parcelas.">
      <a mat-button routerLink="/pagamentos/divergencias">
        <mat-icon aria-hidden="true">rule</mat-icon>
        Divergências
      </a>
      <button mat-flat-button type="button" (click)="upload()" data-tour="statement-import">
        <mat-icon aria-hidden="true">upload_file</mat-icon>
        Importar extrato
      </button>
    </app-page-header>
    <app-data-table
      caption="Extratos importados"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="account_balance"
      emptyMessage="Nenhum extrato importado ainda."
      emptyHint="Exporte o extrato do banco em CSV no layout genérico e importe aqui."
    >
      <button mat-stroked-button appEmptyAction type="button" (click)="upload()">Importar extrato</button>
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-s>
        {{ s.createdAt | appDate }}
        <div class="app-muted sl-small">{{ users.name(s.requestedBy) }}</div>
      </ng-template>
      <ng-template appCell="file" [appCellOf]="query.rows()" let-s>
        <a [routerLink]="['/pagamentos/conciliacao', s.id]">{{ s.fileName ?? 'extrato' }}</a>
        <div class="app-muted sl-small">{{ size(s.fileSize) }} · {{ s.creditorId ? creditors.name(s.creditorId) : 'Todos os credores' }}</div>
      </ng-template>
      <ng-template appCell="lines" [appCellOf]="query.rows()" let-s><span class="app-numeric">{{ s.totalLines }}</span></ng-template>
      <ng-template appCell="reconciled" [appCellOf]="query.rows()" let-s>
        <span class="app-numeric">{{ s.reconciledLines + s.createdPayments }}</span>
        <div class="app-muted sl-small">{{ share(s) }}</div>
      </ng-template>
      <ng-template appCell="divergent" [appCellOf]="query.rows()" let-s>
        <span class="app-numeric" [class.sl-warn]="s.divergentLines > 0">{{ s.divergentLines }}</span>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-s><app-status-chip domain="statementImport" [status]="s.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
        <a mat-icon-button [routerLink]="['/pagamentos/conciliacao', s.id]" aria-label="Abrir lote" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .sl-small {
      font-size: var(--app-text-xs);
    }
    .sl-warn {
      font-weight: 600;
      color: var(--app-warning-fg);
    }
  `,
})
export class StatementList {
  private readonly api = inject(ReconciliationApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  protected readonly size = sizeText;

  protected readonly query = pagedQuery(signal(null), (_, page) => this.api.imports(page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((s) => s.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((s) => s.requestedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'createdAt', header: 'Importado em' },
    { key: 'file', header: 'Arquivo' },
    { key: 'lines', header: 'Linhas', align: 'end' },
    { key: 'reconciled', header: 'Conciliadas', align: 'end' },
    { key: 'divergent', header: 'Divergentes', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    // lotes em processamento: atualiza a cada 5 s
    const refresh = timer(5_000, 5_000).subscribe(() => {
      if (this.query.rows().some(isImportRunning) && !this.query.loading()) {
        this.query.reload();
      }
    });
    inject(DestroyRef).onDestroy(() => refresh.unsubscribe());
  }

  protected share(s: StatementImportView): string {
    const i = importIndicators(s);
    return i.reconciledShare === null ? '—' : `${percent.format(i.reconciledShare)} dos créditos`;
  }

  protected upload(): void {
    this.dialog
      .open<StatementUploadDialog, void, StatementImportView>(StatementUploadDialog, { width: '760px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((imported) => {
        if (imported) {
          void this.router.navigate(['/pagamentos/conciliacao', imported.id]);
        }
      });
  }
}
