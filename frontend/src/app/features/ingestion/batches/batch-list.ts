import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { CreditorLookup, CreditorNames, PortfolioLookup, PortfolioNames } from '../../organization/data/organization.lookups';
import { ImportBatchesApi, ImportLayoutsApi, ImportReviewsApi } from '../data/ingestion.api';
import { MODE_LABELS, SOURCE_LABELS, isTerminal, progressText } from '../data/ingestion.labels';
import { BatchFilter, ImportBatchView, ReviewSummaryView } from '../data/ingestion.models';
import { REVIEW_STEPS } from '../data/review.rules';
import { Amount } from '../../../shared/components/amount/amount';

/** Intervalo de atualização enquanto houver lote em andamento na página. */
export const POLL_MS = 5000;

export function toBatchFilter(v: { creditor: string[]; portfolio: string[]; status: string; source: string }): BatchFilter {
  return { creditorId: v.creditor[0] ?? null, portfolioId: v.portfolio[0] ?? null, status: v.status || null, source: v.source || null };
}

/** Lotes de importação de carteira com progresso dos que estão em andamento e as conferências abertas. */
@Component({
  selector: 'app-batch-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
    DecimalPipe,
    Amount,
  ],
  template: `
    <app-page-header title="Importação de carteiras" subtitle="Arquivos e integrações recebidos dos credores, com prévia, progresso e ocorrências por linha.">
      <button mat-stroked-button type="button" [matMenuTriggerFor]="templates">
        <mat-icon aria-hidden="true">download</mat-icon>
        Modelo do layout padrão
      </button>
      <mat-menu #templates="matMenu">
        <button mat-menu-item type="button" (click)="template('xlsx')">Planilha XLSX</button>
        <button mat-menu-item type="button" (click)="template('csv')">Arquivo CSV</button>
      </mat-menu>
      <a mat-stroked-button routerLink="/importacao/layouts">
        <mat-icon aria-hidden="true">view_column</mat-icon>
        Layouts
      </a>
      @if (canExecute()) {
        <a mat-flat-button routerLink="/importacao/nova" [queryParams]="newParams()">
          <mat-icon aria-hidden="true">upload_file</mat-icon>
          Nova importação
        </a>
      }
    </app-page-header>

    @if (reviews.rows().length) {
      <section class="app-card bl-reviews" aria-labelledby="bl-reviews" data-tour="review-list">
        <h2 class="app-card__title" id="bl-reviews">Conferências em andamento</h2>
        <ul class="bl-review-list">
          @for (r of reviews.rows(); track r.id) {
            <li>
              <a [routerLink]="['/importacao/conferencias', r.id]">{{ r.fileName || 'Arquivo' }}</a>
              <app-status-chip domain="importReview" [status]="r.status" />
              <span class="app-muted">{{ portfolios.name(r.portfolioId) }} · passo {{ r.currentStep }} de 6 ({{ stepLabel(r) }}) · {{ r.createdAt | appDate }}</span>
              @if (r.status === 'AWAITING_APPROVAL' && r.principalToImport !== null) {
                <span class="app-muted">· <app-amount [value]="r.principalToImport" /></span>
              }
            </li>
          }
        </ul>
      </section>
    }

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar lotes">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field class="app-compact">
        <mat-label>Origem</mat-label>
        <mat-select formControlName="source">
          <mat-option value="">Todas</mat-option>
          @for (s of sources; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>

    <app-data-table
      caption="Lotes de importação"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="upload_file"
      emptyMessage="Nenhum lote encontrado."
      emptyHint="Baixe o modelo do layout padrão, preencha com os títulos do credor e envie primeiro em prévia."
    >
      <ng-template appCell="created" [appCellOf]="query.rows()" let-b>{{ b.createdAt | appDate }}</ng-template>
      <ng-template appCell="file" [appCellOf]="query.rows()" let-b>
        <a [routerLink]="['/importacao', b.id]">{{ b.fileName || 'Registros via API' }}</a>
        <div class="app-muted">{{ sourceLabels[b.source] }} · {{ modeLabels[b.mode] }} · {{ b.layoutCode }} v{{ b.layoutVersion }}</div>
      </ng-template>
      <ng-template appCell="portfolio" [appCellOf]="query.rows()" let-b>
        {{ portfolios.name(b.portfolioId) }}
        <div class="app-muted">{{ creditors.name(b.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-b>
        <app-status-chip domain="importBatch" [status]="b.status" />
        @if (!terminal(b)) {
          <mat-progress-bar class="bl-progress" [mode]="b.progressPercent === null ? 'indeterminate' : 'determinate'" [value]="b.progressPercent ?? 0" [attr.aria-label]="progress(b)" />
          <div class="app-muted">{{ progress(b) }}</div>
        }
      </ng-template>
      <ng-template appCell="rows" [appCellOf]="query.rows()" let-b>
        <span class="app-numeric">{{ b.counters.rowsRead | number }}</span>
        @if (b.counters.rowsWithError) {
          <div class="bl-error">{{ b.counters.rowsWithError | number }} com erro</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-b>
        <a mat-icon-button [routerLink]="['/importacao', b.id]" aria-label="Abrir lote" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .bl-progress {
      width: 160px;
      margin-top: 4px;
    }
    .bl-reviews {
      margin-bottom: var(--app-gap);
    }
    .bl-review-list {
      display: grid;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
    }
    .bl-review-list li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 10px;
    }
    .bl-error {
      font-size: var(--app-text-xs);
      color: var(--app-danger-fg);
    }
  `,
})
export class BatchList {
  private readonly api = inject(ImportBatchesApi);
  private readonly layoutsApi = inject(ImportLayoutsApi);
  private readonly reviewsApi = inject(ImportReviewsApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.filters.controls.creditor.value[0] ?? null);

  protected readonly statuses = statusOptions('importBatch');
  protected readonly sources = Object.entries(SOURCE_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly sourceLabels = SOURCE_LABELS;
  protected readonly modeLabels = MODE_LABELS;
  protected readonly progress = progressText;
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), portfolio: this.fb.control<string[]>([]), status: [''], source: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toBatchFilter(this.filters.getRawValue())),
    ),
    { initialValue: toBatchFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  /** Conferências abertas ou aguardando aprovação (com o filtro de credor/carteira da tela). */
  protected readonly reviews = pagedQuery(this.filter, (f, page) =>
    this.reviewsApi.search({ status: ['OPEN', 'AWAITING_APPROVAL'], creditorId: f.creditorId, portfolioId: f.portfolioId }, page),
    10,
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((b) => b.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [...this.query.rows().map((b) => b.portfolioId), ...this.reviews.rows().map((r) => r.portfolioId)]);
  private readonly running = computed(() => this.query.rows().some((b) => !isTerminal(b.status) && b.status !== 'AWAITING_FORMAT'));
  protected readonly newParams = computed(() => (this.filter().portfolioId ? { carteira: this.filter().portfolioId } : {}));

  protected readonly columns: TableColumn[] = [
    { key: 'created', header: 'Recebido em' },
    { key: 'file', header: 'Arquivo' },
    { key: 'portfolio', header: 'Carteira' },
    { key: 'status', header: 'Situação' },
    { key: 'rows', header: 'Linhas', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    const timer = setInterval(() => {
      if (this.running() && !this.query.loading()) {
        this.query.reload();
      }
    }, POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected terminal(b: ImportBatchView): boolean {
    return isTerminal(b.status);
  }

  protected template(format: 'csv' | 'xlsx'): void {
    this.layoutsApi.downloadTemplate(format).subscribe((name) => this.notifications.success(`Modelo baixado (${name}).`));
  }

  protected stepLabel(r: ReviewSummaryView): string {
    return REVIEW_STEPS.find((s) => s.step === r.currentStep)?.label ?? '';
  }
}
