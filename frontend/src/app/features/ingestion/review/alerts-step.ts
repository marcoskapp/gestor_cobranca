import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
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
import { Icon } from '../../../shared/components/icon/icon';

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
  imports: [Icon, DecimalPipe, Amount, CellDef, DataTable, Skeleton, StatusChip],
  template: `
    @let a = review().analysis;
    @if (a.status === 'NOT_STARTED' || a.status === 'PENDING') {
      <p class="app-muted" role="status">Analisando os valores: comparando com o histórico do credor e com o próprio arquivo…</p>
      <app-skeleton variant="table" [rows]="4" />
    } @else if (a.status === 'FAILED') {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>A análise dos valores falhou. Altere e confirme o formato de novo para refazer a prévia, ou tente mais tarde.</span></div>
    } @else {
      <p class="as-intro">
        Valores muito acima ou abaixo do esperado para esta carteira, e outros sinais de erro de digitação ou de separador decimal. Confira cada um na sua planilha.
        @if (reference()) {
          <span class="app-muted">Comparado com: {{ reference() }}.</span>
        }
      </p>
      @if (a.total === 0) {
        <div class="alert alert-success d-flex align-items-start gap-2" role="status"><app-icon name="check2-circle" /><span>Nenhum valor fora do esperado.</span></div>
      } @else {
        @if (a.truncated) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-triangle" /><span>Há alertas demais ({{ a.total | number }}): o arquivo parece ter um problema sistemático. Revise o formato dos valores.</span></div>
        }
        @if (a.openScale) {
          <div class="alert alert-warning d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-lg" /><span>{{ a.openScale | number }} alerta(s) de provável separador decimal precisam ser revisados antes de importar.</span></div>
        }
        <ul class="as-summary" aria-label="Alertas por tipo" data-tour="review-alerts">
          @for (t of a.byType; track t.type) {
            <li>
              <button type="button" class="as-chip" [class.as-chip--active]="type() === t.type" (click)="toggleType(t.type)" [attr.aria-pressed]="type() === t.type">
                <app-icon [name]="t.scale ? 'exclamation-lg' : 'info-circle'" />
                <span>{{ typeLabel(t.type) }}</span>
                <span class="as-chip__n app-numeric">{{ t.open | number }} abertos · {{ t.confirmed + t.excluded | number }} revisados</span>
              </button>
            </li>
          }
        </ul>
        <div class="as-tools">
          <div class="btn-group btn-group-sm flex-wrap" role="group" aria-label="Situação dos alertas">
            <input type="radio" class="btn-check" name="as-status" id="as-status-open" [checked]="status() === 'OPEN'" (change)="setStatus('OPEN')" />
            <label class="btn btn-outline-primary" for="as-status-open">Abertos ({{ a.open | number }})</label>
            <input type="radio" class="btn-check" name="as-status" id="as-status-confirmed" [checked]="status() === 'CONFIRMED'" (change)="setStatus('CONFIRMED')" />
            <label class="btn btn-outline-primary" for="as-status-confirmed">Confirmados ({{ a.confirmed | number }})</label>
            <input type="radio" class="btn-check" name="as-status" id="as-status-excluded" [checked]="status() === 'EXCLUDED'" (change)="setStatus('EXCLUDED')" />
            <label class="btn btn-outline-primary" for="as-status-excluded">Excluídos ({{ a.excluded | number }})</label>
            <input type="radio" class="btn-check" name="as-status" id="as-status-all" [checked]="status() === ''" (change)="setStatus('')" />
            <label class="btn btn-outline-primary" for="as-status-all">Todos</label>
          </div>
          @if (canEdit()) {
            <span class="as-spacer"></span>
            @if (status() === 'OPEN') {
              <button class="btn btn-outline-primary" type="button" [disabled]="!selected().length" (click)="decide('CONFIRM', false)"><app-icon name="check-lg" />Confirmo que está correto</button>
              <button class="btn btn-outline-primary" type="button" [disabled]="!selected().length" (click)="decide('EXCLUDE', false)"><app-icon name="dash-circle" />Excluir do lote</button>
              <button class="btn btn-link" type="button" [disabled]="!openInFilter()" (click)="decide('CONFIRM', true)">Confirmar todos os abertos{{ type() ? ' deste tipo' : '' }}</button>
            } @else if (status()) {
              <button class="btn btn-outline-primary" type="button" [disabled]="!selected().length" (click)="decide('REOPEN', false)"><app-icon name="arrow-counterclockwise" />Reabrir</button>
            }
          }
        </div>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
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
          emptyIcon="check2-circle"
          emptyMessage="Nenhum alerta nesta situação."
        >
          <ng-template appCell="select" [appCellOf]="alerts.rows()" let-x>
            <input class="form-check-input" type="checkbox" [checked]="isSelected(x)" (change)="toggle(x)" [attr.aria-label]="'Selecionar alerta da linha ' + x.rowNumber" />
          </ng-template>
          <ng-template appCell="row" [appCellOf]="alerts.rows()" let-x>
            <span class="app-numeric">{{ x.rowNumber || 'Arquivo' }}</span>
            @if (x.externalId) {<div class="app-muted app-mono as-id">{{ x.externalId }}</div>}
          </ng-template>
          <ng-template appCell="type" [appCellOf]="alerts.rows()" let-x>
            <span
              class="badge rounded-pill d-inline-flex align-items-center gap-1"
              [class.bg-danger-subtle]="x.scale"
              [class.text-danger-emphasis]="x.scale"
              [class.bg-warning-subtle]="!x.scale"
              [class.text-warning-emphasis]="!x.scale"
            >
              <app-icon [name]="x.scale ? 'exclamation-lg' : 'info-circle'" />{{ typeLabel(x.type) }}
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
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
      color: inherit;
      font: inherit;
      font-size: var(--app-text-sm);
      cursor: pointer;
    }
    .as-chip--active {
      border-color: var(--bs-primary-text-emphasis);
      box-shadow: inset 0 0 0 1px var(--bs-primary);
    }
    .as-chip app-icon {
      font-size: 1.125rem;
    }
    .as-chip__n {
      color: var(--bs-secondary-color);
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
      color: var(--bs-secondary-color);
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
