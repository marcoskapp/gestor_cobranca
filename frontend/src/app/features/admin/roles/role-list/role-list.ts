import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { RoleView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { RoleForm } from '../role-form/role-form';

@Component({
  selector: 'app-role-list',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Perfis" subtitle="Conjuntos de permissões atribuídos aos usuários da assessoria.">
      <button mat-flat-button type="button" (click)="open(null)">
        <mat-icon aria-hidden="true">add</mat-icon>
        Novo perfil
      </button>
    </app-page-header>

    <app-data-table
      caption="Perfis"
      [rows]="rows()"
      [columns]="columns"
      [loading]="roles.isLoading()"
      [failed]="roles.status() === 'error'"
      (retry)="roles.reload()"
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-r>
        <strong>{{ r.name }}</strong>
        @if (r.systemRole) {
          <span class="app-status app-status--info">Sistema</span>
        }
        @if (r.description) {
          <div class="app-muted">{{ r.description }}</div>
        }
      </ng-template>
      <ng-template appCell="code" [appCellOf]="rows()" let-r><span class="app-mono">{{ r.code }}</span></ng-template>
      <ng-template appCell="permissions" [appCellOf]="rows()" let-r>{{ r.permissions.length }}</ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-r><app-status-chip domain="active" [status]="r.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-r>
        <button mat-icon-button type="button" (click)="open(r)" [attr.aria-label]="'Editar ' + r.name" matTooltip="Editar">
          <mat-icon aria-hidden="true">edit</mat-icon>
        </button>
        @if (r.active) {
          <button
            mat-icon-button
            type="button"
            (click)="setActive(r, false)"
            [disabled]="r.systemRole"
            [attr.aria-label]="'Desativar ' + r.name"
            [matTooltip]="r.systemRole ? 'Perfis de sistema não podem ser desativados' : 'Desativar'"
          >
            <mat-icon aria-hidden="true">block</mat-icon>
          </button>
        } @else {
          <button mat-icon-button type="button" (click)="setActive(r, true)" [attr.aria-label]="'Ativar ' + r.name" matTooltip="Ativar">
            <mat-icon aria-hidden="true">check_circle</mat-icon>
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class RoleList {
  private readonly api = inject(RolesApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly roles = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.roles, []);
  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Perfil' },
    { key: 'code', header: 'Código' },
    { key: 'permissions', header: 'Permissões', align: 'end' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected open(role: RoleView | null): void {
    this.dialog
      .open<RoleForm, RoleView | null, RoleView>(RoleForm, { data: role, width: '880px' })
      .afterClosed()
      .subscribe((saved) => saved && this.roles.reload());
  }

  protected setActive(role: RoleView, active: boolean): void {
    this.confirm
      .confirm({
        title: active ? 'Ativar perfil' : 'Desativar perfil',
        message: active
          ? `Ativar o perfil ${role.name}?`
          : `Usuários com o perfil ${role.name} perderão as permissões dele imediatamente.`,
        confirmLabel: active ? 'Ativar' : 'Desativar',
        destructive: !active,
      })
      .subscribe(() =>
        this.api.setActive(role.id, active).subscribe(() => {
          this.notifications.success(active ? 'Perfil ativado.' : 'Perfil desativado.');
          this.roles.reload();
        }),
      );
  }
}
