import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { statusOptions } from '../../../shared/status/status';
import { zonedNow } from '../../collection/data/collection.rules';
import { UserLookup } from '../../admin/data/user.lookup';
import { CommissionStatementsApi } from '../data/incentive.api';
import { StatementFilter } from '../data/incentive.models';
import { monthLabel, monthOptions } from '../data/incentive.rules';

/**
 * Extratos de comissão por período: o negociador vê os próprios; supervisor, os da equipe;
 * gestão e financeiro, todos. Consultar extrato de terceiro gera log de acesso no servidor.
 */
@Component({
  selector: 'app-incentive-statement-list',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, Amount, CellDef, DataTable, EntityPicker, Icon, PageHeader, StatusChip],
  template: `
    <app-page-header title="Extratos de comissão" subtitle="Remuneração variável apurada por período, com memória de cálculo, contestação e aprovação.">
      <a class="btn btn-outline-primary" routerLink="/remuneracao/extratos/previa" data-tour="statement-preview">
        <app-icon name="eye" />
        Prévia do período atual
      </a>
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar extratos">
      <app-field label="Período">
        <select appInput formControlName="period">
          <option value="">Todos</option>
          @for (m of months; track m.value) {
            <option [ngValue]="m.value">{{ m.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      @if (canPickPeople()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.user" [source]="users" label="Negociador" [single]="true" />
        </div>
      }
    </form>
    <app-data-table
      caption="Extratos de comissão"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="receipt"
      emptyMessage="Nenhum extrato encontrado."
      emptyHint="Os extratos são gerados no fechamento do período, depois da carência do último dia. Veja a prévia do período atual."
    >
      <ng-template appCell="period" [appCellOf]="query.rows()" let-s>
        <strong>{{ month(s.period) }}</strong>
        <div class="app-muted in-small">{{ s.userName ?? 'Negociador' }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-s>
        <app-status-chip domain="commissionStatement" [status]="s.status" />
        @if (s.outcome !== 'PAYABLE') {
          <div class="mt-1"><app-status-chip domain="commissionOutcome" [status]="s.outcome" /></div>
        }
      </ng-template>
      <ng-template appCell="plan" [appCellOf]="query.rows()" let-s>{{ s.planCode ? s.planCode + ' v' + s.planVersion : '—' }}</ng-template>
      <ng-template appCell="recovery" [appCellOf]="query.rows()" let-s><app-amount [value]="s.creditedRecovery" /></ng-template>
      <ng-template appCell="payable" [appCellOf]="query.rows()" let-s>
        <app-amount [value]="s.payableAmount" />
        @if (s.adjustmentsAmount !== 0) {
          <div class="app-muted in-small">ajustes <app-amount [value]="s.adjustmentsAmount" [signed]="true" /></div>
        }
        @if (s.withheldAmount > 0) {
          <div class="in-small text-warning-emphasis">retido <app-amount [value]="s.withheldAmount" /></div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/remuneracao/extratos', s.id]" [attr.aria-label]="'Abrir o extrato de ' + month(s.period) + ' de ' + (s.userName ?? 'negociador')" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class StatementList {
  private readonly api = inject(CommissionStatementsApi);
  private readonly auth = inject(AuthService);
  protected readonly users = inject(UserLookup);
  protected readonly statuses = statusOptions('commissionStatement');
  protected readonly months = monthOptions(zonedNow().date, 24);
  protected readonly month = monthLabel;
  /** Filtro por pessoa só para quem vê além de si e pode pesquisar usuários. */
  protected readonly canPickPeople = computed(
    () =>
      this.auth.hasPermission(Permission.USER_VIEW) &&
      this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.COMMISSION_STATEMENT_MANAGE, Permission.COMMISSION_STATEMENT_APPROVE]),
  );

  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    period: [this.params.get('periodo') ?? ''],
    status: [this.params.get('situacao') ?? ''],
    user: this.fb.control<string[]>(this.params.get('negociador') ? [this.params.get('negociador')!] : []),
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Período e negociador' },
    { key: 'status', header: 'Situação' },
    { key: 'plan', header: 'Plano' },
    { key: 'recovery', header: 'Recuperado creditado', align: 'end' },
    { key: 'payable', header: 'A pagar', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  private current(): StatementFilter {
    const v = this.filters.getRawValue();
    return { period: v.period || null, status: v.status || null, userId: v.user[0] ?? null };
  }
}
