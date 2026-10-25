import { Component, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { UserLookup } from '../../admin/data/user.lookup';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPlansApi } from '../data/incentive.api';
import { GoalSubjectType, GoalView, YearMonth } from '../data/incentive.models';
import { decimalInput, monthLabel, monthOptions, parseDecimal } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';

const NUMBER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 4 });

export interface GoalDialogData {
  period: YearMonth;
  goal?: GoalView;
  subjectName?: string;
}

/** Define (ou altera) a meta de um negociador ou equipe no período. */
@Component({
  selector: 'app-incentive-goal-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.goal ? 'Alterar meta' : 'Definir meta' }} · {{ month }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">A meta do período prevalece sobre a meta padrão do plano. A unidade segue a métrica da meta do plano: valor recuperado em reais ou taxa de recuperação em %.</p>
        @if (data.goal) {
          <dl class="app-definition-list mb-3">
            <dt>{{ data.goal.subjectType === 'USER' ? 'Negociador' : 'Equipe' }}</dt>
            <dd>{{ data.subjectName }}</dd>
          </dl>
        } @else {
          <fieldset class="mb-3">
            <legend class="form-label">Meta de</legend>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="gd-user" value="USER" formControlName="subjectType" />
              <label class="form-check-label" for="gd-user">Negociador</label>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="gd-team" value="TEAM" formControlName="subjectType" />
              <label class="form-check-label" for="gd-team">Equipe</label>
            </div>
          </fieldset>
          @if (type() === 'USER') {
            <app-entity-picker class="d-block mb-3" [control]="form.controls.user" [source]="users" label="Negociador" [single]="true" />
          } @else {
            <app-entity-picker class="d-block mb-3" [control]="form.controls.team" [source]="teams" label="Equipe" [single]="true" />
          }
        }
        <app-field label="Meta" hint="R$ ou %, conforme a métrica do plano" error="Informe um número positivo.">
          <input appInput ngbAutofocus formControlName="target" inputmode="decimal" autocomplete="off" />
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Salvar meta</button>
      </div>
    </form>
  `,
})
export class GoalDialog {
  protected readonly data = inject<GoalDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<GoalView>>(DialogRef);
  private readonly api = inject(CommissionPlansApi);
  protected readonly users = inject(UserLookup);
  protected readonly teams = inject(TeamLookup);
  protected readonly month = monthLabel(this.data.period);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    subjectType: this.fb.control<GoalSubjectType>(this.data.goal?.subjectType ?? 'USER'),
    user: this.fb.control<string[]>([]),
    team: this.fb.control<string[]>([]),
    target: [decimalInput(this.data.goal?.target), [Validators.required, (c: AbstractControl) => ((parseDecimal(c.value as string) ?? 0) > 0 ? null : { min: { min: 0 } })]],
  });
  protected readonly type = toSignal(this.form.controls.subjectType.valueChanges.pipe(startWith(this.form.controls.subjectType.value)), { initialValue: 'USER' as GoalSubjectType });

  protected save(): void {
    const v = this.form.getRawValue();
    const subjectId = this.data.goal?.subjectId ?? (v.subjectType === 'USER' ? v.user[0] : v.team[0]);
    if (this.form.invalid || !subjectId || this.sending()) {
      this.form.markAllAsTouched();
      if (!subjectId) {
        this.error.set(v.subjectType === 'USER' ? 'Escolha o negociador.' : 'Escolha a equipe.');
      }
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .setGoal({ period: this.data.period, subjectType: this.data.goal?.subjectType ?? v.subjectType, subjectId, target: parseDecimal(v.target)! })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (g) => this.ref.close(g), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

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
      <ng-template appCell="target" [appCellOf]="rows()" let-g><span class="app-numeric">{{ number(g.target) }}</span></ng-template>
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
    { key: 'target', header: 'Meta', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected readonly subject = (g: GoalView) => (g.subjectType === 'USER' ? this.users.name(g.subjectId) : this.teams.name(g.subjectId));
  protected number(v: number): string {
    return NUMBER.format(v);
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
          { label: 'Meta', value: NUMBER.format(g.target) },
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
