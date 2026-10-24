import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { StatementsApi } from '../data/settlement.api';
import { StatementFilter, StatementView } from '../data/settlement.models';
import { periodText } from '../data/settlement.rules';
import { NewStatementDialog } from './new-statement-dialog';

/** Demonstrativos de prestação de contas por credor, do rascunho à resposta do credor. */
@Component({
  selector: 'app-statement-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Demonstrativos" subtitle="Prestação de contas ao credor: o que foi recebido, quanto a assessoria fica e o líquido a repassar, por período.">
      @if (canClose()) {
        <button mat-flat-button type="button" (click)="create()" data-tour="statement-new">
          <mat-icon aria-hidden="true">add</mat-icon>
          Novo demonstrativo
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar demonstrativos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>
    <app-data-table
      caption="Demonstrativos de prestação de contas"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="request_page"
      emptyMessage="Nenhum demonstrativo encontrado."
      emptyHint="Crie o demonstrativo do período encerrado; o sistema sugere o ciclo pelo dia de fechamento do contrato."
    >
      <ng-template appCell="period" [appCellOf]="query.rows()" let-s>
        <strong>nº {{ s.sequenceNumber }}</strong> · {{ period(s) }}
        <div class="app-muted sl-small">{{ creditors.name(s.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-s><app-status-chip domain="settlementStatement" [status]="s.status" /></ng-template>
      <ng-template appCell="recovered" [appCellOf]="query.rows()" let-s>
        @if (s.totals) {
          <app-amount [value]="s.totals.recovered" />
          <div class="app-muted sl-small">{{ s.totals.receipts }} registro(s)</div>
        } @else {
          <span class="app-muted">não calculado</span>
        }
      </ng-template>
      <ng-template appCell="net" [appCellOf]="query.rows()" let-s>
        @if (s.totals) {
          <app-amount [value]="s.totals.netTransfer" [signed]="true" />
          @if (s.totals.outstanding !== 0 && s.status !== 'CANCELLED') {
            <div class="sl-small sl-warn">saldo <app-amount [value]="s.totals.outstanding" /></div>
          }
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="updated" [appCellOf]="query.rows()" let-s>{{ (s.creditorResponseAt ?? s.sentAt ?? s.approvedAt ?? s.calculatedAt) | appDate }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
        <a mat-icon-button [routerLink]="['/prestacao-de-contas/demonstrativos', s.id]" aria-label="Abrir demonstrativo" matTooltip="Abrir">
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
      color: var(--app-warning-fg);
      font-weight: 600;
    }
  `,
})
export class StatementList {
  private readonly api = inject(StatementsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly statuses = statusOptions('settlementStatement');
  protected readonly canClose = computed(() => this.auth.hasPermission(Permission.SETTLEMENT_CLOSE));

  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>(this.params.get('credor') ? [this.params.get('credor')!] : []),
    status: [this.params.get('situacao') ?? ''],
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): StatementFilter => ({ creditorId: this.filters.getRawValue().creditor[0] ?? null, status: this.filters.getRawValue().status || null })),
    ),
    { initialValue: { creditorId: this.filters.getRawValue().creditor[0] ?? null, status: this.filters.getRawValue().status || null }, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((s) => s.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Demonstrativo' },
    { key: 'status', header: 'Situação' },
    { key: 'recovered', header: 'Recuperado', align: 'end' },
    { key: 'net', header: 'Líquido a repassar', align: 'end' },
    { key: 'updated', header: 'Última etapa' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected period(s: StatementView): string {
    return periodText(s.periodStart, s.periodEnd);
  }

  protected create(): void {
    this.dialog
      .open<NewStatementDialog, void, StatementView>(NewStatementDialog, { width: '520px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((s) => {
        if (s) {
          void this.router.navigate(['/prestacao-de-contas/demonstrativos', s.id]);
        }
      });
  }
}
