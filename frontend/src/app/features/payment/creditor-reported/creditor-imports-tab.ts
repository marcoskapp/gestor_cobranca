import { LowerCasePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { emptyPage } from '../../../shared/models/page';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { BatchLabel, CreditorReportedApi, ReportedTitleFilter, ReportedTitleItem, ReportedTitlesPage, ImportSituation } from './creditor-reported.api';
import { IMPORT_SITUATION_LABELS, batchText, defaultPeriod, periodProblem } from './creditor-reported.rules';

const SITUATIONS = Object.keys(IMPORT_SITUATION_LABELS) as ImportSituation[];

/**
 * Aba "Pela carga do credor": títulos que a carga do credor quitou ao reduzir o principal e títulos suspensos porque o
 * credor informou pagamento ou cancelamento na carga (sempre com o lote que suspendeu). O rótulo do lote (data e layout, nunca o nome do arquivo) vem do
 * módulo de importação, só para quem tem `IMPORT_VIEW` e só dos lotes visíveis.
 */
@Component({
  selector: 'app-creditor-imports-tab',
  imports: [FilterBar, Icon, LowerCasePipe, NgbInputDatepicker, ...FORM_FIELD, ReactiveFormsModule, RouterLink, Amount, AppDatePipe, CellDef, DataTable, EntityPicker],
  template: `
    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar baixas pela carga do credor">
        <app-field label="De">
          <div class="input-group">
            <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" required />
            <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Até">
          <div class="input-group">
            <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" required />
            <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Situação">
          <select appInput formControlName="situation">
            <option value="">Todas</option>
            @for (s of situations; track s) {
              <option [ngValue]="s">{{ situationLabels[s] }}</option>
            }
          </select>
        </app-field>
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
        </div>
      </form>
    </app-filter-bar>
    @if (filters.controls.batch.value) {
      <p class="cs-chip" data-testid="batch-filter">
        <app-icon name="funnel" /> {{ batchFilterText() }}
        <button class="btn btn-sm btn-link" type="button" (click)="filters.controls.batch.setValue('')">Ver todas as cargas</button>
      </p>
    }
    @if (problem(); as message) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="alert" data-testid="filter-problem">
        <app-icon name="exclamation-triangle" /><span>{{ message }}</span>
      </div>
    } @else if (totals(); as t) {
      <p class="cs-totals" aria-live="polite" data-testid="totals">
        <strong>{{ t.count }}</strong> {{ t.count === 1 ? 'título' : 'títulos' }} · total <strong><app-amount [value]="t.amount" /></strong>
      </p>
    }
    <app-data-table
      caption="Baixas pela carga do credor"
      [rows]="query.rows()"
      [columns]="columns()"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      [sort]="query.sort()"
      (pageChange)="query.setPage($event)"
      (sortChange)="query.setSort($event)"
      (retry)="query.reload()"
      emptyIcon="file-earmark-arrow-up"
      [emptyMessage]="problem() ? 'Ajuste o período para consultar.' : 'Nenhum título quitado ou suspenso pela carga do credor no período.'"
      emptyHint="Aparecem aqui os títulos zerados pela carga do credor e os que o credor informou como pagos ou cancelados."
    >
      <ng-template appCell="occurredAt" [appCellOf]="query.rows()" let-t>{{ t.occurredAt | appDate: 'date' }}</ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-t>{{ creditors.name(t.creditorId) }}</ng-template>
      <ng-template appCell="title" [appCellOf]="query.rows()" let-t>
        @if (t.titleReference) {
          <a class="app-mono" [routerLink]="['/titulos', t.titleId]" [attr.aria-label]="'Abrir título ' + t.titleReference">{{ t.titleReference }}</a>
        } @else if (t.debtorAnonymized && canViewTitle()) {
          <!-- titular anonimizado: sem a referência (decisão 1581), mas o título continua acessível (UX-04) -->
          <a [routerLink]="['/titulos', t.titleId]" [attr.aria-label]="'Abrir título de titular anonimizado, ' + (situationLabels[t.situation] | lowercase) + ' em ' + (t.occurredAt | appDate: 'date')">Abrir título</a>
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-t>
        @if (t.debtorName) {
          <a [routerLink]="['/devedores', t.debtorId]" [attr.aria-label]="'Abrir devedor ' + t.debtorName">{{ t.debtorName }}</a>
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="situation" [appCellOf]="query.rows()" let-t>{{ situationLabels[t.situation] }}</ng-template>
      <ng-template appCell="batch" [appCellOf]="query.rows()" let-t>
        @let label = batchLabel(t);
        @if (t.importBatchId && labels().get(t.importBatchId) && canViewImports()) {
          <a [routerLink]="['/importacao', t.importBatchId]" [attr.aria-label]="'Abrir ' + label">{{ label }}</a>
        } @else {
          <span>{{ label }}</span>
        }
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-t><app-amount [value]="t.amount" /></ng-template>
    </app-data-table>
  `,
  styles: `
    .cs-totals, .cs-chip { margin: 0 0 var(--app-gap); }
  `,
})
export class CreditorImportsTab {
  private readonly api = inject(CreditorReportedApi);
  private readonly auth = inject(AuthService);
  private readonly date = new AppDatePipe();
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly situations = SITUATIONS;
  protected readonly situationLabels = IMPORT_SITUATION_LABELS;
  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canViewTitle = computed(() => this.auth.hasAnyPermission([Permission.DEBTOR_VIEW, Permission.CASE_VIEW]));
  protected readonly canViewImports = computed(() => this.auth.hasPermission(Permission.IMPORT_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly period = defaultPeriod();
  /** `?aba=carga&de=&ate=&credor=&lote=&situacao=` (o período padrão não vai para a URL). */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      from: this.fb.control<string | null>(this.period.from),
      to: this.fb.control<string | null>(this.period.to),
      creditor: this.fb.control<string[]>([]),
      batch: [''],
      situation: this.fb.control<ImportSituation | ''>(''),
    }),
    {
      from: urlParam.date('de'),
      to: urlParam.date('ate'),
      creditor: urlParam.ids('credor'),
      batch: urlParam.id('lote'),
      situation: urlParam.choice('situacao', SITUATIONS),
    },
  );
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly problem = computed(() => periodProblem(this.filter().from, this.filter().to));
  protected readonly totals = signal<ReportedTitlesPage['totals'] | null>(null);
  protected readonly query = pagedQuery(
    this.filter,
    (f, page) => {
      if (periodProblem(f.from, f.to)) {
        this.totals.set(null);
        return of(emptyPage<ReportedTitleItem>(page.size));
      }
      return this.api.imports(f, page).pipe(map((r) => {
        this.totals.set(r.totals);
        return r.items;
      }));
    },
    { url: true, sortFields: { occurredAt: 'occurredAt', amount: 'amount' } },
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));

  /** Rótulos dos lotes da página (uma consulta por página, só com `IMPORT_VIEW`). */
  private readonly labelsResource = rxResource({
    params: () => {
      if (!this.canViewImports()) {
        return undefined;
      }
      const ids = [...new Set(this.query.rows().map((t) => t.importBatchId).filter((id): id is string => !!id))].sort();
      return ids.length ? ids : undefined;
    },
    stream: ({ params }) => this.api.batchLabels(params).pipe(catchError(() => of([] as BatchLabel[]))),
  });
  private readonly labelList = valueOr(this.labelsResource, [] as BatchLabel[]);
  protected readonly labels = computed(() => new Map(this.labelList().map((l) => [l.id, l])));

  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'occurredAt', header: 'Zerado ou suspenso em', sortable: true, info: 'payment.creditor-reported.import-date', mobile: 'meta' },
    { key: 'creditor', header: 'Credor', mobile: 'meta' },
    ...(this.canViewTitle() ? [{ key: 'title', header: 'Título', mobile: 'title' } as TableColumn] : []),
    ...(this.canViewDebtor() ? [{ key: 'debtor', header: 'Devedor', mobile: 'meta' } as TableColumn] : []),
    { key: 'situation', header: 'Situação', mobile: 'badge' },
    { key: 'batch', header: 'Carga', mobile: 'meta' },
    { key: 'amount', header: 'Valor do título', align: 'end', sortable: true, info: 'payment.creditor-reported.title-amount', mobile: 'meta' },
  ]);

  protected batchLabel(t: ReportedTitleItem): string {
    return batchText(t.importBatchId ? this.labels().get(t.importBatchId) : undefined, (iso) => this.date.transform(iso, 'date'));
  }

  /** Aviso do filtro por lote com o rótulo da carga quando ele está numa linha da página (UX-07). */
  protected batchFilterText(): string {
    const label = this.labels().get(this.filters.controls.batch.value);
    return label ? `Só os títulos da ${batchText(label, (iso) => this.date.transform(iso, 'date')).replace(/^Carga/, 'carga')}` : 'Só os títulos de uma carga';
  }

  protected clear(): void {
    this.filters.reset({ ...this.period, creditor: [], batch: '', situation: '' });
  }

  private current(): ReportedTitleFilter {
    const v = this.filters.getRawValue();
    return {
      from: v.from ?? '',
      to: v.to ?? '',
      creditorId: v.creditor[0] ?? null,
      importBatchId: v.batch || null,
      situation: v.situation || null,
    };
  }

  /** "Filtros (n)" do `app-filter-bar` no celular (ux.md §12.3). */
  protected readonly activeFilters = activeFilterCount(this.filters);
}
