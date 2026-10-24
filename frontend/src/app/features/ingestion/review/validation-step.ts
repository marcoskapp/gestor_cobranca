import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { ImportBatchesApi } from '../data/ingestion.api';
import { isTerminal, progressText } from '../data/ingestion.labels';
import { IssueSeverity, ReviewView } from '../data/ingestion.models';

/** Passo 3: validação das linhas da prévia (nada é gravado), com as ocorrências por linha já existentes. */
@Component({
  selector: 'app-validation-step',
  imports: [DecimalPipe, FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressBarModule, CellDef, DataTable, StatusChip],
  template: `
    @let b = review().batch;
    @if (!terminal()) {
      <div class="vs-progress" role="status" aria-live="polite">
        <app-status-chip domain="importBatch" [status]="b.status" />
        <mat-progress-bar [mode]="b.progressPercent === null ? 'indeterminate' : 'determinate'" [value]="b.progressPercent ?? 0" [attr.aria-label]="progress()" />
        <span>{{ progress() }}</span>
      </div>
    } @else if (b.status === 'FAILED' || b.status === 'CANCELLED') {
      <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ b.statusReason || 'A validação não foi concluída.' }}</span></p>
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
        <mat-button-toggle-group [ngModel]="severity()" (ngModelChange)="severity.set($event)" aria-label="Filtrar ocorrências" hideSingleSelectionIndicator>
          <mat-button-toggle value="">Todas</mat-button-toggle>
          <mat-button-toggle value="ERROR">Erros</mat-button-toggle>
          <mat-button-toggle value="WARNING">Avisos</mat-button-toggle>
        </mat-button-toggle-group>
        @if (b.issuesRecorded) {
          <button mat-stroked-button type="button" (click)="report()"><mat-icon aria-hidden="true">download</mat-icon>Relatório</button>
        }
        <a mat-button [routerLink]="['/importacao', b.id]">Abrir a prévia</a>
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
      emptyIcon="task_alt"
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
    .vs-progress mat-progress-bar {
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
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
    }
    .vs-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      color: var(--mat-sys-on-surface-variant);
    }
    .vs-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .vs-kpi--alert .vs-kpi__value {
      color: var(--app-danger-fg);
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
