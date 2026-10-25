import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
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
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../../shared/dialog/dialog.service';

interface CreditorFilter {
  q: string | null;
  status: string | null;
}

@Component({
  selector: 'app-creditor-list',
  imports: [NgbTooltip, ...FORM_FIELD, Icon,
    ReactiveFormsModule,
    RouterLink,
    DataTable,
    CellDef,
    PageHeader,
    StatusChip,
    HasPermission,
    DocumentPipe,
  ],
  template: `
    <app-page-header title="Credores" subtitle="Empresas contratantes cujas carteiras a assessoria administra.">
      <button class="btn btn-primary" type="button" *appHasPermission="permission.CREDITOR_MANAGE" (click)="create()">
        <app-icon name="shop" />
        Novo credor
      </button>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar credores" (ngSubmit)="query.reload()">
      <app-field class="app-filters__wide" label="Nome, documento ou código">
  <div class="input-group">
    <input appInput formControlName="q" type="search" />
    <span class="input-group-text" aria-hidden="true"><app-icon name="search" /></span>
  </div>
      </app-field>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
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
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/organizacao/credores', c.id]" [attr.aria-label]="'Abrir ' + c.legalName" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class CreditorList {
  private readonly api = inject(CreditorsApi);
  private readonly dialog = inject(DialogService);
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
      .open<CreditorForm, CreditorView | null, CreditorView>(CreditorForm, { data: null, size: 'lg' })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/organizacao/credores', created.id]));
  }
}
