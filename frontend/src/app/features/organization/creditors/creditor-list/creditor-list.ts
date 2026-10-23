import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, map } from 'rxjs';
import { Permission } from '../../../../core/auth/permission';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { DocumentPipe } from '../../../../shared/pipes/document.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { CreditorsApi } from '../../data/organization.api';
import { CreditorView } from '../../data/organization.models';
import { CreditorForm } from '../creditor-form/creditor-form';

interface CreditorFilter {
  q: string | null;
  status: string | null;
}

@Component({
  selector: 'app-creditor-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    PageHeader,
    StatusChip,
    HasPermission,
    DocumentPipe,
  ],
  template: `
    <app-page-header title="Credores" subtitle="Empresas contratantes cujas carteiras a assessoria administra.">
      <button mat-flat-button type="button" *appHasPermission="permission.CREDITOR_MANAGE" (click)="create()">
        <mat-icon aria-hidden="true">add_business</mat-icon>
        Novo credor
      </button>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar credores" (ngSubmit)="query.reload()">
      <mat-form-field class="app-filters__wide app-compact">
        <mat-label>Nome, documento ou código</mat-label>
        <input matInput formControlName="q" type="search" />
        <mat-icon matSuffix aria-hidden="true">search</mat-icon>
      </mat-form-field>
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
      caption="Credores"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyMessage="Nenhum credor encontrado no seu escopo."
    >
      <ng-template appCell="legalName" [appCellOf]="query.rows()" let-c>
        <a [routerLink]="['/organizacao/credores', c.id]">{{ c.legalName }}</a>
        @if (c.tradeName) {
          <div class="app-muted">{{ c.tradeName }}</div>
        }
      </ng-template>
      <ng-template appCell="document" [appCellOf]="query.rows()" let-c>{{ c.document | document }}</ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-c><app-status-chip domain="creditor" [status]="c.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        <a mat-icon-button [routerLink]="['/organizacao/credores', c.id]" [attr.aria-label]="'Abrir ' + c.legalName" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class CreditorList {
  private readonly api = inject(CreditorsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly permission = Permission;
  protected readonly statuses = statusOptions('creditor');
  protected readonly filters = inject(FormBuilder).nonNullable.group({ q: [''], status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      debounceTime(300),
      map((): CreditorFilter => {
        const v = this.filters.getRawValue();
        return { q: v.q.trim() || null, status: v.status || null };
      }),
    ),
    { initialValue: { q: null, status: null } as CreditorFilter },
  );
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page));

  protected readonly columns: TableColumn[] = [
    { key: 'legalName', header: 'Credor' },
    { key: 'document', header: 'CNPJ/CPF' },
    { key: 'externalCode', header: 'Código externo' },
    { key: 'contactEmail', header: 'E-mail de contato' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected create(): void {
    this.dialog
      .open<CreditorForm, CreditorView | null, CreditorView>(CreditorForm, { data: null, width: '640px' })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/organizacao/credores', created.id]));
  }
}
