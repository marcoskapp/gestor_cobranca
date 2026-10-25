import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPlansApi } from '../data/incentive.api';
import { PlanView } from '../data/incentive.models';
import { PlanGroup, groupPlans, versionState } from '../data/incentive.rules';
import { NewPlanDialog, NewPlanDialogData } from './plan-dialogs';

/** Planos de remuneração variável dos negociadores, um por código, com a versão vigente e o rascunho. */
@Component({
  selector: 'app-incentive-plan-list',
  imports: [RouterLink, NgbTooltip, AppDatePipe, CellDef, DataTable, Icon, PageHeader, StatusChip],
  template: `
    <app-page-header title="Planos de remuneração" subtitle="Regras de comissão dos negociadores, versionadas: rascunho, publicação a partir do dia 1º e aposentadoria.">
      <button class="btn btn-primary" type="button" (click)="create()" data-tour="plan-new">
        <app-icon name="plus-lg" />
        Novo plano
      </button>
    </app-page-header>
    <app-data-table
      caption="Planos de remuneração"
      [rows]="rows()"
      [columns]="columns"
      [rowKey]="key"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="sliders"
      emptyMessage="Nenhum plano de remuneração."
      emptyHint="O plano de referência de mercado é criado no provisionamento da assessoria. Crie um plano para começar."
    >
      <ng-template appCell="plan" [appCellOf]="rows()" let-g>
        <strong>{{ g.name }}</strong>
        <div class="app-muted in-small font-monospace">{{ g.code }}</div>
      </ng-template>
      <ng-template appCell="current" [appCellOf]="rows()" let-g>
        @if (g.current; as c) {
          <app-status-chip domain="commissionPlan" status="CURRENT" />
          <div class="in-small">v{{ c.version }} desde {{ c.effectiveFrom | appDate: 'date' }}{{ c.effectiveTo ? ' até ' + (c.effectiveTo | appDate: 'date') : '' }}</div>
        } @else {
          <app-status-chip domain="commissionPlan" [status]="state(g.versions[0])" />
          <div class="app-muted in-small">sem versão vigente hoje</div>
        }
      </ng-template>
      <ng-template appCell="draft" [appCellOf]="rows()" let-g>
        @if (g.draft; as d) {
          <a [routerLink]="['/remuneracao/planos', d.id]">Rascunho v{{ d.version }}</a>
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="versions" [appCellOf]="rows()" let-g>{{ g.versions.length }}</ng-template>
      <ng-template appCell="priority" [appCellOf]="rows()" let-g>{{ g.priority }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-g>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/remuneracao/planos', (g.current ?? g.versions[0]).id]" [attr.aria-label]="'Abrir o plano ' + g.name" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class PlanList {
  private readonly api = inject(CommissionPlansApi);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly today = zonedNow().date;
  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly plans = valueOr(this.list, []);
  protected readonly rows = computed(() => groupPlans(this.plans(), this.today));
  protected readonly key = (g: PlanGroup) => g.code;

  protected readonly columns: TableColumn[] = [
    { key: 'plan', header: 'Plano' },
    { key: 'current', header: 'Versão vigente' },
    { key: 'draft', header: 'Rascunho' },
    { key: 'versions', header: 'Versões', align: 'end' },
    { key: 'priority', header: 'Prioridade', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected state(plan: PlanView): string {
    return versionState(plan, this.today);
  }

  protected create(): void {
    const bases = this.rows()
      .map((g) => g.current)
      .filter((p): p is PlanView => !!p);
    this.dialog
      .open<NewPlanDialog, NewPlanDialogData, PlanView>(NewPlanDialog, { data: { bases }, size: 'lg' })
      .afterClosed()
      .subscribe((p) => {
        if (p) {
          void this.router.navigate(['/remuneracao/planos', p.id, 'regras']);
        }
      });
  }
}
