import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { debounceTime, distinctUntilChanged, finalize, map } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../shared/components/icon/icon';
import { BottomBar } from '../../../shared/directives/bottom-bar';
import { PdfImportsApi } from '../data/pdf-import.api';
import { PdfImportView, PdfRecordView, RecordDecision, RecordFacets, RecordFilter, RecordStatusFilter } from '../data/pdf-import.models';
import { confidenceText } from '../data/pdf-import.rules';
import {
  DEFAULT_FILTER,
  EMPTY_SELECTION,
  RecordSelection,
  STATUS_FILTERS,
  decisionSummary,
  decisionTarget,
  headerState,
  isSelected,
  selectAllInFilter,
  selectedCount,
  selectionBanner,
  toggleOne,
  togglePage,
} from '../data/pdf-record-selection';

const RECORD_PAGE = 50;
const VERBS: Record<RecordDecision, string> = { EXCLUDE: 'excluído(s) do lote', INCLUDE: 'reincluído(s) no lote', CONFIRM: 'confirmado(s)' };

/**
 * Títulos encontrados na tela de validação da Importação PDF: filtros por situação (com contadores), devedor e texto;
 * seleção por linha, pela página (checkbox do cabeçalho, com estado indeterminado) e "todos os N do filtro" no padrão do
 * Gmail; barra fixa de ações em massa (excluir do lote com motivo único, reincluir e, na leitura visual, confirmar).
 * O servidor aplica o permitido e informa o que ficou de fora — título de confiança baixa só é confirmado um a um, pelo
 * botão da própria linha (decisão 667).
 */
@Component({
  selector: 'app-pdf-records-table',
  imports: [Amount, BottomBar, Icon],
  template: `
    @let v = view();
    <section class="app-card rt" aria-labelledby="rt-title" data-tour="pdf-records">
      <div class="rt-head">
        <h2 class="app-card__title" id="rt-title">Títulos encontrados</h2>
      </div>

      <div class="rt-filters" data-tour="pdf-record-filters">
        <div class="rt-status" role="group" aria-label="Filtrar títulos pela situação">
          @for (f of statusFilters(); track f.value) {
            <button type="button" class="btn btn-sm btn-outline-secondary" [class.active]="filter().status === f.value" [attr.aria-pressed]="filter().status === f.value" (click)="setStatus(f.value)">
              {{ f.label }} <span class="rt-count">{{ count(f.value) }}</span>
            </button>
          }
        </div>
        <div class="rt-filter-fields">
          @if (debtors().length > 1) {
            <label class="visually-hidden" for="rt-debtor">Devedor</label>
            <select id="rt-debtor" class="form-select form-select-sm" [value]="filter().debtor ?? ''" (change)="setDebtor(val($event))">
              <option value="">Todos os devedores</option>
              @for (d of debtors(); track d.index) {
                <option [value]="d.index" [selected]="filter().debtor === d.index">Devedor {{ d.index }}{{ d.name ? ' · ' + d.name : '' }} ({{ d.records }})</option>
              }
            </select>
          }
          <label class="visually-hidden" for="rt-q">Buscar título, contrato, nome ou documento</label>
          <input id="rt-q" type="search" class="form-control form-control-sm" placeholder="Buscar título, contrato, nome ou CPF/CNPJ" maxlength="80" [value]="query()" (input)="query.set(val($event))" />
        </div>
      </div>

      <p class="visually-hidden" aria-live="polite">{{ notice() }}</p>
      @if (notice()) {
        <div class="alert alert-info py-1 px-2 rt-small d-flex align-items-center gap-2" role="status">
          <app-icon name="info-circle" /> {{ notice() }}
        </div>
      }

      @if (selectable() && banner(); as b) {
        <div class="rt-banner rt-small" role="status">
          <span>{{ b.text }}</span>
          @if (b.action === 'select-all') {
            <button type="button" class="btn btn-link btn-sm p-0" (click)="selectAll()">Selecionar todos os {{ total() }} do filtro</button>
          } @else {
            <button type="button" class="btn btn-link btn-sm p-0" (click)="clear()">Limpar seleção</button>
          }
        </div>
      }

      <div class="table-responsive">
        <table class="table table-sm table-hover align-middle rt-table">
          <caption class="visually-hidden">Títulos lidos do PDF; espaço marca a linha, o número mostra a origem no documento</caption>
          <thead>
            <tr>
              @if (selectable()) {
                <th scope="col" class="rt-check">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    data-testid="select-page"
                    [checked]="header() === 'all'"
                    [indeterminate]="header() === 'some'"
                    [disabled]="!pageOrdinals().length"
                    (change)="togglePageSelection()"
                    [attr.aria-label]="'Selecionar os ' + pageOrdinals().length + ' títulos desta página'"
                  />
                </th>
              }
              <th scope="col">#</th>
              <th scope="col">Devedor</th>
              <th scope="col">Título</th>
              <th scope="col">Vencimento</th>
              <th scope="col" class="text-end">Valor original</th>
              <th scope="col" class="text-end">Saldo</th>
              <th scope="col">Situação</th>
            </tr>
          </thead>
          <tbody>
            @for (r of records(); track r.ordinal) {
              <tr
                [class.table-active]="active() === r.ordinal"
                [class.rt-excluded]="r.decision === 'EXCLUDED'"
                [class.rt-selected]="selectable() && checked(r.ordinal)"
                [attr.tabindex]="selectable() ? 0 : null"
                (keydown.space)="onSpace($event, r.ordinal)"
              >
                @if (selectable()) {
                  <td class="rt-check">
                    <input class="form-check-input" type="checkbox" [checked]="checked(r.ordinal)" (change)="toggle(r.ordinal)" [attr.aria-label]="'Selecionar título ' + r.ordinal + (r.values['id_titulo'] ? ' (' + r.values['id_titulo'] + ')' : '')" />
                  </td>
                }
                <td>
                  <button type="button" class="btn btn-link btn-sm p-0" (click)="focus(r)" [attr.aria-label]="'Mostrar a origem do título ' + r.ordinal + ' no PDF'">{{ r.ordinal }}</button>
                </td>
                <td>
                  {{ r.values['nome'] || '—' }}
                  <div class="app-muted rt-small">{{ r.values['documento'] || '' }}</div>
                </td>
                <td>{{ r.values['id_titulo'] || '—' }}</td>
                <td>{{ r.values['vencimento'] || '—' }}</td>
                <td class="text-end"><app-amount [value]="amount(r.values['valor_principal'])" /></td>
                <td class="text-end"><app-amount [value]="amount(r.values['saldo'])" /></td>
                <td>
                  @if (r.decision === 'EXCLUDED') {
                    <span class="badge text-bg-secondary">Excluído</span>
                  } @else if (r.decision === 'CONFIRMED') {
                    <span class="badge text-bg-success">Confirmado</span>
                  } @else if (r.status === 'ERROR') {
                    <span class="badge text-bg-danger">Erro</span>
                  } @else if (r.status === 'WARNING') {
                    <span class="badge text-bg-warning">Aviso</span>
                  } @else {
                    <span class="badge bg-success-subtle text-success-emphasis">OK</span>
                  }
                  @if (r.origin === 'VISUAL') {
                    <span class="app-muted rt-small">leitura visual {{ confidence(r.confidence) }}</span>
                    @if (selectable() && lowPending(r)) {
                      <button type="button" class="btn btn-sm btn-outline-success rt-one" [disabled]="busy()" (click)="confirmOne(r)" [attr.aria-label]="'Confirmar a leitura do título ' + r.ordinal + ', conferida com a imagem'">Confirmar</button>
                    }
                  }
                  @for (f of r.findings; track $index) {
                    <div class="rt-small" [class.text-danger]="f.severity === 'ERROR'">{{ f.field ? f.field + ': ' : '' }}{{ f.message }}</div>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="8" class="app-muted">Nenhum título neste filtro.</td></tr>
            }
          </tbody>
        </table>
      </div>

      @if (page().totalPages > 1) {
        <nav class="rt-pager" aria-label="Páginas de títulos">
          <button class="btn btn-sm btn-outline-secondary" type="button" [disabled]="page().page === 0" (click)="pageIndex.set(page().page - 1)">Anterior</button>
          <span class="rt-small">Página {{ page().page + 1 }} de {{ page().totalPages }} · {{ total() }} título(s)</span>
          <button class="btn btn-sm btn-outline-secondary" type="button" [disabled]="page().page + 1 >= page().totalPages" (click)="pageIndex.set(page().page + 1)">Próxima</button>
        </nav>
      }

      @if (selectable() && selected() > 0) {
        <div class="rt-bulk" appBottomBar role="region" aria-label="Ações em massa" data-tour="pdf-bulk-bar" data-testid="bulk-bar">
          <strong class="rt-bulk__count">{{ selected() }} selecionado(s)</strong>
          <button class="btn btn-sm btn-danger" type="button" (click)="decide('EXCLUDE')" [disabled]="busy()"><app-icon name="x-circle" /> Excluir do lote</button>
          <button class="btn btn-sm btn-outline-secondary" type="button" (click)="decide('INCLUDE')" [disabled]="busy()"><app-icon name="arrow-counterclockwise" /> Reincluir</button>
          @if (v.mapOrigin === 'VISUAL') {
            <button class="btn btn-sm btn-outline-success" type="button" (click)="decide('CONFIRM')" [disabled]="busy()" title="Os de confiança baixa ficam de fora: confirme um a um">
              <app-icon name="check2-circle" /> Confirmar leitura
            </button>
          }
          <button class="btn btn-sm btn-link" type="button" (click)="clear()">Limpar seleção</button>
        </div>
      }

      @if (activeRecord(); as r) {
        @if (keys(r.info).length) {
          <div class="rt-info rt-small">
            <strong>Informações adicionais do título {{ r.ordinal }}</strong> (guardadas na importação, não vão ao lote):
            @for (k of keys(r.info); track k) {
              <span class="rt-info__item">{{ k.replaceAll('_', ' ') }}: {{ r.info[k] }}</span>
            }
          </div>
        }
      }
    </section>
  `,
  styles: `
    .rt-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .rt-filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .rt-status {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .rt-count {
      display: inline-block;
      min-width: 1.5em;
      margin-left: 2px;
      padding: 0 5px;
      border-radius: 999px;
      background: var(--bs-secondary-bg);
      color: var(--bs-body-color);
      font-size: var(--app-text-xs);
    }
    .rt-filter-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .rt-filter-fields .form-control,
    .rt-filter-fields .form-select {
      width: auto;
      min-width: 200px;
      max-width: 100%;
    }
    .rt-banner {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 6px 8px;
      margin-bottom: 8px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-secondary-bg);
    }
    .rt-small {
      font-size: var(--app-text-sm);
    }
    .rt-table {
      font-size: var(--app-text-sm);
    }
    .rt-check {
      width: 2.25rem;
    }
    .rt-check .form-check-input {
      width: 1.15em;
      height: 1.15em;
    }
    tr[tabindex]:focus-visible {
      outline: 2px solid var(--bs-primary);
      outline-offset: -2px;
    }
    .rt-selected > td {
      background: var(--bs-primary-bg-subtle);
    }
    .rt-excluded td {
      text-decoration: line-through;
      color: var(--bs-secondary-color);
    }
    .rt-excluded td:first-child,
    .rt-excluded td:last-child {
      text-decoration: none;
    }
    .rt-one {
      margin-left: 6px;
      padding: 0 6px;
    }
    .rt-pager {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
    }
    .rt-bulk {
      position: sticky;
      bottom: 0;
      z-index: 5;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin: 8px calc(-1 * var(--app-card-padding, 16px)) calc(-1 * var(--app-card-padding, 16px));
      padding: 10px var(--app-card-padding, 16px);
      border-top: 1px solid var(--bs-border-color);
      border-radius: 0 0 var(--bs-border-radius-lg) var(--bs-border-radius-lg);
      background: var(--app-surface);
      box-shadow: 0 -4px 12px rgb(0 0 0 / 8%);
    }
    .rt-bulk__count {
      margin-right: auto;
    }
    .rt-info {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      margin-top: 8px;
    }
    .rt-info__item {
      color: var(--bs-secondary-color);
    }
    @media (max-width: 576px) {
      .rt-bulk__count {
        width: 100%;
      }
      .rt-filter-fields,
      .rt-filter-fields .form-control,
      .rt-filter-fields .form-select {
        width: 100%;
      }
    }
  `,
})
export class PdfRecordsTable {
  readonly view = input.required<PdfImportView>();
  readonly canExecute = input(false);
  /** Título escolhido para mostrar a origem no PDF. */
  readonly focused = output<PdfRecordView>();
  /** Estado novo da importação depois de uma decisão (totais e contadores recalculados no servidor). */
  readonly changed = output<PdfImportView>();
  /** A importação mudou desde que a tela foi aberta (revisão ou filtro): o pai recarrega. */
  readonly stale = output<void>();

  private readonly api = inject(PdfImportsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly confidence = confidenceText;
  protected readonly keys = Object.keys;

  protected readonly filter = signal<RecordFilter>(DEFAULT_FILTER);
  protected readonly query = signal('');
  protected readonly pageIndex = signal(0);
  protected readonly selection = signal<RecordSelection>(EMPTY_SELECTION);
  protected readonly notice = signal('');
  protected readonly busy = signal(false);
  protected readonly active = signal<number | null>(null);
  private readonly refresh = signal(0);

  protected readonly selectable = computed(() => this.canExecute() && this.view().status === 'AWAITING_VALIDATION');

  private readonly stateKey = computed(() => {
    const v = this.view();
    return `${v.id}:${v.mapRevision}:${v.summary?.excluded ?? 0}:${v.summary?.confirmed ?? 0}:${this.refresh()}`;
  });

  private readonly recordsResource = rxResource({
    params: () => ({ key: this.stateKey(), id: this.view().id, filter: this.filter(), page: this.pageIndex() }),
    stream: ({ params }) => this.api.records(params.id, params.filter, { page: params.page, size: RECORD_PAGE }),
  });
  private readonly facetsResource = rxResource({
    params: () => ({ key: this.stateKey(), id: this.view().id, debtor: this.filter().debtor, q: this.filter().q }),
    stream: ({ params }) => this.api.facets(params.id, { status: 'ALL', debtor: params.debtor, q: params.q }),
  });

  protected readonly page = computed(() =>
    this.recordsResource.hasValue() ? this.recordsResource.value() : { content: [], page: 0, size: RECORD_PAGE, totalElements: 0, totalPages: 0 },
  );
  protected readonly records = computed(() => this.page().content);
  protected readonly total = computed(() => this.page().totalElements);
  protected readonly pageOrdinals = computed(() => this.records().map((r) => r.ordinal));
  protected readonly facets = computed<RecordFacets | null>(() => (this.facetsResource.hasValue() ? this.facetsResource.value() : null));
  protected readonly debtors = computed(() => this.facets()?.debtors ?? []);
  protected readonly statusFilters = computed(() =>
    STATUS_FILTERS.filter((f) => f.value !== 'LOW_CONFIDENCE' || this.view().mapOrigin === 'VISUAL' || this.count('LOW_CONFIDENCE') > 0),
  );
  protected readonly header = computed(() => headerState(this.selection(), this.pageOrdinals()));
  protected readonly selected = computed(() => selectedCount(this.selection(), this.total()));
  protected readonly banner = computed(() => selectionBanner(this.selection(), this.pageOrdinals(), this.total()));
  protected readonly activeRecord = computed(() => this.records().find((r) => r.ordinal === this.active()) ?? null);

  constructor() {
    // texto livre: aplica depois de uma pausa na digitação
    toObservable(this.query)
      .pipe(
        debounceTime(300),
        map((q) => q.trim()),
        distinctUntilChanged(),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((q) => {
        if ((this.filter().q ?? '') !== q) {
          this.changeFilter({ ...this.filter(), q: q || null });
        }
      });
  }

  protected val(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected count(status: RecordStatusFilter): number {
    return this.facets()?.byStatus[status] ?? 0;
  }

  protected checked(ordinal: number): boolean {
    return isSelected(this.selection(), ordinal);
  }

  /** Confiança baixa ainda não decidida: só se confirma um a um, pelo botão da linha. */
  protected lowPending(r: PdfRecordView): boolean {
    return r.lowConfidence && r.decision === null;
  }

  protected amount(value: string | undefined): number | null {
    if (value === undefined || value === null || value === '') {
      return null;
    }
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }

  protected setStatus(status: RecordStatusFilter): void {
    this.changeFilter({ ...this.filter(), status });
  }

  protected setDebtor(value: string): void {
    this.changeFilter({ ...this.filter(), debtor: value ? Number(value) : null });
  }

  /** Filtro novo: volta à 1ª página e limpa a seleção, avisando. */
  private changeFilter(next: RecordFilter): void {
    const hadSelection = selectedCount(this.selection(), this.total()) > 0;
    this.filter.set(next);
    this.pageIndex.set(0);
    this.selection.set(EMPTY_SELECTION);
    this.notice.set(hadSelection ? 'A seleção foi limpa porque o filtro mudou.' : '');
  }

  protected toggle(ordinal: number): void {
    this.selection.set(toggleOne(this.selection(), ordinal, this.pageOrdinals()));
    this.notice.set('');
  }

  protected onSpace(event: Event, ordinal: number): void {
    // a própria linha com foco: espaço marca (no checkbox, o navegador já trata)
    if (event.target === event.currentTarget && this.selectable()) {
      event.preventDefault();
      this.toggle(ordinal);
    }
  }

  protected togglePageSelection(): void {
    this.selection.set(togglePage(this.selection(), this.pageOrdinals()));
    this.notice.set('');
  }

  protected selectAll(): void {
    this.selection.set(selectAllInFilter());
  }

  protected clear(): void {
    this.selection.set(EMPTY_SELECTION);
    this.notice.set('');
  }

  protected focus(r: PdfRecordView): void {
    this.active.set(r.ordinal);
    this.focused.emit(r);
  }

  protected confirmOne(r: PdfRecordView): void {
    this.confirm
      .confirm({
        title: `Confirmar a leitura do título ${r.ordinal}`,
        message: 'Leitura visual com confiança baixa: confira cada valor com a imagem da página antes de confirmar.',
        confirmLabel: 'Confirmar',
        reason: 'required',
        reasonLabel: 'Justificativa',
        reasonMaxLength: 500,
      })
      .subscribe((reason) => this.run({ ordinals: [r.ordinal] }, 'CONFIRM', reason));
  }

  protected decide(decision: RecordDecision): void {
    const n = this.selected();
    const target = decisionTarget(this.selection(), this.filter(), this.total());
    if (decision === 'INCLUDE') {
      this.confirm
        .confirm({ title: 'Reincluir no lote', message: `${n} título(s) selecionado(s) voltam para o lote (os que não estavam excluídos ficam como estão).`, confirmLabel: 'Reincluir' })
        .subscribe(() => this.run(target, decision, null));
      return;
    }
    this.confirm
      .confirm({
        title: decision === 'EXCLUDE' ? 'Excluir do lote' : 'Confirmar leitura visual',
        message:
          decision === 'EXCLUDE'
            ? `${n} título(s) não vão para o lote. O motivo vale para todos e fica na auditoria de cada título. Se o documento somar esses títulos, os totais passam a pedir justificativa.`
            : `Você conferiu ${n} título(s) com a imagem da página. Os de confiança baixa ficam de fora: confirme cada um pelo botão da linha.`,
        confirmLabel: decision === 'EXCLUDE' ? 'Excluir' : 'Confirmar',
        destructive: decision === 'EXCLUDE',
        reason: 'required',
        reasonLabel: decision === 'EXCLUDE' ? 'Motivo (o mesmo para todos)' : 'Justificativa',
        reasonMaxLength: 500,
      })
      .subscribe((reason) => this.run(target, decision, reason));
  }

  private run(target: Parameters<PdfImportsApi['decide']>[1], decision: RecordDecision, justification: string | null): void {
    const v = this.view();
    this.busy.set(true);
    this.api
      .decide(v.id, target, decision, justification, v.mapRevision)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (result) => {
          this.selection.set(EMPTY_SELECTION);
          const text = decisionSummary(result.applied, VERBS[decision], result.skipped);
          this.notice.set(text);
          if (result.skipped.length) {
            this.notifications.info(text);
          } else {
            this.notifications.success(text);
          }
          this.refresh.update((n) => n + 1);
          this.changed.emit(result.importView);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.notifications.error(e.detail || userMessage(e));
          if (e.status === 409) {
            this.selection.set(EMPTY_SELECTION);
            this.refresh.update((n) => n + 1);
            this.stale.emit();
          }
        },
      });
  }
}
