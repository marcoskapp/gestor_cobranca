import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { catchError, debounceTime, forkJoin, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { dueText } from '../../compliance/data/compliance.labels';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { DisputesApi } from '../data/collection.api';
import { DISPUTE_ORIGIN_LABELS, DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { DisputeFilter, DisputeView } from '../data/collection.models';
import { daysBetween, zonedNow } from '../data/collection.rules';
import { DISPUTE_FORM_DEFAULTS, DISPUTE_URL_CODECS, activeDisputeFilters, canWorkDispute, dueTone, isPending, nearestDue, toDisputeFilter } from './dispute.rules';

export { toDisputeFilter } from './dispute.rules';

/**
 * Fila de contestações (F21): prazo mais próximo (interno ou de resposta ao devedor) em destaque, responsável,
 * aguardando o credor, resposta ao devedor e ajuste pendentes; "pegar para mim" na linha. O detalhe abre a página da
 * contestação com o pedido ao credor, a resolução e a resposta ao devedor.
 */
@Component({
  selector: 'app-dispute-list',
  imports: [ReactiveFormsModule, RouterLink, CellDef, DataTable, EntityPicker, FilterBar, PageHeader, StatusChip, AppDatePipe, Icon, NgbTooltip, ...FORM_FIELD],
  template: `
    <app-page-header title="Contestações" subtitle="Dívidas contestadas pelo devedor, credor ou jurídico. Contestação bloqueante suspende cobrança e contato até a resolução.">
      @if (canManage()) {
        <a class="btn btn-outline-secondary" routerLink="/cobranca/contestacoes/configuracao" data-tour="dispute-config-link">
          <app-icon name="sliders" />
          Prazos, distribuição e modelos
        </a>
      }
    </app-page-header>

    <section class="dl-kpis" aria-label="Situação das contestações" data-tour="dispute-kpis">
      @let k = counters.hasValue() ? counters.value() : null;
      <button type="button" class="dl-kpi" (click)="show({ status: 'PENDING', due: 'overdue', assignee: '' })">
        <span class="dl-kpi__label">Prazo de resposta vencido</span>
        <span class="dl-kpi__value" [class.dl-late]="(k?.overdue ?? 0) > 0">{{ k?.overdue ?? '—' }}</span>
      </button>
      @if (canWork()) {
        <button type="button" class="dl-kpi" (click)="show({ status: 'PENDING', due: '', assignee: 'me' })" data-tour="dispute-mine">
          <span class="dl-kpi__label">Minhas em aberto</span>
          <span class="dl-kpi__value">{{ k?.mine ?? '—' }}</span>
        </button>
      }
      <button type="button" class="dl-kpi" (click)="show({ status: 'PENDING', due: '', assignee: 'none' })">
        <span class="dl-kpi__label">Sem responsável</span>
        <span class="dl-kpi__value" [class.dl-soon]="(k?.unassigned ?? 0) > 0">{{ k?.unassigned ?? '—' }}</span>
      </button>
      <button type="button" class="dl-kpi" (click)="show({ status: 'PENDING', due: '', assignee: '', awaiting: true })">
        <span class="dl-kpi__label">Aguardando o credor</span>
        <span class="dl-kpi__value">{{ k?.awaiting ?? '—' }}</span>
      </button>
      <button type="button" class="dl-kpi" (click)="show({ status: 'RESOLVED', due: '', assignee: '', noticePending: true })">
        <span class="dl-kpi__label">Resposta ao devedor pendente</span>
        <span class="dl-kpi__value">{{ k?.notice ?? '—' }}</span>
      </button>
    </section>

    <app-filter-bar [activeCount]="activeFilters()">
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar contestações">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="PENDING">Pendentes (abertas e em análise)</option>
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Motivo">
        <select appInput formControlName="type">
          <option value="">Todos</option>
          @for (t of typeOptions; track t.value) {
            <option [ngValue]="t.value">{{ t.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Responsável">
        <select appInput formControlName="assignee">
          <option value="">Todos</option>
          @if (canWork()) {
            <option value="me">Minhas</option>
          }
          <option value="none">Sem responsável</option>
          @if (canManage()) {
            <option value="supervision">Sem responsável ou vencidas</option>
          }
        </select>
      </app-field>
      <app-field label="Prazo">
        <select appInput formControlName="due">
          <option value="">Qualquer</option>
          <option value="overdue">Resposta vencida</option>
          <option value="week">Vence em até 7 dias</option>
        </select>
      </app-field>
      <div class="dl-toggles">
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="dl-awaiting" formControlName="awaiting" />
          <label class="form-check-label" for="dl-awaiting">Aguardando o credor</label>
        </div>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="dl-notice" formControlName="noticePending" />
          <label class="form-check-label" for="dl-notice">Resposta ao devedor pendente</label>
        </div>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="dl-adjustment" formControlName="adjustmentPending" />
          <label class="form-check-label" for="dl-adjustment">Ajuste no título pendente</label>
        </div>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="dl-mail" formControlName="mailReplyPending" />
          <label class="form-check-label" for="dl-mail">Resposta por e-mail a confirmar</label>
        </div>
      </div>
    </form>
    </app-filter-bar>

    <app-data-table
      caption="Contestações, do prazo mais próximo ao mais distante"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="hammer"
      emptyMessage="Nenhuma contestação encontrada."
    >
      <ng-template appCell="due" [appCellOf]="query.rows()" let-d>
        @if (pending(d)) {
          @let dueTone = tone(d);
          <span [class.dl-late]="dueTone === 'late'" [class.dl-soon]="dueTone === 'soon'">
            @if (dueTone === 'late') {
              <app-icon class="dl-icon" name="alarm" />
            }
            {{ due(d) }}
          </span>
        }
        <div class="app-muted">resposta {{ d.responseDueDate | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-d>
        <a [routerLink]="['/cobranca/contestacoes', d.id]">{{ types[d.type] }}</a>
        <div class="app-muted">{{ origins[d.origin] }} · {{ d.openedAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-d>
        <a [routerLink]="['/cobranca/casos', d.caseId]">{{ debtors.name(d.debtorId) }}</a>
        <div class="app-muted">{{ creditors.name(d.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="assignee" [appCellOf]="query.rows()" let-d>
        @if (d.assigneeName) {
          {{ d.assigneeName }}
        } @else if (pending(d) && canTake(d)) {
          <button class="btn btn-sm btn-outline-primary" type="button" (click)="take(d)" [attr.aria-label]="'Pegar para mim a contestação de ' + debtors.name(d.debtorId)">Pegar para mim</button>
        } @else {
          <span class="app-muted">Sem responsável</span>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d>
        <app-status-chip domain="dispute" [status]="d.status" />
        @if (d.awaitingCreditorSince) {
          <div class="app-muted">aguardando o credor</div>
        } @else if (d.blocksCollection && pending(d)) {
          <div class="app-muted">cobrança suspensa</div>
        }
        @if (d.debtorNoticeStatus === 'PENDING') {
          <div class="dl-soon">resposta ao devedor pendente</div>
        }
        @if (d.adjustmentStatus === 'PENDING') {
          <div class="dl-soon">ajuste no título pendente</div>
        }
      </ng-template>
      <ng-template appCell="evidence" [appCellOf]="query.rows()" let-d><span class="app-numeric">{{ d.evidenceCount }}</span></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/cobranca/contestacoes', d.id]" aria-label="Abrir contestação" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dl-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .dl-kpi {
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
    .dl-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: var(--app-weight-semibold);
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .dl-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: var(--app-weight-semibold);
      font-variant-numeric: tabular-nums;
    }
    .dl-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
    .dl-soon {
      color: var(--app-warning-fg);
    }
    .dl-toggles {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      align-self: center;
    }
    .dl-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      vertical-align: -3px;
    }
  `,
})
export class DisputeList {
  private readonly api = inject(DisputesApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly types = DISPUTE_TYPE_LABELS;
  protected readonly origins = DISPUTE_ORIGIN_LABELS;
  protected readonly statuses = statusOptions('dispute');
  protected readonly typeOptions = Object.entries(DISPUTE_TYPE_LABELS).map(([value, label]) => ({ value, label }));
  private readonly today = zonedNow().date;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DISPUTE_MANAGE));
  protected readonly canWork = computed(() => this.auth.hasAnyPermission([Permission.DISPUTE_MANAGE, Permission.DISPUTE_WORK]));
  private readonly userId = computed(() => this.auth.user()?.id ?? null);

  private readonly fb = inject(FormBuilder).nonNullable;
  /**
   * Filtros na URL (`DISPUTE_URL_CODECS`), preservados ao voltar; "Minhas pendências" (servidor) já gera esses parâmetros.
   */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>(DISPUTE_FORM_DEFAULTS.creditor),
      status: [DISPUTE_FORM_DEFAULTS.status],
      type: [DISPUTE_FORM_DEFAULTS.type],
      assignee: [DISPUTE_FORM_DEFAULTS.assignee],
      due: [DISPUTE_FORM_DEFAULTS.due],
      awaiting: [DISPUTE_FORM_DEFAULTS.awaiting],
      noticePending: [DISPUTE_FORM_DEFAULTS.noticePending],
      adjustmentPending: [DISPUTE_FORM_DEFAULTS.adjustmentPending],
      mailReplyPending: [DISPUTE_FORM_DEFAULTS.mailReplyPending],
    }),
    DISPUTE_URL_CODECS,
  );
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toDisputeFilter(this.filters.getRawValue(), this.today)),
    ),
    { initialValue: toDisputeFilter(this.filters.getRawValue(), this.today), equal: sameValue },
  );
  /** Filtros aplicados (botão "Filtros (n)" no celular). */
  private readonly formValue = toSignal(this.filters.valueChanges.pipe(map(() => this.filters.getRawValue())), {
    initialValue: this.filters.getRawValue(),
  });
  protected readonly activeFilters = computed(() => activeDisputeFilters(this.formValue()));
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page), { url: true });
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((d) => d.debtorId));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((d) => d.creditorId));

  /** Indicadores (totais do servidor; uma consulta de 1 item por indicador). */
  protected readonly counters = rxResource({
    params: () => this.filter().creditorId ?? '',
    stream: ({ params }) => {
      const total = (f: DisputeFilter) =>
        this.api.search({ ...f, creditorId: params || null }, { page: 0, size: 1 }).pipe(
          map((p) => p.totalElements),
          catchError(() => of(null)),
        );
      const pending = 'OPEN,UNDER_REVIEW';
      return forkJoin({
        overdue: total({ status: pending, overdue: true }),
        mine: this.canWork() ? total({ status: pending, assignee: 'me' }) : of(null),
        unassigned: total({ status: pending, unassigned: true }),
        awaiting: total({ status: pending, awaitingCreditor: true }),
        notice: total({ noticePending: true }),
      });
    },
  });

  protected readonly columns: TableColumn[] = [
    { key: 'due', header: 'Prazo', mobile: 'meta' },
    { key: 'type', header: 'Motivo', mobile: 'title' },
    { key: 'debtor', header: 'Devedor', mobile: 'meta' },
    { key: 'assignee', header: 'Responsável', mobile: 'meta' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'evidence', header: 'Evidências', align: 'end', mobile: 'hidden' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected pending(d: DisputeView): boolean {
    return isPending(d);
  }

  protected tone(d: DisputeView) {
    return dueTone(d, this.today);
  }

  protected due(d: DisputeView): string {
    return dueText(daysBetween(this.today, nearestDue(d)), d.overdue);
  }

  protected canTake(d: DisputeView): boolean {
    return canWorkDispute(d, this.userId(), this.canManage(), this.canWork());
  }

  protected show(patch: Partial<ReturnType<typeof this.filters.getRawValue>>): void {
    this.filters.patchValue({ awaiting: false, noticePending: false, adjustmentPending: false, mailReplyPending: false, ...patch });
  }

  protected take(d: DisputeView): void {
    this.api.take(d.id).subscribe(() => {
      this.notifications.success('Contestação atribuída a você.');
      void this.router.navigate(['/cobranca/contestacoes', d.id]);
    });
  }
}
