import { FilterBar } from '../../../../shared/components/filter-bar/filter-bar';
import { activeFilterCount } from '../../../../shared/data/filter-count';
import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, map } from 'rxjs';
import { Permission } from '../../../../core/auth/permission';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { sameValue } from '../../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../../shared/data/url-state';
import { valueOr } from '../../../../shared/data/resource-value';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../../shared/status/status';
import { UserFilter, UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';
import { UserForm, UserFormData } from '../user-form/user-form';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../../shared/dialog/dialog.service';

function toUserFilter(v: { q: string; status: string; roleId: string }): UserFilter {
  return { q: v.q.trim() || null, status: v.status || null, roleId: v.roleId || null };
}

@Component({
  selector: 'app-user-list',
  imports: [FilterBar, NgbTooltip, ...FORM_FIELD, Icon,
    ReactiveFormsModule,
    RouterLink,
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
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);

  protected readonly permission = Permission;
  protected readonly statuses = statusOptions('user');
  /** Situação e perfil na URL (`?situacao=&perfil=`); a busca fica fora dela porque aceita e-mail (dado pessoal). */
  protected readonly filters = syncFormWithUrl(
    inject(FormBuilder).nonNullable.group({
      q: [''],
      status: [''],
      roleId: [''],
    }),
    {
      status: urlParam.choice('situacao', this.statuses.map((s) => s.value)),
      roleId: urlParam.id('perfil'),
    },
  );

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      debounceTime(300),
      map(() => toUserFilter(this.filters.getRawValue())),
    ),
    { initialValue: toUserFilter(this.filters.getRawValue()), equal: sameValue },
  );

  protected readonly query = pagedQuery(this.filter, (filter, page) => this.api.search(filter, page), { url: true });

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
      .open<UserForm, UserFormData, UserView>(UserForm, { data: { user: null } })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          void this.router.navigate(['/admin/usuarios', created.id]);
        }
      });
  }

  /** "Filtros (n)" do `app-filter-bar` no celular (ux.md §12.3). */
  protected readonly activeFilters = activeFilterCount(this.filters);
}
