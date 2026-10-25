import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { TeamView } from '../../data/admin.models';
import { TeamsApi } from '../../data/teams.api';
import { UserLookup } from '../../data/user.lookup';
import { TeamForm } from '../team-form/team-form';
import { Icon } from '../../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../../shared/dialog/dialog.service';

@Component({
  selector: 'app-team-list',
  imports: [NgbTooltip, Icon, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Equipes" subtitle="Agrupamento de operadores com supervisor, usado na distribuição de casos.">
      <button class="btn btn-primary" type="button" (click)="open(null)">
        <app-icon name="person-plus" />
        Nova equipe
      </button>
    </app-page-header>
    <app-data-table
      caption="Equipes"
      [rows]="rows()"
      [columns]="columns"
      [loading]="teams.isLoading()"
      [failed]="teams.status() === 'error'"
      (retry)="teams.reload()"
    >
      <ng-template appCell="unit" [appCellOf]="rows()" let-t>{{ unitName(t.businessUnitId) }}</ng-template>
      <ng-template appCell="members" [appCellOf]="rows()" let-t>{{ t.memberIds.length }}</ng-template>
      <ng-template appCell="supervisor" [appCellOf]="rows()" let-t>{{ userName(t.supervisorId) }}</ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-t><app-status-chip domain="active" [status]="t.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-t>
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="open(t)" [attr.aria-label]="'Editar ' + t.name" ngbTooltip="Editar">
          <app-icon name="pencil" />
        </button>
        <button class="btn btn-sm btn-outline-secondary border-0"
          type="button"
          (click)="setActive(t, !t.active)"
          [attr.aria-label]="(t.active ? 'Desativar ' : 'Ativar ') + t.name"
          [ngbTooltip]="t.active ? 'Desativar' : 'Ativar'"
        >
          <app-icon [name]="t.active ? 'slash-circle' : 'check-circle'" />
        </button>
      </ng-template>
    </app-data-table>
  `,
})
export class TeamList {
  private readonly api = inject(TeamsApi);
  private readonly unitsApi = inject(BusinessUnitsApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly teams = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.teams, []);
  private readonly units = valueOr(rxResource({ stream: () => this.unitsApi.list() }), []);
  private readonly userLookup = inject(UserLookup);
  private readonly supervisors = valueOr(
    rxResource({
      params: () => [...new Set(this.rows().flatMap((t) => (t.supervisorId ? [t.supervisorId] : [])))],
      stream: ({ params }) => this.userLookup.resolve(params),
    }),
    [],
  );
  private readonly supervisorNames = computed(() => new Map(this.supervisors().map((o) => [o.value, o.label])));
  private readonly unitNames = computed(() => new Map(this.units().map((u) => [u.id, `${u.code} — ${u.name}`])));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Equipe' },
    { key: 'unit', header: 'Unidade' },
    { key: 'supervisor', header: 'Supervisor' },
    { key: 'members', header: 'Membros', align: 'end' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected userName(id: string | null): string {
    return id ? (this.supervisorNames().get(id) ?? '…') : '—';
  }

  protected unitName(id: string | null): string {
    return id ? (this.unitNames().get(id) ?? '—') : '—';
  }

  protected open(team: TeamView | null): void {
    this.dialog
      .open<TeamForm, TeamView | null, TeamView>(TeamForm, { data: team })
      .afterClosed()
      .subscribe((saved) => saved && this.teams.reload());
  }

  protected setActive(team: TeamView, active: boolean): void {
    this.confirm
      .confirm({
        title: active ? 'Ativar equipe' : 'Desativar equipe',
        message: active ? `Ativar a equipe ${team.name}?` : `A equipe ${team.name} deixará de receber distribuição de casos.`,
        confirmLabel: active ? 'Ativar' : 'Desativar',
        destructive: !active,
      })
      .subscribe(() =>
        this.api.setActive(team.id, active).subscribe(() => {
          this.notifications.success(active ? 'Equipe ativada.' : 'Equipe desativada.');
          this.teams.reload();
        }),
      );
  }
}
