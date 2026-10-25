import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { statusOptions } from '../../../shared/status/status';
import { UserLookup } from '../../admin/data/user.lookup';
import { OverdueBadge } from '../../debt/components/overdue-badge';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { CasesApi } from '../data/collection.api';
import { OWNER_LABELS, eligibilityText } from '../data/collection.labels';
import { QueueNames, UserNames } from '../data/collection.lookups';
import { CaseFilter, CaseView, OwnerType } from '../data/collection.models';
import { nextActionText } from '../data/collection.rules';
import { AssignDialog, AssignDialogData } from './assign-dialog';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DialogService } from '../../../shared/dialog/dialog.service';

interface CaseFormValue {
  creditor: string[];
  status: string;
  queueId: string;
  assignee: string;
  user: string[];
  ownerType: string;
  eligible: string;
}

/** Filtro da consulta: situações ativas por padrão; responsável `me`, operador escolhido ou qualquer. */
export function toCaseFilter(v: CaseFormValue): CaseFilter {
  return {
    creditorId: v.creditor[0] ?? null,
    status: v.status === 'ACTIVE' ? 'OPEN,IN_NEGOTIATION,PROMISE,AGREEMENT,DISPUTED,SUSPENDED,LEGAL' : v.status || null,
    queueId: v.queueId || null,
    assignee: v.assignee === 'me' ? 'me' : v.assignee === 'user' ? (v.user[0] ?? null) : null,
    ownerType: (v.ownerType || null) as OwnerType | null,
    eligible: v.eligible === '' ? null : v.eligible === 'true',
  };
}

/** Consulta de casos para supervisão: filtros por credor, fila, responsável e elegibilidade, com atribuição manual. */
@Component({
  selector: 'app-case-list',
  imports: [ReactiveFormsModule, RouterLink, Amount, CellDef, DataTable, EntityPicker, OverdueBadge, PageHeader, StatusChip, Icon, NgbTooltip, ...FORM_FIELD],
  template: `
    <app-page-header title="Casos de cobrança" subtitle="Casos por devedor e carteira, com situação, elegibilidade e responsável pelo atendimento.">
      @if (canQueues()) {
        <a class="btn btn-outline-primary" routerLink="/cobranca/filas">
          <app-icon name="view-list" />
          Filas e distribuição
        </a>
      }
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar casos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="ACTIVE">Ativos</option>
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Fila">
        <select appInput formControlName="queueId">
          <option value="">Todas</option>
          @for (q of queues(); track q.value) {
            <option [ngValue]="q.value">{{ q.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Atendimento">
        <select appInput formControlName="ownerType">
          <option value="">Qualquer</option>
          @for (o of owners; track o.value) {
            <option [ngValue]="o.value">{{ o.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Responsável">
        <select appInput formControlName="assignee">
          <option value="">Qualquer</option>
          <option value="me">Eu</option>
          @if (canPickUser()) {
            <option value="user">Escolher operador…</option>
          }
        </select>
      </app-field>
      @if (filters.controls.assignee.value === 'user') {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.user" [source]="users" label="Operador" [single]="true" />
        </div>
      }
      <app-field label="Elegibilidade">
        <select appInput formControlName="eligible">
          <option value="">Todos</option>
          <option value="true">Elegíveis</option>
          <option value="false">Inelegíveis</option>
        </select>
      </app-field>
    </form>

    <app-data-table
      caption="Casos de cobrança"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="folder-x"
      emptyMessage="Nenhum caso encontrado."
      emptyHint="Casos são abertos automaticamente quando a carteira recebe títulos em aberto."
    >
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-c>
        <a [routerLink]="['/cobranca/casos', c.id]">{{ debtors.name(c.debtorId) }}</a>
        <div class="app-muted">{{ creditors.name(c.creditorId) }} · {{ portfolios.name(c.portfolioId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-c>
        <app-status-chip domain="case" [status]="c.status" />
        @if (!c.eligible) {
          <div class="app-muted" [ngbTooltip]="reasons(c)">Inelegível</div>
        }
      </ng-template>
      <ng-template appCell="balance" [appCellOf]="query.rows()" let-c><app-amount [value]="c.openBalance" /></ng-template>
      <ng-template appCell="overdue" [appCellOf]="query.rows()" let-c><app-overdue-badge [days]="c.daysOverdue" /></ng-template>
      <ng-template appCell="owner" [appCellOf]="query.rows()" let-c>
        {{ c.assigneeUserId ? (canPickUser() ? assignees.name(c.assigneeUserId) : 'Operador') : ownerLabels[c.ownerType] }}
        @if (c.queueId) {
          <div class="app-muted">{{ queueNames.name(c.queueId) }}</div>
        }
      </ng-template>
      <ng-template appCell="next" [appCellOf]="query.rows()" let-c>{{ nextActionText(c.nextActionAt) ?? '—' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        @if (canAssign() && c.status !== 'CLOSED') {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="assign(c)" aria-label="Atribuir caso" ngbTooltip="Atribuir">
            <app-icon name="person-badge" />
          </button>
        }
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/cobranca/casos', c.id]" aria-label="Abrir caso" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class CaseList {
  private readonly api = inject(CasesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly users = inject(UserLookup);
  private readonly queueLookup = inject(QueueNames);

  protected readonly statuses = statusOptions('case');
  protected readonly owners = Object.entries(OWNER_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly ownerLabels = OWNER_LABELS;
  protected readonly nextActionText = nextActionText;
  protected readonly canAssign = computed(() => this.auth.hasPermission(Permission.CASE_ASSIGN));
  protected readonly canPickUser = computed(() => this.auth.hasPermission(Permission.USER_VIEW));
  protected readonly canQueues = computed(() => this.auth.hasAnyPermission([Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN]));

  private readonly queueList = rxResource({ stream: () => this.queueLookup.all() });
  protected readonly queues = valueOr(this.queueList, []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    status: ['ACTIVE'],
    queueId: [''],
    assignee: [''],
    user: this.fb.control<string[]>([]),
    ownerType: [''],
    eligible: [''],
  });

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toCaseFilter(this.filters.getRawValue())),
    ),
    { initialValue: toCaseFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));

  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((c) => c.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.query.rows().map((c) => c.portfolioId));
  protected readonly queueNames = nameMap(this.queueLookup, () => this.query.rows().map((c) => c.queueId));
  protected readonly assignees = nameMap(inject(UserNames), () => (this.canPickUser() ? this.query.rows().map((c) => c.assigneeUserId) : []));

  protected readonly columns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
    { key: 'balance', header: 'Saldo', align: 'end' },
    { key: 'overdue', header: 'Atraso' },
    { key: 'owner', header: 'Atendimento' },
    { key: 'next', header: 'Próxima ação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected reasons(c: CaseView): string {
    return c.eligibilityReasons.map(eligibilityText).join(' · ');
  }

  protected assign(c: CaseView): void {
    this.dialog
      .open<AssignDialog, AssignDialogData, CaseView>(AssignDialog, { data: { caseView: c, debtorName: this.debtors.name(c.debtorId) } })
      .afterClosed()
      .subscribe((saved) => saved && this.query.reload());
  }
}
