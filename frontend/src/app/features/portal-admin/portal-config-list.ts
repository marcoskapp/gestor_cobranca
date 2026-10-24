import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { nameMap } from '../../shared/data/name-map';
import { valueOr } from '../../shared/data/resource-value';
import { CreditorNames } from '../organization/data/organization.lookups';
import { PortalAdminApi } from './data/portal-admin.api';

/** Portais de autonegociação por credor (`PORTAL_MANAGE`). */
@Component({
  selector: 'app-portal-config-list',
  imports: [RouterLink, MatButtonModule, MatIconModule, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Portal de autonegociação" subtitle="Endereço, marca, carteiras e segurança do portal de cada credor.">
      <a mat-flat-button routerLink="novo"><mat-icon aria-hidden="true">add</mat-icon>Configurar portal</a>
    </app-page-header>
    <app-data-table
      caption="Portais configurados"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="storefront"
      emptyMessage="Nenhum portal configurado."
      emptyHint="Configure o portal de um credor para que os devedores negociem sozinhos."
    >
      <ng-template appCell="displayName" [appCellOf]="rows()" let-r>
        <a [routerLink]="r.id">{{ r.displayName }}</a>
        <div class="app-muted">{{ creditors.name(r.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="slug" [appCellOf]="rows()" let-r><code>/portal/{{ r.slug }}</code></ng-template>
      <ng-template appCell="portfolios" [appCellOf]="rows()" let-r>{{ r.portfolioIds.length }}</ng-template>
      <ng-template appCell="enabled" [appCellOf]="rows()" let-r><app-status-chip domain="active" [status]="r.enabled" /></ng-template>
    </app-data-table>
  `,
})
export class PortalConfigList {
  private readonly api = inject(PortalAdminApi);
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.list, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));
  protected readonly columns: TableColumn[] = [
    { key: 'displayName', header: 'Portal' },
    { key: 'slug', header: 'Endereço' },
    { key: 'portfolios', header: 'Carteiras habilitadas', align: 'end' },
    { key: 'enabled', header: 'Situação' },
  ];
  protected readonly total = computed(() => this.rows().length);
}
