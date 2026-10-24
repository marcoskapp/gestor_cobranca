import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Observable } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { ImportReviewsApi } from '../data/ingestion.api';
import { AlertDecision, AlertStatus, AlertType, AlertView, AnalysisView, ReviewView } from '../data/ingestion.models';
import { ALERT_TYPE_LABELS, REFERENCE_LABELS, REVIEW_ERROR_LABELS } from '../data/review.rules';

/** Texto da referência estatística usada (resumo da análise). */
export function referenceSummary(references: Record<string, unknown>): string | null {
  const used = references['references'] as Record<string, number> | undefined;
  if (!used || !Object.keys(used).length) {
    return null;
  }
  const n = new Intl.NumberFormat('pt-BR');
  return Object.entries(used)
    .sort((a, b) => b[1] - a[1])
    .map(([kind, rows]) => `${REFERENCE_LABELS[kind] ?? kind} (${n.format(rows)} linhas)`)
    .join('; ');
}

/**
 * Passo 5: alertas de valor ("ticket médio" e outros sinais), do mais prioritário ao menos. Nenhum alerta bloqueia
 * sozinho: o usuário confirma que está correto ou exclui a linha do lote, por linha ou em lote, sempre com
 * justificativa; alertas de provável separador decimal precisam ser todos revisados.
 */
@Component({
  selector: 'app-alerts-step',
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatSelectModule, Amount, CellDef, DataTable, Skeleton, StatusChip],
  template: `
    @let a = review().analysis;
    @if (a.status === 'NOT_STARTED' || a.status === 'PENDING') {
      <p class="app-muted" role="status">Analisando os valores: comparando com o histórico do credor e com o próprio arquivo…</p>
      <app-skeleton variant="table" [rows]="4" />
    } @else if (a.status === 'FAILED') {
      <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>A análise dos valores falhou. Altere e confirme o formato de novo para refazer a prévia, ou tente mais tarde.</span></p>
    } @else {
      <p class="as-intro">
        Valores muito acima ou abaixo do esperado para esta carteira, e outros sinais de erro de digitação ou de separador decimal. Confira cada um na sua planilha.
        @if (reference()) {
          <span class="app-muted">Comparado com: {{ reference() }}.</span>
        }
      </p>
      @if (a.total === 0) {
        <p class="app-alert app-alert--success" role="status"><mat-icon aria-hidden="true">task_alt</mat-icon><span>Nenhum valor fora do esperado.</span></p>
      } @else {
        @if (a.truncated) {
          <p class="app-alert app-alert--danger" role="alert"><mat-icon aria-hidden="true">warning</mat-icon><span>Há alertas demais ({{ a.total | number }}): o arquivo parece ter um problema sistemático. Revise o formato dos valores.</span></p>
        }
        @if (a.openScale) {
          <p class="app-alert app-alert--warning" role="alert"><mat-icon aria-hidden="true">priority_high</mat-icon><span>{{ a.openScale | number }} alerta(s) de provável separador decimal precisam ser revisados antes de importar.</span></p>
        }
        <ul class="as-summary" aria-label="Alertas por tipo" data-tour="review-alerts">
          @for (t of a.byType; track t.type) {
            <li>
              <button type="button" class="as-chip" [class.as-chip--active]="type() === t.type" (click)="toggleType(t.type)" [attr.aria-pressed]="type() === t.type">
                <mat-icon aria-hidden="true">{{ t.scale ? 'priority_high' : 'info' }}</mat-icon>
                <span>{{ typeLabel(t.type) }}</span>
                <span class="as-chip__n app-numeric">{{ t.open | number }} abertos · {{ t.confirmed + t.excluded | number }} revisados</span>
              </button>
            </li>
          }
        </ul>
        <div class="as-tools">
          <mat-button-toggle-group [ngModel]="status()" (ngModelChange)="setStatus($event)" aria-label="Situação dos alertas" hideSingleSelectionIndicator>
            <mat-button-toggle value="OPEN">Abertos ({{ a.open | number }})</mat-button-toggle>
            <mat-button-toggle value="CONFIRMED">Confirmados ({{ a.confirmed | number }})</mat-button-toggle>
            <mat-button-toggle value="EXCLUDED">Excluídos ({{ a.excluded | number }})</mat-button-toggle>
            <mat-button-toggle value="">Todos</mat-button-toggle>
          </mat-button-toggle-group>
          @if (canEdit()) {
            <span class="as-spacer"></span>
            @if (status() === 'OPEN') {
              <button mat-stroked-button type="button" [disabled]="!selected().length" (click)="decide('CONFIRM', false)"><mat-icon aria-hidden="true">check</mat-icon>Confirmo que está correto</button>
              <button mat-stroked-button type="button" [disabled]="!selected().length" (click)="decide('EXCLUDE', false)"><mat-icon aria-hidden="true">remove_circle_outline</mat-icon>Excluir do lote</button>
              <button mat-button type="button" [disabled]="!openInFilter()" (click)="decide('CONFIRM', true)">Confirmar todos os abertos{{ type() ? ' deste tipo' : '' }}</button>
            } @else if (status()) {
              <button mat-stroked-button type="button" [disabled]="!selected().length" (click)="decide('REOPEN', false)"><mat-icon aria-hidden="true">undo</mat-icon>Reabrir</button>
            }
          }
        </div>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        <app-data-table
          caption="Alertas de valor"
          [rows]="alerts.rows()"
          [columns]="columns()"
          [loading]="alerts.loading()"
          [failed]="alerts.failed()"
          [page]="alerts.tablePage()"
          (pageChange)="alerts.setPage($event)"
          (retry)="alerts.reload()"
          emptyIcon="task_alt"
          emptyMessage="Nenhum alerta nesta situação."
        >
          <ng-template appCell="select" [appCellOf]="alerts.rows()" let-x>
            <mat-checkbox [checked]="isSelected(x)" (change)="toggle(x)" [aria-label]="'Selecionar alerta da linha ' + x.rowNumber" />
          </ng-template>
          <ng-template appCell="row" [appCellOf]="alerts.rows()" let-x>
            <span class="app-numeric">{{ x.rowNumber || 'Arquivo' }}</span>
            @if (x.externalId) {<div class="app-muted app-mono as-id">{{ x.externalId }}</div>}
          </ng-template>
          <ng-template appCell="type" [appCellOf]="alerts.rows()" let-x>
            <span class="app-status" [class.app-status--danger]="x.scale" [class.app-status--warning]="!x.scale">
              <mat-icon aria-hidden="true">{{ x.scale ? 'priority_high' : 'info' }}</mat-icon>{{ typeLabel(x.type) }}
            </span>
          </ng-template>
          <ng-template appCell="amount" [appCellOf]="alerts.rows()" let-x>
            @if (x.amount !== null) {<app-amount [value]="x.amount" />} @else {—}
            @if (x.suggestedAmount !== null) {<div class="as-suggested">talvez <app-amount [value]="x.suggestedAmount" /></div>}
          </ng-template>
          <ng-template appCell="range" [appCellOf]="alerts.rows()" let-x>
            @if (x.expectedLow !== null || x.expectedHigh !== null) {
              <span class="app-numeric">@if (x.expectedLow !== null) {<app-amount [value]="x.expectedLow" />} a <app-amount [value]="x.expectedHigh" /></span>
            } @else {—}
          </ng-template>
          <ng-template appCell="detail" [appCellOf]="alerts.rows()" let-x>
            {{ x.detail }}
            @if (x.justification) {<div class="app-muted">Justificativa: {{ x.justification }}</div>}
          </ng-template>
          <ng-template appCell="status" [appCellOf]="alerts.rows()" let-x><app-status-chip domain="importAlert" [status]="x.status" /></ng-template>
        </app-data-table>
      }
    }
  `,
  styles: `
    .as-intro {
      margin-top: 0;
      max-width: 80ch;
    }
    .as-summary {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 0 0 12px;
      padding: 0;
      list-style: none;
    }
    .as-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 10px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
      color: inherit;
      font: inherit;
      font-size: var(--app-text-sm);
      cursor: pointer;
    }
    .as-chip--active {
      border-color: var(--mat-sys-primary);
      box-shadow: inset 0 0 0 1px var(--mat-sys-primary);
    }
    .as-chip .mat-icon {
      width: 18px;
      height: 18px;
      font-size: 18px;
    }
    .as-chip__n {
      color: var(--mat-sys-on-surface-variant);
    }
    .as-tools {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }
    .as-spacer {
      flex: 1;
    }
    .as-id {
      white-space: nowrap;
    }
    .as-suggested {
      font-size: var(--app-text-xs);
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class AlertsStep {
  readonly review = input.required<ReviewView>();
  readonly canExecute = input(false);
  readonly changed = output<AnalysisView>();

  private readonly api = inject(ImportReviewsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly type = signal<AlertType | null>(null);
  protected readonly status = signal<AlertStatus | ''>('OPEN');
  protected readonly selected = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private readonly version = signal(0);
  protected readonly canEdit = computed(() => this.canExecute() && this.review().status === 'OPEN');
  protected readonly reference = computed(() => referenceSummary(this.review().analysis.references));
  protected readonly openInFilter = computed(() => {
    const a = this.review().analysis;
    const t = this.type();
    return t ? (a.byType.find((x) => x.type === t)?.open ?? 0) : a.open;
  });

  private readonly filter = computed(() => ({ id: this.review().id, batch: this.review().batch.id, type: this.type(), status: this.status() || null, v: this.version(), done: this.review().analysis.status }));
  protected readonly alerts = pagedQuery(this.filter, (f, page) => this.api.alerts(f.id, f.type, f.status, page), 20);

  protected readonly columns = computed<TableColumn[]>(() => [
    ...(this.canEdit() && this.status() ? [{ key: 'select', header: 'Selecionar' }] : []),
    { key: 'row', header: 'Linha', align: 'end' as const },
    { key: 'type', header: 'Alerta' },
    { key: 'amount', header: 'Valor', align: 'end' as const },
    { key: 'range', header: 'Faixa esperada' },
    { key: 'detail', header: 'Detalhe' },
    { key: 'status', header: 'Situação' },
  ]);

  protected typeLabel(type: AlertType): string {
    return ALERT_TYPE_LABELS[type] ?? type;
  }

  protected toggleType(type: AlertType): void {
    this.type.update((current) => (current === type ? null : type));
    this.selected.set([]);
  }

  protected setStatus(status: AlertStatus | ''): void {
    this.status.set(status);
    this.selected.set([]);
  }

  protected isSelected(a: AlertView): boolean {
    return this.selected().includes(a.id);
  }

  protected toggle(a: AlertView): void {
    this.selected.update((ids) => (ids.includes(a.id) ? ids.filter((id) => id !== a.id) : [...ids, a.id]));
  }

  protected decide(decision: AlertDecision, allOpen: boolean): void {
    const count = allOpen ? this.openInFilter() : this.selected().length;
    if (!count) {
      return;
    }
    if (decision === 'REOPEN') {
      this.send({ alertIds: this.selected(), allOpen: false, type: null, decision, justification: null }, 'Alertas reabertos.');
      return;
    }
    const exclude = decision === 'EXCLUDE';
    this.confirm
      .confirm({
        title: exclude ? 'Excluir linhas do lote' : 'Confirmar valores',
        message: exclude
          ? 'As linhas selecionadas não serão importadas. Elas continuam no relatório da importação, com a sua justificativa.'
          : 'Você confirma que conferiu esses valores na planilha e que estão corretos.',
        confirmLabel: exclude ? 'Excluir do lote' : 'Confirmar',
        destructive: exclude,
        reason: 'required',
        reasonLabel: 'Justificativa',
        details: [{ label: 'Alertas', value: new Intl.NumberFormat('pt-BR').format(count) + (allOpen && this.type() ? ` (${this.typeLabel(this.type()!)})` : '') }],
      })
      .subscribe((justification) =>
        this.send(
          { alertIds: allOpen ? [] : this.selected(), allOpen, type: allOpen ? this.type() : null, decision, justification },
          exclude ? 'Linhas excluídas do lote.' : 'Valores confirmados.',
        ),
      );
  }

  private send(request: Parameters<ImportReviewsApi['decide']>[1], message: string): void {
    this.error.set(null);
    const call: Observable<AnalysisView> = this.api.decide(this.review().id, request);
    call.subscribe({
      next: (analysis) => {
        this.selected.set([]);
        this.version.update((v) => v + 1);
        this.notifications.success(message);
        this.changed.emit(analysis);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && REVIEW_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }
}
