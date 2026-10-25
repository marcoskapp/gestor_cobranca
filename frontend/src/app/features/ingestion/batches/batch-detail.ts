import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
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
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { ImportBatchesApi, ImportReviewsApi } from '../data/ingestion.api';
import { OPTION_LABELS } from '../data/review.rules';
import { MODE_LABELS, SOURCE_LABELS, UPLOAD_ERROR_LABELS, isTerminal, progressText, sizeText } from '../data/ingestion.labels';
import { ImportBatchView, IssueSeverity } from '../data/ingestion.models';
import { POLL_MS } from './batch-list';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/**
 * Ações disponíveis pela situação do lote. Importar uma prévia ou reprocessar um arquivo passa sempre pela conferência
 * (formato, totais e alertas); a prévia de uma conferência é acompanhada pela própria conferência.
 */
export function batchActions(b: Pick<ImportBatchView, 'status' | 'mode' | 'source' | 'cancelRequestedAt' | 'reviewId'>): ('cancel' | 'review' | 'import' | 'reprocess')[] {
  if (b.reviewId && b.mode === 'VALIDATE_ONLY') {
    return ['review'];
  }
  if (!isTerminal(b.status)) {
    return b.cancelRequestedAt || b.status === 'AWAITING_FORMAT' ? [] : ['cancel'];
  }
  if (b.source === 'API') {
    return b.reviewId ? ['review'] : [];
  }
  const own: ('review' | 'import' | 'reprocess')[] = b.status === 'VALIDATED' ? ['import'] : ['reprocess'];
  return b.reviewId ? ['review', ...own] : own;
}

/**
 * Lote de importação: progresso (atualizado enquanto processa), contadores, ocorrências por linha
 * com valores mascarados, relatório CSV, arquivo original (dado pessoal: permissão própria e
 * registro do download), cancelamento, leitura aplicada aos valores e o caminho para a conferência
 * (importar a prévia ou reprocessar o arquivo).
 */
@Component({
  selector: 'app-batch-detail',
  imports: [
    NgbTooltip,
    Icon,
    RouterLink,
    Amount,
    CellDef,
    DataTable,
    EmptyState,
    PageHeader,
    Skeleton,
    StatusChip,
    AppDatePipe,
  ],
  template: `
    @if (batch.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (batch.status() === 'error' && !current()) {
      <app-empty-state icon="file-earmark-arrow-up" tone="error" title="Não foi possível abrir o lote." message="O lote pode não existir ou o credor estar fora do seu escopo.">
        <button class="btn btn-outline-primary" type="button" (click)="batch.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/importacao">Voltar para importações</a>
      </app-empty-state>
    }
    @if (current(); as b) {
      <app-page-header [title]="b.fileName || 'Registros via API'" [subtitle]="creditors.name(b.creditorId) + ' · ' + portfolios.name(b.portfolioId) + ' · ' + modeLabels[b.mode]">
        <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" />Importações</a>
        @if (b.issuesRecorded) {
          <button class="btn btn-outline-primary" type="button" (click)="report(b)" ngbTooltip="CSV com valores mascarados (exportação registrada)">
            <app-icon name="download" />
            Relatório de ocorrências
          </button>
        }
        @if (canOriginal() && b.fileName) {
          <button class="btn btn-outline-primary" type="button" (click)="original(b)">
            <app-icon name="unlock" />
            Arquivo original
          </button>
        }
        @if (canExecute()) {
          @for (a of actions(); track a) {
            @switch (a) {
              @case ('cancel') {
                <button class="btn btn-outline-primary" type="button" (click)="cancel(b)"><app-icon name="x-circle" />Cancelar</button>
              }
              @case ('review') {
                <a class="btn btn-primary" [routerLink]="['/importacao/conferencias', b.reviewId]"><app-icon name="card-checklist" />Abrir a conferência</a>
              }
              @case ('import') {
                <button class="btn btn-primary" type="button" (click)="review(b)"><app-icon name="card-checklist" />Conferir e importar</button>
              }
              @case ('reprocess') {
                <button class="btn btn-outline-primary" type="button" (click)="review(b)"><app-icon name="arrow-counterclockwise" />Conferir e reprocessar</button>
              }
            }
          }
        }
      </app-page-header>

      <section class="app-card bd-status" aria-label="Situação do lote" data-tour="batch-status" aria-live="polite">
        <app-status-chip domain="importBatch" [status]="b.status" />
        @if (!terminal(b)) {
          <div class="progress bd-progress" role="progressbar" [attr.aria-label]="progress(b)" aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="b.progressPercent">
            <div class="progress-bar" [class.progress-bar-striped]="b.progressPercent === null" [class.progress-bar-animated]="b.progressPercent === null" [style.width.%]="b.progressPercent ?? 100"></div>
          </div>
          <span>{{ progress(b) }}</span>
          @if (b.cancelRequestedAt) {
            <span class="app-muted">· cancelamento pedido; para ao fim do bloco corrente</span>
          }
        } @else {
          <span class="app-muted">{{ b.finishedAt ? 'Encerrado em ' + (b.finishedAt | appDate) : '' }}</span>
        }
        @if (b.statusReason) {
          <span class="bd-reason">{{ b.statusReason }}</span>
        }
      </section>

      <section class="bd-kpis" aria-label="Contadores do lote" data-tour="batch-counters">
        @for (k of kpis(); track k.label) {
          <div class="bd-kpi" [class.bd-kpi--alert]="k.alert">
            <span class="bd-kpi__label">{{ k.label }}</span>
            <span class="bd-kpi__value app-numeric">{{ k.value }}</span>
          </div>
        }
        <div class="bd-kpi">
          <span class="bd-kpi__label">Principal {{ b.mode === 'VALIDATE_ONLY' ? 'validado' : 'gravado' }}</span>
          <span class="bd-kpi__value"><app-amount [value]="b.counters.principalTotal" /></span>
        </div>
      </section>

      <section class="app-card" aria-labelledby="bd-file">
        <h2 class="app-card__title" id="bd-file">Recebimento</h2>
        <dl class="app-definition-list bd-small">
          <dt>Origem</dt>
          <dd>{{ sourceLabels[b.source] }} · layout {{ b.layoutCode }} v{{ b.layoutVersion }}</dd>
          @if (b.fileName) {
            <dt>Arquivo</dt>
            <dd>{{ b.fileName }} · {{ size(b.fileSize) }}</dd>
          }
          @if (b.fileSha256) {
            <dt>SHA-256</dt>
            <dd class="app-mono">{{ b.fileSha256 }}</dd>
          }
          <dt>Recebido</dt>
          <dd>{{ b.createdAt | appDate }}{{ b.startedAt ? ' · iniciado ' + (b.startedAt | appDate: 'time') : '' }}</dd>
          @if (b.forced) {
            <dt>Reprocessamento</dt>
            <dd>{{ b.forceReason }}</dd>
          }
          @if (b.reprocessOfBatchId) {
            <dt>Origem do arquivo</dt>
            <dd><a [routerLink]="['/importacao', b.reprocessOfBatchId]">{{ b.reviewId && b.mode === 'IMPORT' ? 'prévia conferida' : 'lote anterior' }}</a></dd>
          }
          @if (b.reviewId) {
            <dt>Conferência</dt>
            <dd><a [routerLink]="['/importacao/conferencias', b.reviewId]">formato, totais e alertas conferidos</a></dd>
          }
          @if (formats(b).length) {
            <dt>Leitura dos valores</dt>
            <dd>
              @for (f of formats(b); track f.column) {
                <div>{{ f.column }}: {{ f.label }}</div>
              }
            </dd>
          }
          @if (b.duplicateOfBatchId) {
            <dt>Mesmo conteúdo de</dt>
            <dd><a [routerLink]="['/importacao', b.duplicateOfBatchId]">lote já importado</a></dd>
          }
        </dl>
      </section>

      <section class="app-card" aria-labelledby="bd-issues" data-tour="batch-issues">
        <div class="bd-head">
          <h2 class="app-card__title" id="bd-issues">Ocorrências por linha ({{ b.issuesRecorded }})</h2>
          <div class="btn-group btn-group-sm flex-wrap" role="group" aria-label="Filtrar ocorrências">
            <input type="radio" class="btn-check" name="bd-severity" id="bd-severity-all" [checked]="severity() === ''" (change)="severity.set('')" />
            <label class="btn btn-outline-primary" for="bd-severity-all">Todas</label>
            <input type="radio" class="btn-check" name="bd-severity" id="bd-severity-error" [checked]="severity() === 'ERROR'" (change)="severity.set('ERROR')" />
            <label class="btn btn-outline-primary" for="bd-severity-error">Erros</label>
            <input type="radio" class="btn-check" name="bd-severity" id="bd-severity-warning" [checked]="severity() === 'WARNING'" (change)="severity.set('WARNING')" />
            <label class="btn btn-outline-primary" for="bd-severity-warning">Avisos</label>
          </div>
        </div>
        @if (b.issuesTruncated) {
          <div class="alert alert-warning d-flex align-items-start gap-2 bd-small" role="note">
            <app-icon name="info-circle" />
            <span>Há mais ocorrências do que as gravadas (limite por lote); os contadores consideram todas.</span>
          </div>
        }
        <app-data-table
          caption="Ocorrências do lote"
          [rows]="issues.rows()"
          [columns]="issueColumns"
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
          <ng-template appCell="message" [appCellOf]="issues.rows()" let-i>
            {{ i.message }}
            <div class="app-muted app-mono">{{ i.code }}</div>
          </ng-template>
          <ng-template appCell="value" [appCellOf]="issues.rows()" let-i><span class="app-mono">{{ i.valueExcerpt || '—' }}</span></ng-template>
        </app-data-table>
      </section>
    }
  `,
  styles: `
    .bd-status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: var(--app-gap);
    }
    .bd-progress {
      width: 240px;
    }
    .bd-reason {
      flex-basis: 100%;
      color: var(--bs-secondary-color);
    }
    .bd-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .bd-kpi {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 14px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .bd-kpi--alert .bd-kpi__value {
      color: var(--bs-danger-text-emphasis);
    }
    .bd-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      color: var(--bs-secondary-color);
    }
    .bd-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .bd-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .bd-small {
      font-size: var(--app-text-sm);
    }
  `,
})
export class BatchDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ImportBatchesApi);
  private readonly reviews = inject(ImportReviewsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly sourceLabels = SOURCE_LABELS;
  protected readonly modeLabels = MODE_LABELS;
  protected readonly progress = progressText;
  protected readonly size = sizeText;
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));
  protected readonly canOriginal = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));

  protected readonly batch = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.batch, undefined);
  protected readonly actions = computed(() => (this.current() ? batchActions(this.current()!) : []));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.current()?.portfolioId]);

  protected readonly severity = signal<'' | IssueSeverity>('');
  private readonly issueFilter = computed(() => ({ id: this.id(), severity: this.severity() || null, done: this.current() ? isTerminal(this.current()!.status) : false }));
  protected readonly issues = pagedQuery(this.issueFilter, (f, page) => this.api.issues(f.id, f.severity, page), 50);

  protected readonly issueColumns: TableColumn[] = [
    { key: 'row', header: 'Linha', align: 'end' },
    { key: 'severity', header: 'Tipo' },
    { key: 'field', header: 'Coluna' },
    { key: 'message', header: 'Ocorrência' },
    { key: 'value', header: 'Valor recebido' },
  ];

  protected readonly kpis = computed(() => {
    const c = this.current()?.counters;
    if (!c) {
      return [];
    }
    const n = new Intl.NumberFormat('pt-BR');
    return [
      { label: 'Linhas lidas', value: n.format(c.rowsRead), alert: false },
      { label: 'Linhas válidas', value: n.format(c.rowsValid), alert: false },
      { label: 'Linhas com erro', value: n.format(c.rowsWithError), alert: c.rowsWithError > 0 },
      { label: 'Avisos', value: n.format(c.warnings), alert: false },
      { label: 'Devedores novos / atualizados', value: `${n.format(c.debtorsCreated)} / ${n.format(c.debtorsUpdated)}`, alert: false },
      { label: 'Títulos novos / atualizados', value: `${n.format(c.titlesCreated)} / ${n.format(c.titlesUpdated)}`, alert: false },
      { label: 'Sem alteração / ignorados', value: `${n.format(c.titlesUnchanged)} / ${n.format(c.titlesSkipped)}`, alert: false },
      { label: 'Suspensos pela carga', value: n.format(c.titlesSuspended), alert: false },
      ...(c.rowsExcluded ? [{ label: 'Excluídas na conferência', value: n.format(c.rowsExcluded), alert: false }] : []),
    ];
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.fileName ?? null));
    const timer = setInterval(() => {
      const b = this.current();
      if (b && !isTerminal(b.status) && !this.batch.isLoading()) {
        this.batch.reload();
      }
    }, POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected terminal(b: ImportBatchView): boolean {
    return isTerminal(b.status);
  }

  protected report(b: ImportBatchView): void {
    this.api.downloadIssues(b.id).subscribe((name) => this.notifications.success(`Relatório baixado (${name}).`));
  }

  protected original(b: ImportBatchView): void {
    this.confirm
      .confirm({
        title: 'Baixar arquivo original',
        message: 'O arquivo contém dados pessoais em claro dos devedores. O download fica registrado no log de acesso.',
        confirmLabel: 'Baixar',
        details: [
          { label: 'Arquivo', value: b.fileName ?? '' },
          { label: 'Tamanho', value: sizeText(b.fileSize) },
        ],
        warning: 'Não envie o arquivo por canais não autorizados e apague a cópia local depois do uso.',
      })
      .subscribe(() => this.api.downloadOriginal(b.id, b.fileName ?? 'arquivo').subscribe());
  }

  protected cancel(b: ImportBatchView): void {
    this.confirm
      .confirm({
        title: 'Cancelar lote',
        message: 'Na fila, o lote é cancelado de imediato; em processamento, para ao fim do bloco corrente e o que já foi gravado permanece.',
        confirmLabel: 'Cancelar lote',
        destructive: true,
        reason: 'required',
        details: [{ label: 'Arquivo', value: b.fileName ?? 'Registros via API' }, { label: 'Progresso', value: progressText(b) }],
      })
      .subscribe((reason) =>
        this.api.cancel(b.id, reason).subscribe((saved) => {
          this.batch.set(saved);
          this.notifications.success('Cancelamento registrado.');
        }),
      );
  }

  /** Importar ou reprocessar o arquivo passa pela conferência (nova prévia do mesmo arquivo). */
  protected review(b: ImportBatchView): void {
    this.reviews.startFromBatch(b.id).subscribe({
      next: (created) => {
        this.notifications.success('Conferência iniciada com o mesmo arquivo.');
        void this.router.navigate(['/importacao/conferencias', created.id]);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        this.notifications.error((e.code && UPLOAD_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }

  protected formats(b: ImportBatchView): { column: string; label: string }[] {
    return Object.entries(b.numberFormat ?? {}).map(([column, option]) => ({ column, label: OPTION_LABELS[option] ?? option }));
  }
}
