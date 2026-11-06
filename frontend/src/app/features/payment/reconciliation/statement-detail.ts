import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize, timer } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
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
import { DIRECTION_LABELS, PAYMENT_ERROR_LABELS, STATEMENT_FAILURE_LABELS } from '../data/payment.labels';
import { StatementImportView } from '../data/payment.models';
import { importIndicators, isImportRunning } from '../data/payment.rules';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';

const percent = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 });

/** Resultado de um extrato importado: indicadores do lote e o tratamento de cada linha. */
@Component({
  selector: 'app-statement-detail',
  imports: [Icon, RouterLink, Amount, AppDatePipe, CellDef, DataTable, EmptyState, PageHeader, Skeleton, StatusChip, InfoHint],
  template: `
    @if (resource.isLoading() && !statement()) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (resource.status() === 'error' && !statement()) {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o extrato." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (statement(); as s) {
      @let ind = indicators()!;
      <app-page-header [title]="s.fileName ?? 'Extrato importado'" [subtitle]="'Importado em ' + (s.createdAt | appDate) + ' por ' + users.name(s.requestedBy) + ' · ' + (s.creditorId ? creditors.name(s.creditorId) : 'todos os credores')">
        @if (running() && canManage()) {
          <button class="btn btn-outline-danger d-inline-flex align-items-center gap-2" type="button" data-tour="statement-cancel" [disabled]="cancelling()" (click)="cancel(s)">
            <app-icon name="stop-circle" />
            {{ cancelling() ? 'Cancelando…' : 'Cancelar processamento' }}
          </button>
        }
        @if (retryable(s) && canManage()) {
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" data-tour="statement-retry" [disabled]="retrying()" (click)="retry(s)">
            <app-icon name="arrow-clockwise" />
            {{ retrying() ? 'Enviando…' : 'Tentar novamente' }}
          </button>
        }
        @if (s.divergentLines > 0) {
          <a class="btn btn-primary d-inline-flex align-items-center gap-2" routerLink="/pagamentos/divergencias" [queryParams]="{ situacao: 'OPEN' }">
            <app-icon name="list-check" />
            Tratar divergências
          </a>
        }
      </app-page-header>
      <div class="sd-status">
        <app-status-chip domain="statementImport" [status]="s.status" />
        <span class="app-muted">{{ size(s.fileSize) }} · SHA-256 <span class="app-mono" [attr.title]="s.fileSha256">{{ s.fileSha256.slice(0, 12) }}…</span></span>
        @if (s.finishedAt) {
          <span class="app-muted">· {{ s.status === 'CANCELLED' ? 'cancelado' : 'concluído' }} em {{ s.finishedAt | appDate }}</span>
        }
      </div>
      @if (running()) {
        <div class="progress" role="progressbar" aria-label="Conciliando"><div class="progress-bar progress-bar-striped progress-bar-animated w-100"></div></div>
        <p class="app-muted" role="status">Conciliando as linhas. Esta tela se atualiza sozinha.</p>
      }
      @if (s.status === 'CANCELLED') {
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="status">
          <app-icon name="stop-circle" />
          <span>
            O processamento foi cancelado.
            @if (s.totalLines > 0) {
              As {{ s.totalLines }} linhas já gravadas e as baixas delas ficam valendo.
            } @else {
              Nenhuma linha chegou a ser gravada.
            }
            @if (canManage()) {
              Use <strong>Tentar novamente</strong> para continuar de onde parou, sem baixa repetida.
            }
          </span>
        </div>
      }
      @if (s.status === 'FAILED') {
        <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">
          <app-icon name="exclamation-circle" />
          @if (s.failureCode === processingFailed) {
            <span>
              A importação parou{{ s.failureLine ? ' na linha ' + s.failureLine : '' }} depois de várias tentativas.
              @if (s.totalLines > 0) {
                As {{ s.totalLines }} linhas já gravadas e as baixas delas ficam valendo.
              }
              @if (canManage()) {
                Use <strong>Tentar novamente</strong> para continuar de onde parou, sem baixa repetida.
              }
            </span>
          } @else {
            <span>
              O arquivo foi recusado{{ s.failureLine ? ' na linha ' + s.failureLine : '' }}: {{ failure(s.failureCode) }}
              @if (s.totalLines === 0) {
                Nenhuma linha foi conciliada.
              }
              Corrija e importe de novo (a prévia aponta as linhas com problema).
              @if (canManage()) {
                Se o arquivo estiver certo, <strong>Tentar novamente</strong> lê o mesmo arquivo outra vez.
              }
            </span>
          }
        </div>
      }

      <section class="app-card" aria-labelledby="sd-kpis" data-tour="statement-indicators">
        <h2 class="app-card__title" id="sd-kpis">Indicadores do lote</h2>
        <div class="sd-kpis">
          <div class="sd-kpi"><span class="sd-kpi__label">Linhas lidas <app-info-hint indicator="payment.statement.lines" label="Linhas lidas" /></span><span class="sd-kpi__value">{{ s.totalLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Créditos tratados <app-info-hint indicator="payment.statement.credits" label="Créditos tratados" /></span><span class="sd-kpi__value">{{ ind.credits }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Conciliadas <app-info-hint indicator="payment.statement.reconciled" label="Conciliadas" /></span><span class="sd-kpi__value">{{ s.reconciledLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Confirmaram pagamento <app-info-hint indicator="payment.statement.confirmed" label="Confirmaram pagamento" /></span><span class="sd-kpi__value">{{ s.createdPayments }}</span></div>
          <div class="sd-kpi sd-kpi--warn"><span class="sd-kpi__label">Divergentes <app-info-hint indicator="payment.statement.divergent" label="Divergentes" /></span><span class="sd-kpi__value">{{ s.divergentLines }}</span></div>
          <div class="sd-kpi"><span class="sd-kpi__label">Ignoradas <app-info-hint indicator="payment.statement.ignored" label="Ignoradas" /></span><span class="sd-kpi__value">{{ s.ignoredLines }}</span></div>
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
        emptyIcon="receipt"
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
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
    .sd-kpi--warn .sd-kpi__value {
      color: var(--bs-warning-text-emphasis);
    }
    .sd-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
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
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly confirm = inject(ConfirmService);
  /** Cancelar e tentar de novo: mesma permissão de importar extrato; sem ela os botões não aparecem. */
  private readonly auth = inject(AuthService);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.RECONCILIATION_MANAGE));
  protected readonly retrying = signal(false);
  protected readonly cancelling = signal(false);
  protected readonly processingFailed = 'statement.processing_failed';
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
    this.destroyRef.onDestroy(() => refresh.unsubscribe());
  }

  /** Volta a importação com falha ou cancelada para a fila; o job retoma de onde parou e a tela acompanha o processamento. */
  protected retry(s: StatementImportView): void {
    if (this.retrying()) {
      return;
    }
    this.retrying.set(true);
    this.api
      .retryImport(s.id)
      .pipe(
        finalize(() => this.retrying.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (view) => {
          this.polls.set(0);
          this.notifications.success('Nova tentativa enviada. A importação continua de onde parou.');
          // a resposta já é a importação atualizada (RECEIVED): a tela passa a acompanhar o processamento
          this.resource.set(view);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.notifications.error((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
          this.resource.reload();
        },
      });
  }

  /** Com falha ou cancelada: aceita nova tentativa, que continua de onde parou. */
  protected retryable(s: StatementImportView): boolean {
    return s.status === 'FAILED' || s.status === 'CANCELLED';
  }

  /**
   * Para o processamento onde está (ação sensível: confirmação com motivo, que vai para a auditoria). O que já foi
   * gravado fica; o servidor espera o lote em andamento terminar e nenhum outro é gravado depois.
   */
  protected cancel(s: StatementImportView): void {
    if (this.cancelling()) {
      return;
    }
    this.confirm
      .confirm({
        title: 'Cancelar processamento',
        message: 'O processamento para onde está. As linhas já gravadas e as baixas delas ficam valendo; o restante do arquivo não é conciliado. A contagem do que foi gravado aparece na tela depois de cancelar.',
        warning: 'Depois de cancelar, "Tentar novamente" continua de onde parou, sem baixa repetida.',
        confirmLabel: 'Cancelar processamento',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo do cancelamento',
        reasonMaxLength: 200,
        details: [
          { label: 'Arquivo', value: s.fileName ?? 'Extrato importado' },
          // as contagens só são apuradas no fim (ou no cancelamento): durante o processamento estariam desatualizadas
          { label: 'Situação', value: s.status === 'RECEIVED' ? 'Na fila' : 'Em processamento' },
        ],
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((reason) => {
        this.cancelling.set(true);
        this.api
          .cancelImport(s.id, reason)
          .pipe(
            finalize(() => this.cancelling.set(false)),
            takeUntilDestroyed(this.destroyRef),
          )
          .subscribe({
            next: (view) => {
              this.notifications.success('Processamento cancelado. O que já foi gravado continua valendo.');
              // a resposta já é a importação cancelada, com as contagens das linhas gravadas
              this.resource.set(view);
            },
            error: (err: unknown) => {
              const e = toApiError(err);
              this.notifications.error((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
              this.resource.reload();
            },
          });
      });
  }

  protected failure(code: string | null): string {
    return (code && STATEMENT_FAILURE_LABELS[code]) || 'arquivo inválido.';
  }
}
