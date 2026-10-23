import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ComponentType } from '@angular/cdk/portal';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { Observable, map } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { SessionTable } from '../../../../shared/components/session-table/session-table';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';
import { UserForm, UserFormData } from '../user-form/user-form';
import { UserRolesDialog } from '../user-roles-dialog/user-roles-dialog';
import { UserScopeDialog } from '../user-scope-dialog/user-scope-dialog';

@Component({
  selector: 'app-user-detail',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDividerModule,
    MatProgressBarModule,
    PageHeader,
    StatusChip,
    SessionTable,
    HasPermission,
    AppDatePipe,
  ],
  templateUrl: './user-detail.html',
})
export class UserDetail {
  readonly id = input.required<string>();

  private readonly api = inject(UsersApi);
  private readonly rolesApi = inject(RolesApi);
  private readonly unitsApi = inject(BusinessUnitsApi);
  private readonly creditorLookup = inject(CreditorLookup);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);

  protected readonly permission = Permission;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.USER_MANAGE));
  protected readonly isSelf = computed(() => this.auth.user()?.id === this.id());

  protected readonly user = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.user, undefined);
  protected readonly sessions = rxResource({
    params: () => (this.canManage() ? this.id() : undefined),
    stream: ({ params }) => this.api.sessions(params),
  });
  protected readonly sessionRows = valueOr(this.sessions, []);
  private readonly roles = valueOr(rxResource({ stream: () => this.rolesApi.list() }), []);
  private readonly units = valueOr(rxResource({ stream: () => this.unitsApi.list() }), []);
  private readonly creditors = valueOr(
    rxResource({
      params: () => {
        const user = this.current();
        return user && !user.allCreditors ? user.creditorIds : undefined;
      },
      stream: ({ params }) => this.creditorLookup.resolve(params),
    }),
    [],
  );

  protected readonly roleNames = computed(() => {
    const byId = new Map(this.roles().map((r) => [r.id, r.name]));
    return (this.current()?.roleIds ?? []).map((id) => byId.get(id) ?? id);
  });
  protected readonly unitName = computed(() => {
    const unitId = this.current()?.businessUnitId;
    const unit = this.units().find((u) => u.id === unitId);
    return unit ? `${unit.code} — ${unit.name}` : '—';
  });
  protected readonly creditorNames = computed(() => this.creditors().map((c) => c.label));

  protected edit(user: UserView): void {
    this.openDialog(UserForm, { user } satisfies UserFormData, '640px');
  }

  protected editRoles(user: UserView): void {
    this.openDialog(UserRolesDialog, user);
  }

  protected editScope(user: UserView): void {
    this.openDialog(UserScopeDialog, user, '640px');
  }

  protected disable(user: UserView): void {
    this.confirm
      .confirm({
        title: 'Desativar usuário',
        message: `${user.name} não poderá mais acessar o sistema e as sessões abertas serão encerradas.`,
        confirmLabel: 'Desativar',
        destructive: true,
        reason: 'required',
      })
      .subscribe((reason) => this.run(this.api.disable(user.id, reason), 'Usuário desativado.'));
  }

  protected enable(user: UserView): void {
    this.confirm
      .confirm({ title: 'Reativar usuário', message: `Reativar o acesso de ${user.name}?`, confirmLabel: 'Reativar' })
      .subscribe(() => this.run(this.api.enable(user.id), 'Usuário reativado.'));
  }

  protected unlock(user: UserView): void {
    this.confirm
      .confirm({
        title: 'Desbloquear usuário',
        message: `Remover o bloqueio por tentativas de acesso de ${user.name}?`,
        confirmLabel: 'Desbloquear',
      })
      .subscribe(() => this.run(this.api.unlock(user.id), 'Usuário desbloqueado.'));
  }

  protected resetMfa(user: UserView): void {
    this.confirm
      .confirm({
        title: 'Redefinir MFA',
        message: `O autenticador atual de ${user.name} deixará de funcionar. No próximo acesso, um novo cadastro será exigido se o perfil requerer MFA.`,
        confirmLabel: 'Redefinir MFA',
        destructive: true,
        reason: 'required',
      })
      .subscribe((reason) => this.run(this.api.resetMfa(user.id, reason), 'MFA redefinido.'));
  }

  protected sendLink(user: UserView): void {
    const activation = user.status === 'PENDING_ACTIVATION';
    this.confirm
      .confirm({
        title: activation ? 'Reenviar link de ativação' : 'Enviar link de redefinição de senha',
        message: `Um novo link será enviado para ${user.email}. Links anteriores deixam de valer.`,
        confirmLabel: 'Enviar',
      })
      .subscribe(() =>
        this.run(this.api.sendCredentialLink(user.id).pipe(map(() => undefined)), 'Link enviado por e-mail.'),
      );
  }

  protected revokeSessions(user: UserView): void {
    this.confirm
      .confirm({
        title: 'Encerrar todas as sessões',
        message: `Todas as sessões ativas de ${user.name} serão encerradas imediatamente.`,
        confirmLabel: 'Encerrar sessões',
        destructive: true,
        reason: 'required',
      })
      .subscribe((reason) =>
        this.api.revokeSessions(user.id, reason).subscribe((count) => {
          this.notifications.success(count === 1 ? '1 sessão encerrada.' : `${count} sessões encerradas.`);
          this.sessions.reload();
        }),
      );
  }

  private openDialog<C, D>(component: ComponentType<C>, data: D, width = '560px'): void {
    this.dialog
      .open<C, D, UserView>(component, { data, width })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.user.set(updated);
        }
      });
  }

  private run(request: Observable<UserView | undefined>, message: string): void {
    request.subscribe((updated) => {
      this.notifications.success(message);
      if (updated) {
        this.user.set(updated);
      }
      if (this.canManage()) {
        this.sessions.reload();
      }
    });
  }
}

