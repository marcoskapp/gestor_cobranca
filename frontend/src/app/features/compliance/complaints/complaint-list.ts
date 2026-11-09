import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import {
  CHANNEL_LABELS,
  ComplaintFilter,
  ComplaintView,
  ComplaintsApi,
  STATUS_LABELS,
  SUBJECT_LABELS,
  deadlineText,
  deadlineTone,
} from './complaint.data';
import { ComplaintFormDialog } from './complaint-form';
import {
  COMPLAINT_FORM_DEFAULTS,
  COMPLAINT_URL_CODECS,
  ComplaintListForm,
  OPEN_STATUSES,
  activeComplaintFilters,
  toComplaintFilter,
  unassignedAlias,
} from './complaint.rules';

/**
 * Fila de reclamações (I5): Procon, consumidor.gov.br, Bacen/ouvidoria do credor e Reclame Aqui, do prazo de
 * resposta mais próximo ao mais distante. A operação registra; compliance e supervisão tratam.
 */
@Component({
  selector: 'app-complaint-list',
  imports: [ReactiveFormsModule, RouterLink, CellDef, DataTable, EntityPicker, FilterBar, PageHeader, AppDatePipe, Icon, NgbTooltip, ...FORM_FIELD],
  template: `
    <app-page-header title="Reclamações" subtitle="Procon, consumidor.gov.br, Bacen ou ouvidoria do credor e Reclame Aqui, com prazo de resposta por canal.">
      @if (canManage()) {
        <a class="btn btn-outline-secondary" routerLink="/compliance/reclamacoes/configuracao">
          <app-icon name="sliders" />
          Prazos e indicador
        </a>
      }
      @if (canRegister()) {
        <button class="btn btn-primary" type="button" (click)="register()" data-tour="complaint-new">
          <app-icon name="plus-lg" />
          Registrar reclamação
        </button>
      }
    </app-page-header>

    <section class="cl-kpis" aria-label="Situação das reclamações" data-tour="complaint-kpis">
      @let k = counters.hasValue() ? counters.value() : null;
      <button type="button" class="cl-kpi" (click)="show({ deadline: 'overdue', assignee: '' })">
        <span class="cl-kpi__label">Prazo vencido</span>
        <span class="cl-kpi__value" [class.cl-late]="(k?.overdue ?? 0) > 0">{{ k?.overdue ?? '—' }}</span>
      </button>
      <button type="button" class="cl-kpi" (click)="show({ deadline: 'soon', assignee: '' })">
        <span class="cl-kpi__label">Perto do prazo</span>
        <span class="cl-kpi__value" [class.cl-soon]="(k?.soon ?? 0) > 0">{{ k?.soon ?? '—' }}</span>
      </button>
      <button type="button" class="cl-kpi" (click)="show({ deadline: '', assignee: 'none' })">
        <span class="cl-kpi__label">Sem responsável</span>
        <span class="cl-kpi__value">{{ k?.unassigned ?? '—' }}</span>
      </button>
      @if (canManage()) {
        <button type="button" class="cl-kpi" (click)="show({ deadline: '', assignee: 'me' })">
          <span class="cl-kpi__label">Minhas em aberto</span>
          <span class="cl-kpi__value">{{ k?.mine ?? '—' }}</span>
        </button>
      }
    </section>

    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar reclamações">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Situação">
          <select appInput formControlName="status">
            <option value="OPEN">Em aberto (aguardando resposta)</option>
            <option value="">Todas</option>
            @for (s of statuses; track s.value) {
              <option [ngValue]="s.value">{{ s.label }}</option>
            }
          </select>
        </app-field>
        <app-field label="Canal">
          <select appInput formControlName="channel">
            <option value="">Todos</option>
            @for (c of channels; track c.value) {
              <option [ngValue]="c.value">{{ c.label }}</option>
            }
          </select>
        </app-field>
        <app-field label="Responsável">
          <select appInput formControlName="assignee">
            <option value="">Todos</option>
            @if (canManage()) {
              <option value="me">Minhas</option>
            }
            <option value="none">Sem responsável</option>
          </select>
        </app-field>
        <app-field label="Prazo">
          <select appInput formControlName="deadline">
            <option value="">Qualquer</option>
            <option value="soon">Perto do prazo ou vencidas</option>
            <option value="overdue">Vencidas</option>
            <option value="on_time">No prazo</option>
          </select>
        </app-field>
        <app-field label="Protocolo do canal">
          <input appInput formControlName="protocol" maxlength="60" />
        </app-field>
      </form>
    </app-filter-bar>

    <app-data-table
      caption="Reclamações, do prazo mais próximo ao mais distante"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="megaphone"
      emptyMessage="Nenhuma reclamação encontrada."
    >
      <ng-template appCell="due" [appCellOf]="query.rows()" let-c>
        @let t = tone(c);
        <span [class.cl-late]="t === 'late'" [class.cl-soon]="t === 'soon'">
          @if (c.deadlineState === 'OVERDUE') {
            <app-icon class="cl-icon" name="alarm" />
          }
          {{ deadline(c) }}
        </span>
        <div class="app-muted">prazo {{ c.dueDate | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="channel" [appCellOf]="query.rows()" let-c>
        <a [routerLink]="['/compliance/reclamacoes', c.id]">{{ channelLabels[c.channel] }}</a>
        <div class="app-muted">{{ c.externalProtocol || 'sem protocolo' }} · recebida {{ c.receivedAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="parties" [appCellOf]="query.rows()" let-c>
        {{ creditors.name(c.creditorId) }}
        <div class="app-muted">{{ c.debtorId ? debtors.name(c.debtorId) : 'Sem devedor vinculado' }}</div>
      </ng-template>
      <ng-template appCell="subject" [appCellOf]="query.rows()" let-c>{{ subjectLabels[c.subject] }}</ng-template>
      <ng-template appCell="assignee" [appCellOf]="query.rows()" let-c>
        @if (c.assigneeName) {
          {{ c.assigneeName }}
        } @else {
          <span class="app-muted">Sem responsável</span>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-c>
        <span [class]="statusBadge(c)">{{ statusLabels[c.status] }}</span>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/compliance/reclamacoes', c.id]" aria-label="Abrir reclamação" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .cl-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .cl-kpi {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px 16px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
      &:hover {
        border-color: var(--bs-primary);
      }
    }
    .cl-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: var(--app-weight-semibold);
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .cl-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: var(--app-weight-semibold);
      font-variant-numeric: tabular-nums;
    }
    .cl-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .cl-soon {
      color: var(--app-warning-fg);
    }
    .cl-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      vertical-align: -3px;
    }
  `,
})
export class ComplaintList {
  private readonly api = inject(ComplaintsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly subjectLabels = SUBJECT_LABELS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly statuses = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMPLAINT_MANAGE));
  protected readonly canRegister = computed(() =>
    this.auth.hasAnyPermission([Permission.COMPLAINT_REGISTER, Permission.COMPLAINT_MANAGE]),
  );

  private readonly fb = inject(FormBuilder).nonNullable;
  /** Filtros na URL (`COMPLAINT_URL_CODECS`), compatíveis com os links de "Minhas pendências". */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>(COMPLAINT_FORM_DEFAULTS.creditor),
      status: [COMPLAINT_FORM_DEFAULTS.status],
      channel: [COMPLAINT_FORM_DEFAULTS.channel],
      assignee: [COMPLAINT_FORM_DEFAULTS.assignee],
      deadline: [COMPLAINT_FORM_DEFAULTS.deadline],
      protocol: [COMPLAINT_FORM_DEFAULTS.protocol],
    }),
    COMPLAINT_URL_CODECS,
  );
  private readonly formValue = toSignal(this.filters.valueChanges.pipe(map(() => this.filters.getRawValue())), {
    initialValue: this.filters.getRawValue(),
  });
  protected readonly activeFilters = computed(() => activeComplaintFilters(this.formValue()));
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toComplaintFilter(this.filters.getRawValue())),
    ),
    { initialValue: toComplaintFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page), { url: true });
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((c) => c.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));

  /** Indicadores da fila em aberto (totais do servidor; uma consulta de 1 item por indicador). */
  protected readonly counters = rxResource({
    params: () => this.filter().creditorId ?? '',
    stream: ({ params }) => {
      const total = (f: ComplaintFilter) =>
        this.api.search({ ...f, creditorId: params || null, status: OPEN_STATUSES }, { page: 0, size: 1 }).pipe(
          map((p) => p.totalElements),
          catchError(() => of(null)),
        );
      return forkJoin({
        overdue: total({ deadline: 'overdue' }),
        soon: total({ deadline: 'soon' }),
        unassigned: total({ unassigned: true }),
        mine: this.canManage() ? total({ assignee: 'me' }) : of(null),
      });
    },
  });

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Prazo', mobile: 'meta' },
    { key: 'channel', header: 'Canal', mobile: 'title' },
    { key: 'parties', header: 'Credor e devedor', mobile: 'meta' },
    { key: 'subject', header: 'Assunto', mobile: 'meta' },
    { key: 'assignee', header: 'Responsável', mobile: 'hidden' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    // link de "Minhas pendências": `sem-responsavel=1`
    if (unassignedAlias((p) => this.route.snapshot.queryParamMap.get(p))) {
      this.filters.patchValue({ assignee: 'none' });
    }
  }

  protected deadline(c: ComplaintView): string {
    return deadlineText(c);
  }

  protected tone(c: ComplaintView): 'late' | 'soon' | 'ok' {
    return deadlineTone(c.deadlineState);
  }

  protected statusBadge(c: ComplaintView): string {
    const base = 'badge rounded-pill ';
    if (c.status === 'CLOSED') {
      return base + 'text-bg-secondary';
    }
    if (c.status === 'ANSWERED') {
      return base + 'text-bg-success';
    }
    return base + (c.deadlineState === 'OVERDUE' ? 'text-bg-danger' : 'text-bg-primary');
  }

  protected show(patch: Partial<ComplaintListForm>): void {
    this.filters.patchValue({ status: 'OPEN', ...patch });
  }

  protected register(): void {
    this.dialog
      .open<ComplaintFormDialog, null, ComplaintView>(ComplaintFormDialog, { size: 'lg', data: null })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/compliance/reclamacoes', created.id]));
  }
}
