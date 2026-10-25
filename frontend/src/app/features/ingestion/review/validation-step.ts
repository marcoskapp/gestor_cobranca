import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { ImportBatchesApi } from '../data/ingestion.api';
import { isTerminal, progressText } from '../data/ingestion.labels';
import { IssueSeverity, ReviewView } from '../data/ingestion.models';
import { Icon } from '../../../shared/components/icon/icon';

/** Passo 3: validação das linhas da prévia (nada é gravado), com as ocorrências por linha já existentes. */
@Component({
  selector: 'app-validation-step',
  imports: [Icon, DecimalPipe, RouterLink, CellDef, DataTable, StatusChip],
  template: `
    @let b = review().batch;
    @if (!terminal()) {
      <div class="vs-progress" role="status" aria-live="polite">
        <app-status-chip domain="importBatch" [status]="b.status" />
        <div class="progress" role="progressbar" [attr.aria-label]="progress()" aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="b.progressPercent">
          <div class="progress-bar" [class.progress-bar-striped]="b.progressPercent === null" [class.progress-bar-animated]="b.progressPercent === null" [style.width.%]="b.progressPercent ?? 100"></div>
        </div>
        <span>{{ progress() }}</span>
      </div>
    } @else if (b.status === 'FAILED' || b.status === 'CANCELLED') {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ b.statusReason || 'A validação não foi concluída.' }}</span></div>
    }
    <section class="vs-kpis" aria-label="Resultado da validação" data-tour="review-validation">
      <div class="vs-kpi"><span class="vs-kpi__label">Linhas lidas</span><span class="vs-kpi__value app-numeric">{{ b.counters.rowsRead | number }}</span></div>
      <div class="vs-kpi"><span class="vs-kpi__label">Válidas</span><span class="vs-kpi__value app-numeric">{{ b.counters.rowsValid | number }}</span></div>
      <div class="vs-kpi" [class.vs-kpi--alert]="b.counters.rowsWithError > 0">
        <span class="vs-kpi__label">Com erro (não serão importadas)</span><span class="vs-kpi__value app-numeric">{{ b.counters.rowsWithError | number }}</span>
      </div>
      <div class="vs-kpi"><span class="vs-kpi__label">Avisos</span><span class="vs-kpi__value app-numeric">{{ b.counters.warnings | number }}</span></div>
    </section>
    <div class="vs-head">
      <h3 class="vs-title">Ocorrências por linha</h3>
      <div class="vs-tools">
        <div class="btn-group btn-group-sm flex-wrap" role="group" aria-label="Filtrar ocorrências">
          <input type="radio" class="btn-check" name="vs-severity" id="vs-severity-all" [checked]="severity() === ''" (change)="severity.set('')" />
          <label class="btn btn-outline-primary" for="vs-severity-all">Todas</label>
          <input type="radio" class="btn-check" name="vs-severity" id="vs-severity-error" [checked]="severity() === 'ERROR'" (change)="severity.set('ERROR')" />
          <label class="btn btn-outline-primary" for="vs-severity-error">Erros</label>
          <input type="radio" class="btn-check" name="vs-severity" id="vs-severity-warning" [checked]="severity() === 'WARNING'" (change)="severity.set('WARNING')" />
          <label class="btn btn-outline-primary" for="vs-severity-warning">Avisos</label>
        </div>
        @if (b.issuesRecorded) {
          <button class="btn btn-outline-primary" type="button" (click)="report()"><app-icon name="download" /> Relatório</button>
        }
        <a class="btn btn-link" [routerLink]="['/importacao', b.id]">Abrir a prévia</a>
      </div>
    </div>
    <app-data-table
      caption="Ocorrências da prévia"
      [rows]="issues.rows()"
      [columns]="columns"
      [loading]="issues.loading()"
      [failed]="issues.failed()"
      [page]="issues.tablePage()"
      (pageChange)="issues.setPage($event)"
      (retry)="issues.reload()"
      emptyIcon="check2-circle"
      emptyMessage="Nenhuma ocorrência."
    >
      <ng-template appCell="row" [appCellOf]="issues.rows()" let-i><span class="app-numeric">{{ i.rowNumber }}</span></ng-template>
      <ng-template appCell="severity" [appCellOf]="issues.rows()" let-i><app-status-chip domain="importIssue" [status]="i.severity" /></ng-template>
      <ng-template appCell="field" [appCellOf]="issues.rows()" let-i><span class="app-mono">{{ i.field || '—' }}</span></ng-template>
      <ng-template appCell="message" [appCellOf]="issues.rows()" let-i>{{ i.message }}<div class="app-muted app-mono">{{ i.code }}</div></ng-template>
      <ng-template appCell="value" [appCellOf]="issues.rows()" let-i><span class="app-mono">{{ i.valueExcerpt || '—' }}</span></ng-template>
    </app-data-table>
  `,
  styles: `
    .vs-progress {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
    }
    .vs-progress .progress {
      width: 240px;
    }
    .vs-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .vs-kpi {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 14px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .vs-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      color: var(--bs-secondary-color);
    }
    .vs-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .vs-kpi--alert .vs-kpi__value {
      color: var(--bs-danger-text-emphasis);
    }
    .vs-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .vs-title {
      margin: 0;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .vs-tools {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
  `,
})
export class ValidationStep {
  readonly review = input.required<ReviewView>();

  private readonly api = inject(ImportBatchesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly terminal = computed(() => isTerminal(this.review().batch.status));
  protected readonly progress = computed(() => progressText(this.review().batch));
  protected readonly severity = signal<'' | IssueSeverity>('');
  private readonly filter = computed(() => ({ id: this.review().batch.id, severity: this.severity() || null, status: this.review().batch.status, read: this.review().batch.counters.rowsRead }));
  protected readonly issues = pagedQuery(this.filter, (f, page) => this.api.issues(f.id, f.severity, page), 20);

  protected readonly columns: TableColumn[] = [
    { key: 'row', header: 'Linha', align: 'end' },
    { key: 'severity', header: 'Tipo' },
    { key: 'field', header: 'Coluna' },
    { key: 'message', header: 'Ocorrência' },
    { key: 'value', header: 'Valor recebido' },
  ];

  protected report(): void {
    this.api.downloadIssues(this.review().batch.id).subscribe((name) => this.notifications.success(`Relatório baixado (${name}).`));
  }
}
