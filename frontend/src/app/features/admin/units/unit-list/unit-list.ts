import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { BusinessUnitView } from '../../../organization/data/organization.models';
import { UnitForm } from '../unit-form/unit-form';

@Component({
  selector: 'app-unit-list',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Unidades" subtitle="Filiais ou células operacionais da assessoria.">
      <button mat-flat-button type="button" (click)="open(null)">
        <mat-icon aria-hidden="true">add</mat-icon>
        Nova unidade
      </button>
    </app-page-header>
    <app-data-table
      caption="Unidades"
      [rows]="rows()"
      [columns]="columns"
      [loading]="units.isLoading()"
      [failed]="units.status() === 'error'"
      (retry)="units.reload()"
    >
      <ng-template appCell="code" [appCellOf]="rows()" let-u><span class="app-mono">{{ u.code }}</span></ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-u><app-status-chip domain="active" [status]="u.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-u>
        <button mat-icon-button type="button" (click)="open(u)" [attr.aria-label]="'Editar ' + u.name" matTooltip="Editar">
          <mat-icon aria-hidden="true">edit</mat-icon>
        </button>
      </ng-template>
    </app-data-table>
  `,
})
export class UnitList {
  private readonly api = inject(BusinessUnitsApi);
  private readonly dialog = inject(MatDialog);
  protected readonly units = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.units, []);
  protected readonly columns: TableColumn[] = [
    { key: 'code', header: 'Código' },
    { key: 'name', header: 'Nome' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected open(unit: BusinessUnitView | null): void {
    this.dialog
      .open<UnitForm, BusinessUnitView | null, BusinessUnitView>(UnitForm, { data: unit, width: '480px' })
      .afterClosed()
      .subscribe((saved) => saved && this.units.reload());
  }
}
