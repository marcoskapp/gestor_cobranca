import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { UrlCodec, syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { isoDate, toDateStruct } from '../../../shared/models/dates';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { ReconciliationApi } from '../data/payment.api';
import { DIVERGENCE_TYPE_LABELS, RESOLUTION_LABELS } from '../data/payment.labels';
import { DivergenceFilter, DivergenceType, DivergenceView } from '../data/payment.models';
import { ResolveDialog, ResolveDialogData } from './resolve-dialog';

const ALL_STATUSES = 'todas';

/**
 * Situação na URL (`?situacao=`): o padrão da tela é "Em aberto" (fora da URL), então "Todas" (valor vazio) precisa de
 * um valor explícito (`situacao=todas`) para sobreviver ao voltar.
 */
export const divergenceStatusParam: UrlCodec<string> = {
  param: 'situacao',
  parse: (raw) => (raw === ALL_STATUSES || raw === '' ? '' : urlParam.choice('situacao', statusOptions('divergence').map((s) => s.value)).parse(raw)),
  format: (value) => value || ALL_STATUSES,
};

/** Fila de divergências da conciliação por tipo, com resolução auditada (justificativa obrigatória). */
@Component({
  selector: 'app-divergence-list',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, RouterLink, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, FilterBar, NgbInputDatepicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Divergências de conciliação" subtitle="Créditos que o sistema não conseguiu conciliar sozinho. Cada decisão fica registrada com a justificativa.">
      <a class="btn btn-link d-inline-flex align-items-center gap-2" routerLink="/pagamentos/conciliacao">
        <app-icon name="bank" />
        Extratos
      </a>
    </app-page-header>
    <app-filter-bar [activeCount]="activeFilters()">
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar divergências">
      <div class="d-flex flex-wrap gap-2 app-control-offset" role="group" aria-label="Tipo de divergência">
        <input type="radio" class="btn-check" formControlName="type" value="" id="dl-type-all" />
        <label class="btn btn-outline-primary" for="dl-type-all">Todas</label>
        @for (t of types; track t[0]) {
          <input type="radio" class="btn-check" formControlName="type" [value]="t[0]" [id]="'dl-type-' + t[0]" />
          <label class="btn btn-outline-primary" [for]="'dl-type-' + t[0]">{{ t[1] }}</label>
        }
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Aberta de">
        <div class="input-group">
          <input appInput ngbDatepicker #openedFrom="ngbDatepicker" formControlName="openedFrom" placeholder="dd/mm/aaaa" autocomplete="off" [maxDate]="openedToLimit()" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (aberta de)" (click)="openedFrom.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <app-field label="Aberta até">
        <div class="input-group">
          <input appInput ngbDatepicker #openedTo="ngbDatepicker" formControlName="openedTo" placeholder="dd/mm/aaaa" autocomplete="off" [minDate]="openedFromLimit()" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (aberta até)" (click)="openedTo.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      @if (activeFilters()) {
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="filters.reset()">Limpar filtros</button>
        </div>
      }
    </form>
    </app-filter-bar>
    <app-data-table
      caption="Divergências"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="check2-circle"
      emptyMessage="Nenhuma divergência com estes filtros."
      emptyHint="Divergências surgem na importação de extratos e nas notificações do Pix."
    >
      <ng-template appCell="occurredOn" [appCellOf]="query.rows()" let-d>
        {{ d.occurredOn ? (d.occurredOn | appDate: 'date') : (d.createdAt | appDate: 'date') }}
        <div class="app-muted dl-small">{{ d.source === 'WEBHOOK' ? 'Notificação Pix' : 'Extrato' }}</div>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-d>
        {{ typeLabels[asType(d.type)] }}
        <div class="app-muted dl-small">{{ d.creditorId ? creditors.name(d.creditorId) : 'Sem credor identificado' }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-d>
        <app-amount [value]="d.amount" />
        @if (d.expectedAmount !== null) {
          <div class="app-muted dl-small">esperado <app-amount [value]="d.expectedAmount" /></div>
        }
      </ng-template>
      <ng-template appCell="description" [appCellOf]="query.rows()" let-d>
        <span class="dl-desc">{{ d.description ?? d.providerReference ?? '—' }}</span>
        @if (d.paymentId) {
          <div><a class="dl-small" [routerLink]="['/pagamentos', d.paymentId]">Pagamento relacionado</a></div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d>
        <app-status-chip domain="divergence" [status]="d.status" />
        @if (d.resolution) {
          <div class="app-muted dl-small" [attr.title]="d.resolutionNote">{{ resolutions[d.resolution] }} · {{ users.name(d.resolvedBy) }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        @if (d.status === 'OPEN') {
          <button class="btn btn-outline-primary" type="button" (click)="resolve(d)">Resolver</button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dl-small {
      font-size: var(--app-text-xs);
    }
    .dl-desc {
      display: inline-block;
      max-width: 320px;
      white-space: normal;
    }
  `,
})
export class DivergenceList {
  private readonly api = inject(ReconciliationApi);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly types = Object.entries(DIVERGENCE_TYPE_LABELS);
  protected readonly typeLabels = DIVERGENCE_TYPE_LABELS;
  protected readonly resolutions = RESOLUTION_LABELS;
  protected readonly statuses = statusOptions('divergence');
  protected readonly asType = (t: string) => t as DivergenceType;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly creditorSource = inject(CreditorLookup);
  /**
   * Filtros na URL (`?tipo=&situacao=&credor=&aberta-de=&aberta-ate=`), preservados ao voltar e usados pelo link de
   * "Minhas pendências" e pelo drill-down do painel de confiabilidade (abertas no período, em aberto agora).
   */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      type: [''],
      status: ['OPEN'],
      creditor: this.fb.control<string[]>([]),
      openedFrom: this.fb.control<string | null>(null),
      openedTo: this.fb.control<string | null>(null),
    }),
    {
      type: urlParam.choice('tipo', Object.keys(DIVERGENCE_TYPE_LABELS)),
      status: divergenceStatusParam,
      creditor: urlParam.ids('credor'),
      openedFrom: urlParam.date('aberta-de'),
      openedTo: urlParam.date('aberta-ate'),
    },
  );
  protected readonly activeFilters = activeFilterCount(this.filters);
  private readonly dates = toSignal(this.filters.valueChanges.pipe(startWith(null)), { initialValue: null });
  /** O intervalo não se inverte: "de" ≤ "até" (limites dos calendários). */
  protected readonly openedFromLimit = computed(() => {
    this.dates();
    return toDateStruct(this.filters.controls.openedFrom.value || '1900-01-01');
  });
  protected readonly openedToLimit = computed(() => {
    this.dates();
    return toDateStruct(this.filters.controls.openedTo.value || '2100-12-31');
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(150),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.divergences(f, page), { url: true });
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((d) => d.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((d) => d.resolvedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'occurredOn', header: 'Data e fonte' },
    { key: 'type', header: 'Tipo e credor' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'description', header: 'Histórico' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected resolve(d: DivergenceView): void {
    this.dialog
      .open<ResolveDialog, ResolveDialogData, DivergenceView>(ResolveDialog, { data: { divergence: d }, size: 'lg' })
      .afterClosed()
      .subscribe((resolved) => {
        if (resolved) {
          this.notifications.success('Divergência resolvida.');
          this.query.reload();
        }
      });
  }

  private current(): DivergenceFilter {
    const v = this.filters.getRawValue();
    return {
      type: v.type || null,
      status: v.status || null,
      creditorId: v.creditor[0] ?? null,
      openedFrom: isoDate(v.openedFrom),
      openedTo: isoDate(v.openedTo),
    };
  }
}
