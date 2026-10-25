import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { DocumentPipe } from '../../../../shared/pipes/document.pipe';
import { TenantView } from '../../../organization/data/organization.models';
import { PlatformTenantsApi, ProvisionedTenant } from '../../data/platform.api';
import { TenantProvision } from '../tenant-provision/tenant-provision';
import { Icon } from '../../../../shared/components/icon/icon';
import { DialogService } from '../../../../shared/dialog/dialog.service';

@Component({
  selector: 'app-tenant-list',
  imports: [Icon, DataTable, CellDef, PageHeader, StatusChip, DocumentPipe],
  template: `
    <app-page-header title="Assessorias" subtitle="Clientes da plataforma (tenants), com dados isolados entre si.">
      <button class="btn btn-primary" type="button" (click)="provision()">
        <app-icon name="building-add" />
        Provisionar assessoria
      </button>
    </app-page-header>
    <app-data-table
      caption="Assessorias"
      [rows]="rows()"
      [columns]="columns"
      [loading]="tenants.isLoading()"
      [failed]="tenants.status() === 'error'"
      (retry)="tenants.reload()"
    >
      <ng-template appCell="tradeName" [appCellOf]="rows()" let-t>
        <strong>{{ t.tradeName }}</strong>
        @if (t.platform) {
          <span class="app-status app-status--info">Plataforma</span>
        }
        <div class="app-muted">{{ t.legalName }}</div>
      </ng-template>
      <ng-template appCell="slug" [appCellOf]="rows()" let-t><span class="app-mono">{{ t.slug }}</span></ng-template>
      <ng-template appCell="cnpj" [appCellOf]="rows()" let-t>{{ t.cnpj | document }}</ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-t><app-status-chip domain="tenant" [status]="t.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-t>
        @if (!t.platform) {
          @if (t.status === 'ACTIVE') {
            <button class="btn btn-link" type="button" (click)="suspend(t)">Suspender</button>
          } @else {
            <button class="btn btn-link" type="button" (click)="reactivate(t)">Reativar</button>
          }
        }
      </ng-template>
    </app-data-table>
  `,
})
export class TenantList {
  private readonly api = inject(PlatformTenantsApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly tenants = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.tenants, []);
  protected readonly columns: TableColumn[] = [
    { key: 'tradeName', header: 'Assessoria' },
    { key: 'slug', header: 'Identificador' },
    { key: 'cnpj', header: 'CNPJ' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected provision(): void {
    this.dialog
      .open<TenantProvision, void, ProvisionedTenant>(TenantProvision, { size: 'lg' })
      .afterClosed()
      .subscribe((result) => result && this.tenants.reload());
  }

  protected suspend(tenant: TenantView): void {
    this.confirm
      .confirm({
        title: 'Suspender assessoria',
        message: `Todos os usuários de ${tenant.tradeName} perderão o acesso. Os dados são preservados.`,
        confirmLabel: 'Suspender',
        destructive: true,
        reason: 'required',
      })
      .subscribe((reason) =>
        this.api.suspend(tenant.id, reason).subscribe(() => {
          this.notifications.success('Assessoria suspensa.');
          this.tenants.reload();
        }),
      );
  }

  protected reactivate(tenant: TenantView): void {
    this.confirm
      .confirm({
        title: 'Reativar assessoria',
        message: `Restabelecer o acesso de ${tenant.tradeName}?`,
        confirmLabel: 'Reativar',
        reason: 'required',
      })
      .subscribe((reason) =>
        this.api.reactivate(tenant.id, reason).subscribe(() => {
          this.notifications.success('Assessoria reativada.');
          this.tenants.reload();
        }),
      );
  }
}
