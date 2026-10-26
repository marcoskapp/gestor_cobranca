import { Component, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPlansApi } from '../data/incentive.api';
import { GoalView } from '../data/incentive.models';
import { monthLabel, monthOptions } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { GoalDialog, GoalDialogData } from './goal-dialog';
import { GOAL_UNIT_LABELS, goalTargetText } from './goal-units';

/** Metas por período (individuais e de equipe), que sobrepõem a meta padrão do plano. */
@Component({
  selector: 'app-incentive-goal-list',
  imports: [ReactiveFormsModule, NgbTooltip, ...FORM_FIELD, CellDef, DataTable, Icon, PageHeader],
  template: `
    <app-page-header title="Metas por período" subtitle="Meta individual ou da equipe no mês; sem meta cadastrada, vale a meta padrão do plano (se houver).">
      <button class="btn btn-primary" type="button" (click)="edit()" data-tour="goal-new">
        <app-icon name="plus-lg" />
        Definir meta
      </button>
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="form" aria-label="Período das metas">
      <app-field label="Período">
        <select appInput formControlName="period">
          @for (m of months; track m.value) {
            <option [ngValue]="m.value">{{ m.label }}</option>
          }
        </select>
      </app-field>
    </form>
    @if (error()) {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
    }
    <app-data-table
      [caption]="'Metas de ' + month(period())"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="flag"
      emptyMessage="Nenhuma meta cadastrada para o período."
      emptyHint="Sem meta cadastrada, vale a meta padrão do plano; se o plano não tiver, o componente de meta não se aplica."
    >
      <ng-template appCell="subject" [appCellOf]="rows()" let-g>
        <strong>{{ subject(g) }}</strong>
        <div class="app-muted in-small">{{ g.subjectType === 'USER' ? 'Negociador' : 'Equipe' }}</div>
      </ng-template>
      <ng-template appCell="target" [appCellOf]="rows()" let-g>
        <span class="app-numeric">{{ target(g) }}</span>
        @if (!g.unit) {
          <div class="app-muted in-small">Unidade da métrica do plano</div>
        }
      </ng-template>
      <ng-template appCell="unit" [appCellOf]="rows()" let-g>{{ unitLabel(g) }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-g>
        <button type="button" class="btn btn-sm btn-outline-secondary border-0" (click)="edit(g)" [attr.aria-label]="'Alterar a meta de ' + subject(g)" ngbTooltip="Alterar">
          <app-icon name="pencil" />
        </button>
        <button type="button" class="btn btn-sm btn-outline-danger border-0" (click)="remove(g)" [disabled]="busy()" [attr.aria-label]="'Remover a meta de ' + subject(g)" ngbTooltip="Remover">
          <app-icon name="trash3" />
        </button>
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class GoalList {
  private readonly api = inject(CommissionPlansApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly months = monthOptions(zonedNow().date, 6, 3);
  protected readonly month = monthLabel;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ period: [this.months[3].value] });
  protected readonly period = toSignal(this.form.controls.period.valueChanges.pipe(startWith(this.form.controls.period.value)), { initialValue: this.months[3].value });
  protected readonly list = rxResource({ params: () => this.period(), stream: ({ params }) => this.api.goals(params) });
  protected readonly rows = valueOr(this.list, []);
  protected readonly users = nameMap(inject(UserNames), () => this.rows().filter((g) => g.subjectType === 'USER').map((g) => g.subjectId), { batch: USER_NAMES_BATCH });
  protected readonly teams = nameMap(inject(TeamLookup), () => this.rows().filter((g) => g.subjectType === 'TEAM').map((g) => g.subjectId));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly columns: TableColumn[] = [
    { key: 'subject', header: 'Negociador ou equipe' },
    { key: 'unit', header: 'Unidade' },
    { key: 'target', header: 'Meta', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected readonly subject = (g: GoalView) => (g.subjectType === 'USER' ? this.users.name(g.subjectId) : this.teams.name(g.subjectId));
  protected unitLabel(g: GoalView): string {
    return g.unit ? GOAL_UNIT_LABELS[g.unit] : 'Não informada';
  }
  protected target(g: GoalView): string {
    return goalTargetText(g.target, g.unit);
  }

  protected edit(goal?: GoalView): void {
    this.dialog
      .open<GoalDialog, GoalDialogData, GoalView>(GoalDialog, { data: { period: this.period(), goal, subjectName: goal ? this.subject(goal) : undefined } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Meta salva.');
          this.list.reload();
        }
      });
  }

  protected remove(g: GoalView): void {
    this.confirm
      .confirm({
        title: 'Remover meta',
        message: 'Sem a meta cadastrada, passa a valer a meta padrão do plano (se houver). A remoção fica na trilha de auditoria.',
        confirmLabel: 'Remover',
        destructive: true,
        details: [
          { label: g.subjectType === 'USER' ? 'Negociador' : 'Equipe', value: this.subject(g) },
          { label: 'Período', value: monthLabel(g.period) },
          { label: 'Meta', value: goalTargetText(g.target, g.unit) },
        ],
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .removeGoal(g.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Meta removida.');
              this.list.reload();
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }
}
