import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
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
import { valueOr } from '../../../../shared/data/resource-value';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { UserFilter, UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';
import { UserForm, UserFormData } from '../user-form/user-form';

@Component({
  selector: 'app-user-list',
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
    AppDatePipe,
  ],
  templateUrl: './user-list.html',
})
export class UserList {
  private readonly api = inject(UsersApi);
  private readonly rolesApi = inject(RolesApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly permission = Permission;
  protected readonly statuses = statusOptions('user');
  protected readonly filters = inject(FormBuilder).nonNullable.group({
    q: [''],
    status: [''],
    roleId: [''],
  });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      debounceTime(300),
      map((): UserFilter => {
        const v = this.filters.getRawValue();
        return { q: v.q.trim() || null, status: v.status || null, roleId: v.roleId || null };
      }),
    ),
    { initialValue: {} as UserFilter },
  );

  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page));

  protected readonly roles = valueOr(rxResource({ stream: () => this.rolesApi.list() }), []);
  protected readonly roleNames = computed(() => new Map(this.roles().map((r) => [r.code, r.name])));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Nome' },
    { key: 'email', header: 'E-mail' },
    { key: 'roles', header: 'Perfis' },
    { key: 'status', header: 'Situação' },
    { key: 'mfa', header: 'MFA', align: 'center' },
    { key: 'lastLoginAt', header: 'Último acesso' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected rolesOf(user: UserView): string {
    return user.roleCodes.map((code) => this.roleNames().get(code) ?? code).join(', ') || '—';
  }

  protected clearFilters(): void {
    this.filters.reset();
  }

  protected create(): void {
    this.dialog
      .open<UserForm, UserFormData, UserView>(UserForm, { data: { user: null }, width: '640px' })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          void this.router.navigate(['/admin/usuarios', created.id]);
        }
      });
  }
}
