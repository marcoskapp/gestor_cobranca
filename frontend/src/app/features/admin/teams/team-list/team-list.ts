import { Component, computed, inject } from '@angular/core';
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
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { TeamView } from '../../data/admin.models';
import { TeamsApi } from '../../data/teams.api';
import { UserLookup } from '../../data/user.lookup';
import { TeamForm } from '../team-form/team-form';

@Component({
  selector: 'app-team-list',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTable, CellDef, PageHeader, StatusChip],
  template: `
    <app-page-header title="Equipes" subtitle="Agrupamento de operadores com supervisor, usado na distribuição de casos.">
      <button mat-flat-button type="button" (click)="open(null)">
        <mat-icon aria-hidden="true">group_add</mat-icon>
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
        <button mat-icon-button type="button" (click)="open(t)" [attr.aria-label]="'Editar ' + t.name" matTooltip="Editar">
          <mat-icon aria-hidden="true">edit</mat-icon>
        </button>
        <button
          mat-icon-button
          type="button"
          (click)="setActive(t, !t.active)"
          [attr.aria-label]="(t.active ? 'Desativar ' : 'Ativar ') + t.name"
          [matTooltip]="t.active ? 'Desativar' : 'Ativar'"
        >
          <mat-icon aria-hidden="true">{{ t.active ? 'block' : 'check_circle' }}</mat-icon>
        </button>
      </ng-template>
    </app-data-table>
  `,
})
export class TeamList {
  private readonly api = inject(TeamsApi);
  private readonly unitsApi = inject(BusinessUnitsApi);
  private readonly dialog = inject(MatDialog);
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
      .open<TeamForm, TeamView | null, TeamView>(TeamForm, { data: team, width: '640px' })
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
