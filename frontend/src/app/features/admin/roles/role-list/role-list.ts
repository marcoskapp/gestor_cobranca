import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
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
import { Icon } from '../../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../../shared/dialog/dialog.service';

@Component({
  selector: 'app-role-list',
  imports: [NgbTooltip, Icon, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Perfis" subtitle="Conjuntos de permissões atribuídos aos usuários da assessoria.">
      <button class="btn btn-primary" type="button" (click)="open(null)">
        <app-icon name="plus-lg" />
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
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="open(r)" [attr.aria-label]="'Editar ' + r.name" ngbTooltip="Editar">
          <app-icon name="pencil" />
        </button>
        @if (r.active) {
          <button class="btn btn-sm btn-outline-secondary border-0"
            type="button"
            (click)="setActive(r, false)"
            [disabled]="r.systemRole"
            [attr.aria-label]="'Desativar ' + r.name"
            [ngbTooltip]="r.systemRole ? 'Perfis de sistema não podem ser desativados' : 'Desativar'"
          >
            <app-icon name="slash-circle" />
          </button>
        } @else {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="setActive(r, true)" [attr.aria-label]="'Ativar ' + r.name" ngbTooltip="Ativar">
            <app-icon name="check-circle" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class RoleList {
  private readonly api = inject(RolesApi);
  private readonly dialog = inject(DialogService);
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
      .open<RoleForm, RoleView | null, RoleView>(RoleForm, { data: role, size: 'lg' })
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
