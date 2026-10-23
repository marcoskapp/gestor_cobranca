import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { timer } from 'rxjs';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ReconciliationApi } from '../data/payment.api';
import { DIRECTION_LABELS, STATEMENT_FAILURE_LABELS } from '../data/payment.labels';
import { StatementImportView } from '../data/payment.models';
import { importIndicators, isImportRunning } from '../data/payment.rules';

const percent = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 });

/** Resultado de um extrato importado: indicadores do lote e o tratamento de cada linha. */
@Component({
  selector: 'app-statement-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule, Amount, AppDatePipe, CellDef, DataTable, EmptyState, PageHeader, Skeleton, StatusChip],
  template: `
    @if (resource.isLoading() && !statement()) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (resource.status() === 'error' && !statement()) {
      <app-empty-state icon="cloud_off" tone="error" title="Não foi possível carregar o extrato." message="Verifique a conexão e tente novamente.">
        <button mat-stroked-button type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (statement(); as s) {
      @let ind = indicators()!;
      <app-page-header [title]="s.fileName ?? 'Extrato importado'" [subtitle]="'Importado em ' + (s.createdAt | appDate) + ' por ' + users.name(s.requestedBy) + ' · ' + (s.creditorId ? creditors.name(s.creditorId) : 'todos os credores')">
        @if (s.divergentLines > 0) {
          <a mat-flat-button routerLink="/pagamentos/divergencias" [queryParams]="{ situacao: 'OPEN' }">
            <mat-icon aria-hidden="true">rule</mat-icon>
            Tratar divergências
          </a>
        }
      </app-page-header>
      <div class="sd-status">
        <app-status-chip domain="statementImport" [status]="s.status" />
        <span class="app-muted">{{ size(s.fileSize) }} · SHA-256 <span class="app-mono" [attr.title]="s.fileSha256">{{ s.fileSha256.slice(0, 12) }}…</span></span>
        @if (s.finishedAt) {
          <span class="app-muted">· concluído em {{ s.finishedAt | appDate }}</span>
        }
      </div>
      @if (running()) {
        <mat-progress-bar mode="indeterminate" aria-label="Conciliando" />
        <p class="app-muted" role="status">Conciliando as linhas. Esta tela se atualiza sozinha.</p>
      }
      @if (s.status === 'FAILED') {
        <p class="app-alert app-alert--error" role="alert">
          <mat-icon aria-hidden="true">error_outline</mat-icon>
          <span>O arquivo foi recusado: {{ failure(s.failureCode) }} Nenhuma linha foi conciliada. Corrija e importe de novo (a prévia aponta as linhas com problema).</span>
        </p>
      }

      <section class="app-card" aria-labelledby="sd-kpis" data-tour="statement-indicators">
        <h2 class="app-card__title" id="sd-kpis">Indicadores do lote</h2>
        <div class="sd-kpis">
          <div class="sd-kpi"><span class="sd-kpi__label">Linhas lidas</span><span class="sd-kpi__value">{{ s.totalLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Créditos tratados</span><span class="sd-kpi__value">{{ ind.credits }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Conciliadas</span><span class="sd-kpi__value">{{ s.reconciledLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Confirmaram pagamento</span><span class="sd-kpi__value">{{ s.createdPayments }}</span></div>
          <div class="sd-kpi sd-kpi--warn"><span class="sd-kpi__label">Divergentes</span><span class="sd-kpi__value">{{ s.divergentLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Ignoradas</span><span class="sd-kpi__value">{{ s.ignoredLines }}</span></div>
        </div>
        <p class="app-muted sd-small">
          Taxa de conciliação: <strong>{{ ind.reconciledShare === null ? '—' : pct(ind.reconciledShare) }}</strong> dos créditos (conciliadas + confirmações) ·
          divergência: <strong>{{ ind.divergentShare === null ? '—' : pct(ind.divergentShare) }}</strong>. Ignoradas = débitos e transações já importadas em outro arquivo.
        </p>
      </section>

      <h2 class="app-section-title">Linhas</h2>
      <app-data-table
        caption="Linhas do extrato"
        [rows]="lines.rows()"
        [columns]="columns"
        [loading]="lines.loading()"
        [failed]="lines.failed()"
        [page]="lines.tablePage()"
        (pageChange)="lines.setPage($event)"
        (retry)="lines.reload()"
        emptyIcon="receipt_long"
        [emptyMessage]="running() ? 'As linhas aparecem ao fim do processamento.' : 'Nenhuma linha registrada.'"
      >
        <ng-template appCell="lineNumber" [appCellOf]="lines.rows()" let-l><span class="app-numeric">{{ l.lineNumber }}</span></ng-template>
        <ng-template appCell="paidOn" [appCellOf]="lines.rows()" let-l>{{ l.paidOn | appDate: 'date' }}</ng-template>
        <ng-template appCell="amount" [appCellOf]="lines.rows()" let-l>
          <app-amount [value]="l.amount" />
          <div class="app-muted sd-xs">{{ directions[l.direction] ?? l.direction }}</div>
        </ng-template>
        <ng-template appCell="ids" [appCellOf]="lines.rows()" let-l>
          <span class="app-mono sd-xs">{{ l.transactionId ?? '—' }}</span>
          <div class="app-mono app-muted sd-xs">{{ l.reference ?? '' }}</div>
        </ng-template>
        <ng-template appCell="description" [appCellOf]="lines.rows()" let-l>{{ l.description ?? '—' }}</ng-template>
        <ng-template appCell="status" [appCellOf]="lines.rows()" let-l>
          <app-status-chip domain="statementLine" [status]="l.status" />
          @if (l.paymentId) {
            <div><a class="sd-xs" [routerLink]="['/pagamentos', l.paymentId]">Ver pagamento</a></div>
          }
        </ng-template>
      </app-data-table>
    }
  `,
  styles: `
    .sd-status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 12px;
      margin-bottom: var(--app-gap);
    }
    .sd-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 12px;
    }
    .sd-kpi {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
    .sd-kpi--warn .sd-kpi__value {
      color: var(--app-warning-fg);
    }
    .sd-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--mat-sys-on-surface-variant);
    }
    .sd-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    .sd-small {
      margin: 12px 0 0;
      font-size: var(--app-text-sm);
    }
    .sd-xs {
      font-size: var(--app-text-xs);
    }
    .app-card {
      margin-bottom: var(--app-gap);
    }
  `,
})
export class StatementDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ReconciliationApi);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly size = sizeText;
  protected readonly directions = DIRECTION_LABELS;
  protected readonly pct = (v: number) => percent.format(v);

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.getImport(params) });
  protected readonly statement = valueOr<StatementImportView | undefined>(this.resource, undefined);
  protected readonly running = computed(() => (this.statement() ? isImportRunning(this.statement()!) : false));
  protected readonly indicators = computed(() => (this.statement() ? importIndicators(this.statement()!) : null));
  /** Recarrega as linhas quando o lote termina. */
  private readonly linesKey = computed(() => ({ id: this.id(), done: !this.running() }));
  protected readonly lines = pagedQuery(this.linesKey, (k, page) => this.api.lines(k.id, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.statement()?.creditorId]);
  protected readonly users = nameMap(inject(UserNames), () => [this.statement()?.requestedBy], { batch: USER_NAMES_BATCH });
  private readonly polls = signal(0);

  protected readonly columns: TableColumn[] = [
    { key: 'lineNumber', header: 'Linha', align: 'end' },
    { key: 'paidOn', header: 'Data' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'ids', header: 'Transação e referência' },
    { key: 'description', header: 'Histórico' },
    { key: 'status', header: 'Tratamento' },
  ];

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.statement()?.fileName ?? null));
    const refresh = timer(3_000, 3_000).subscribe(() => {
      if (this.running() && !this.resource.isLoading() && this.polls() < 200) {
        this.polls.update((n) => n + 1);
        this.resource.reload();
      }
    });
    inject(DestroyRef).onDestroy(() => refresh.unsubscribe());
  }

  protected failure(code: string | null): string {
    return (code && STATEMENT_FAILURE_LABELS[code]) || 'arquivo inválido.';
  }
}
