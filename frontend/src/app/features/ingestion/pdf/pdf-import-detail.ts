import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { sizeText } from '../data/ingestion.labels';
import { PdfImportsApi } from '../data/pdf-import.api';
import { ExtractionMap, PdfImportView, PdfRecordView, RegionView } from '../data/pdf-import.models';
import { ORIGIN_LABELS, canEdit, needsPolling, targetLabel } from '../data/pdf-import.rules';
import { PdfMapEditor } from './pdf-map-editor';
import { PdfPageViewer } from './pdf-page-viewer';
import { PdfRecordsTable } from './pdf-records-table';

const POLL_MS = 3000;


/**
 * Tela intermediária da Importação PDF inteligente: o PDF com as regiões lidas, o "mapa" em linguagem simples (e
 * editável), os títulos encontrados com erros e alertas, a conferência dos totais do documento e as ações "Concordo,
 * importar", "Salvar mapa para este credor" e "Descartar". A aprovação abre a conferência da importação de sempre.
 */
@Component({
  selector: 'app-pdf-import-detail',
  imports: [RouterLink, DecimalPipe, Amount, EmptyState, Icon, PageHeader, Skeleton, StatusChip, AppDatePipe, PdfMapEditor, PdfPageViewer, PdfRecordsTable],
  template: `
    @if (resource.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error' && !current()) {
      <app-empty-state icon="file-earmark-pdf" tone="error" title="Não foi possível abrir a importação PDF." message="Ela pode não existir ou o credor estar fora do seu escopo.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/importacao">Voltar para importações</a>
      </app-empty-state>
    }
    @if (current(); as v) {
      <app-page-header title="Importação PDF inteligente" [subtitle]="v.fileName + ' · ' + creditors.name(v.creditorId) + ' · ' + portfolios.name(v.portfolioId)">
        <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" /> Importações</a>
        @if (canSensitive() && v.fileAvailable) {
          <button class="btn btn-outline-primary" type="button" (click)="download(v)"><app-icon name="download" /> PDF original</button>
        }
        @if (canExecute() && editable() && v.textLayer && v.status !== 'MAPPING') {
          <button class="btn btn-outline-primary" type="button" (click)="newMap(v)" [disabled]="busy()"><app-icon name="robot" /> Pedir novo mapa à IA</button>
        }
        @if (canExecute() && open()) {
          <button class="btn btn-outline-danger" type="button" (click)="discard(v)" [disabled]="busy()"><app-icon name="x-circle" /> Descartar</button>
        }
      </app-page-header>

      <div class="pd-status" role="status" aria-live="polite" data-tour="pdf-status">
        <app-status-chip domain="pdfImport" [status]="v.status" />
        @if (v.mapOrigin) {
          <span class="badge rounded-pill border bg-info-subtle text-info-emphasis border-info-subtle fw-normal pd-badge">{{ originLabel(v.mapOrigin) }}{{ v.templateName ? ': ' + v.templateName : '' }}</span>
        }
        <span class="app-muted">{{ v.pages }} página(s) · {{ size(v.fileSize) }} · {{ v.textLayer ? 'com camada de texto' : 'sem camada de texto (digitalizado)' }} · mapa rev. {{ v.mapRevision }}</span>
        @if (v.usage.calls) {
          <span class="app-muted">· IA: {{ v.usage.calls }} chamada(s), {{ v.usage.inputTokens + v.usage.outputTokens | number }} tokens, {{ v.usage.currency || 'USD' }} {{ v.usage.cost | number: '1.4-4' }}</span>
        }
        <span class="app-muted">· recebido em {{ v.createdAt | appDate }}</span>
      </div>

      @switch (v.status) {
        @case ('RECEIVED') {
          <div class="alert alert-info d-flex gap-2" role="status"><app-icon name="hourglass-top" /><span>PDF recebido. Procurando um mapa salvo do credor ou pedindo a proposta à IA…</span></div>
        }
        @case ('MAPPING') {
          <div class="alert alert-info d-flex gap-2" role="status"><app-icon name="hourglass-top" /><span>Lendo o PDF e montando o mapa de extração. Isso leva de alguns segundos a poucos minutos.</span></div>
        }
        @case ('FAILED') {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ v.failureMessage || 'Não foi possível ler o PDF.' }}</span></div>
        }
        @case ('DISCARDED') {
          <div class="alert alert-secondary d-flex gap-2" role="status"><app-icon name="x-circle" /><span>Descartada em {{ v.discardedAt | appDate }}: {{ v.discardReason }}</span></div>
        }
        @case ('APPROVED') {
          <div class="alert alert-success d-flex flex-wrap align-items-center gap-2" role="status">
            <app-icon name="check2-circle" />
            <span>Mapa aprovado em {{ v.approvedAt | appDate }}. O lote segue na conferência da importação (formato, linhas, grandes totais e alertas).</span>
            @if (v.reviewId) {
              <a class="btn btn-sm btn-success ms-auto" [routerLink]="['/importacao/conferencias', v.reviewId]">Abrir a conferência <app-icon name="arrow-right" /></a>
            }
          </div>
        }
        @case ('AWAITING_MAP') {
          <div class="alert alert-warning d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ v.failureMessage || 'Ainda não há mapa para este PDF.' }} O sistema reaplica ao texto do PDF o mapa que você descrever abaixo.</span></div>
        }
      }

      @if (v.summary && (v.status === 'AWAITING_VALIDATION' || v.status === 'APPROVED')) {
        <section class="app-card pd-conference" aria-labelledby="pd-conf" data-tour="pdf-conference">
          <h2 class="app-card__title" id="pd-conf">Conferência com o documento</h2>
          <dl class="pd-kpis">
            <div><dt>Títulos encontrados</dt><dd class="app-numeric">{{ v.summary.records | number }}</dd></div>
            <div><dt>A importar</dt><dd class="app-numeric">{{ v.summary.recordsToImport | number }}</dd></div>
            <div><dt>Devedores</dt><dd class="app-numeric">{{ v.summary.debtors | number }}</dd></div>
            <div><dt>Valor original</dt><dd><app-amount [value]="v.summary.principalTotal" /></dd></div>
            <div><dt>Saldo</dt><dd><app-amount [value]="v.summary.balanceTotal" /></dd></div>
            <div><dt>Com erro</dt><dd class="app-numeric" [class.text-danger]="v.summary.recordsWithError">{{ v.summary.recordsWithError | number }}</dd></div>
          </dl>
          @if (v.totals.length) {
            <div class="table-responsive">
              <table class="table table-sm align-middle pd-totals">
                <caption class="visually-hidden">Totais do documento comparados com a soma extraída</caption>
                <thead>
                  <tr>
                    <th scope="col">Total no documento</th>
                    <th scope="col">Abrangência</th>
                    <th scope="col" class="text-end">Documento</th>
                    <th scope="col" class="text-end">Extraído</th>
                    <th scope="col" class="text-end">Diferença</th>
                    <th scope="col">Confere</th>
                  </tr>
                </thead>
                <tbody>
                  @for (t of v.totals; track $index) {
                    <tr [class.table-danger]="!t.matches">
                      <td>
                        <button type="button" class="btn btn-link btn-sm p-0" (click)="show(t.source ? [t.source] : [])" [disabled]="!t.source">"{{ t.label }}"{{ t.column ? ' · ' + t.column : '' }}</button>
                        <div class="app-muted pd-small">{{ t.target === 'quantidade' ? 'quantidade de títulos' : field(t.target) }}</div>
                      </td>
                      <td>{{ t.scope === 'DOCUMENT' ? 'documento' : 'devedor ' + t.scope }}</td>
                      <td class="text-end">@if (t.target === 'quantidade') { {{ t.document }} } @else { <app-amount [value]="t.document" /> }</td>
                      <td class="text-end">@if (t.target === 'quantidade') { {{ t.extracted }} } @else { <app-amount [value]="t.extracted" /> }</td>
                      <td class="text-end">@if (t.target === 'quantidade') { {{ t.difference }} } @else { <app-amount [value]="t.difference" /> }</td>
                      <td>
                        @if (t.matches) {
                          <span class="text-success"><app-icon name="check-lg" /> sim</span>
                        } @else {
                          <span class="text-danger"><app-icon name="exclamation-lg" /> não</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <p class="app-muted pd-small">O mapa não indica totais impressos no documento: confira a soma com cuidado na conferência de grandes totais.</p>
          }
          @if (docFindings().length) {
            <ul class="pd-findings">
              @for (f of docFindings(); track $index) {
                <li [class.text-danger]="f.severity === 'ERROR'">
                  <app-icon [name]="f.severity === 'ERROR' ? 'exclamation-circle' : 'info-circle'" />
                  @if (f.source) {
                    <button type="button" class="btn btn-link btn-sm p-0" (click)="show([f.source])">{{ f.message }}</button>
                  } @else {
                    {{ f.message }}
                  }
                </li>
              }
            </ul>
          }
        </section>
      }

      @if (v.status === 'AWAITING_VALIDATION' || v.status === 'APPROVED' || v.status === 'AWAITING_MAP') {
        <div class="pd-layout">
          <section class="app-card pd-pdf" aria-label="PDF">
            @if (!canSeePages() && v.fileAvailable) {
              <p class="app-muted">A imagem das páginas do PDF original fica com quem executa importações ou vê dados sensíveis de devedores.</p>
            } @else if (v.textLayer && v.fileAvailable) {
              <app-pdf-page-viewer [importId]="v.id" [pages]="v.pages" [pageSizes]="v.pageSizes" [regions]="v.regions" [highlight]="highlight()" [(page)]="page" />
            } @else if (v.fileAvailable) {
              <app-pdf-page-viewer [importId]="v.id" [pages]="v.pages" [pageSizes]="v.pageSizes" [regions]="[]" [highlight]="[]" [(page)]="page" />
              <p class="app-muted pd-small">PDF digitalizado: sem coordenadas de texto para destacar. Compare cada título com a imagem.</p>
            } @else {
              <p class="app-muted">O arquivo PDF já foi descartado pela política de retenção.</p>
            }
          </section>
          <section class="app-card pd-map" aria-labelledby="pd-map" data-tour="pdf-map">
            <div class="pd-map__head">
              <h2 class="app-card__title" id="pd-map">O mapa lido pelo sistema</h2>
              @if (canExecute() && editable() && v.mapOrigin !== 'VISUAL') {
                <button class="btn btn-sm btn-outline-primary" type="button" (click)="editing.set(!editing())">
                  <app-icon name="pencil" /> {{ editing() ? 'Fechar edição' : 'Editar mapa' }}
                </button>
              }
            </div>
            @if (v.explanation.length) {
              <ul class="pd-explain">
                @for (line of v.explanation; track $index) {
                  <li>{{ line }}</li>
                }
              </ul>
            }
            @if (v.mapProblems.length) {
              <div class="alert alert-warning pd-small" role="alert">
                <strong>O mapa precisa de ajustes:</strong>
                <ul class="mb-0">
                  @for (p of v.mapProblems; track $index) {
                    <li>{{ p.message }}</li>
                  }
                </ul>
              </div>
            }
            @if (editing() || v.status === 'AWAITING_MAP') {
              <app-pdf-map-editor [map]="v.map" [fields]="v.canonicalFields" [busy]="busy()" (apply)="applyMap(v, $event)" />
            }
            @if (v.revisions.length > 1) {
              <details class="pd-small">
                <summary>Histórico do mapa ({{ v.revisions.length }} revisões)</summary>
                <ol class="pd-revisions">
                  @for (r of v.revisions; track r.revision) {
                    <li>Rev. {{ r.revision }} · {{ originLabel(r.origin) }} · {{ r.records }} título(s) · totais {{ r.totalsMatch ? 'conferem' : 'não conferem' }} · {{ r.createdAt | appDate }}</li>
                  }
                </ol>
              </details>
            }
          </section>
        </div>
      }

      @if (v.summary && (v.status === 'AWAITING_VALIDATION' || v.status === 'APPROVED')) {
        <app-pdf-records-table [view]="v" [canExecute]="canExecute()" (focused)="focus($event)" (changed)="replace($event)" (stale)="resource.reload()" />
      }

      @if (canExecute() && v.status === 'AWAITING_VALIDATION') {
        <section class="app-card pd-approve" aria-labelledby="pd-approve" data-tour="pdf-approve">
          <h2 class="app-card__title" id="pd-approve">Concordo com o mapa?</h2>
          @if (v.blockers.length) {
            <div class="alert alert-danger pd-small" role="alert">
              <strong>Antes de importar:</strong>
              <ul class="mb-0">
                @for (b of v.blockers; track $index) {
                  <li>{{ b }}</li>
                }
              </ul>
            </div>
          }
          @if (v.totalsJustificationRequired) {
            <label class="form-label pd-small" for="pd-justification">Os totais do documento não conferem com a soma extraída. Corrija o mapa ou justifique (fica na auditoria):</label>
            <textarea id="pd-justification" class="form-control" rows="2" maxlength="500" [value]="justification()" (input)="justification.set(val($event))"></textarea>
          }
          @if (v.visualConfirmationRequired) {
            <div class="form-check pd-small mt-2">
              <input class="form-check-input" type="checkbox" id="pd-visual" [checked]="visualConfirmed()" (change)="visualConfirmed.set(!visualConfirmed())" />
              <label class="form-check-label" for="pd-visual">Conferi os valores lidos visualmente com as imagens das páginas.</label>
            </div>
          }
          <p class="app-muted pd-small mt-2">Ao concordar, o sistema gera o lote no layout padrão com os {{ v.summary?.recordsToImport ?? 0 }} título(s) a importar e abre a conferência da importação. Nada é gravado na carteira antes de você concluir a conferência.</p>
          @if (actionError()) {
            <div class="alert alert-danger pd-small" role="alert">{{ actionError() }}</div>
          }
          <div class="pd-actions">
            @if (v.textLayer && v.mapOrigin !== 'VISUAL') {
              <button class="btn btn-outline-primary" type="button" (click)="saveTemplate(v)" [disabled]="busy() || !v.summary?.totalsMatch || v.blockers.length > 0">
                <app-icon name="bookmark-plus" /> Salvar mapa para este credor
              </button>
            }
            <button class="btn btn-primary" type="button" (click)="approve(v)" [disabled]="busy() || !canApprove()">
              <app-icon name="check2-circle" /> Concordo, importar
            </button>
          </div>
        </section>
      }
      @if (canExecute() && v.status === 'APPROVED' && v.textLayer && v.mapOrigin !== 'VISUAL' && !v.templateId) {
        <div class="pd-actions">
          <button class="btn btn-outline-primary" type="button" (click)="saveTemplate(v)" [disabled]="busy() || !v.summary?.totalsMatch">
            <app-icon name="bookmark-plus" /> Salvar mapa para este credor
          </button>
        </div>
      }
    }
  `,
  styles: `
    .pd-status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
      margin-bottom: var(--app-gap);
      font-size: var(--app-text-sm);
    }
    .pd-badge {
      white-space: normal;
      text-align: start;
    }
    .pd-conference,
    .pd-approve {
      margin-bottom: var(--app-gap);
    }
    .pd-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 8px 16px;
      margin: 0 0 12px;
    }
    .pd-kpis dt {
      font-size: var(--app-text-xs);
      font-weight: 400;
      color: var(--bs-secondary-color);
    }
    .pd-kpis dd {
      margin: 0;
      font-size: 1.1rem;
      font-weight: 500;
    }
    .pd-totals {
      font-size: var(--app-text-sm);
    }
    .pd-small {
      font-size: var(--app-text-sm);
    }
    .pd-findings {
      display: grid;
      gap: 2px;
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
    }
    .pd-layout {
      display: grid;
      grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
      align-items: start;
    }
    @media (max-width: 1100px) {
      .pd-layout {
        /* minmax(0, …): a página do PDF (largura mínima legível) rola dentro do quadro, sem alargar a coluna */
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .pd-map__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .pd-explain {
      display: grid;
      gap: 4px;
      padding-left: 18px;
      font-size: var(--app-text-sm);
    }
    .pd-revisions {
      margin: 6px 0 0;
      padding-left: 18px;
    }
    .pd-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 12px;
    }
  `,
})
export class PdfImportDetail {
  readonly id = input.required<string>();
  private readonly api = inject(PdfImportsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly size = sizeText;
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));
  protected readonly canSensitive = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));
  /** Imagem das páginas (`GET .../pages/{n}`): `IMPORT_EXECUTE` ou `DEBTOR_SENSITIVE_VIEW`; sem elas, nem consulta (seria 403). */
  protected readonly canSeePages = computed(() => this.canExecute() || this.canSensitive());

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.resource, undefined);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.current()?.portfolioId]);
  protected readonly editable = computed(() => (this.current() ? canEdit(this.current()!) : false));
  protected readonly open = computed(() => {
    const s = this.current()?.status;
    return s === 'RECEIVED' || s === 'MAPPING' || s === 'AWAITING_MAP' || s === 'AWAITING_VALIDATION';
  });

  protected readonly docFindings = computed(() => (this.current()?.findings ?? []).filter((f) => f.severity !== 'INFO' || f.code === 'pdf.visual_reading').slice(0, 30));

  protected readonly editing = signal(false);
  protected readonly busy = signal(false);
  protected readonly page = signal(1);
  protected readonly highlight = signal<readonly RegionView[]>([]);
  protected readonly justification = signal('');
  protected readonly visualConfirmed = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly canApprove = computed(() => {
    const v = this.current();
    if (!v || v.blockers.length) {
      return false;
    }
    if (v.totalsJustificationRequired && this.justification().trim().length < 10) {
      return false;
    }
    return !v.visualConfirmationRequired || this.visualConfirmed();
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.fileName ?? null));
    const timer = setInterval(() => {
      const v = this.current();
      if (v && needsPolling(v.status) && !this.resource.isLoading()) {
        this.resource.reload();
      }
    }, POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected val(event: Event): string {
    return (event.target as HTMLTextAreaElement).value;
  }

  protected originLabel(origin: string): string {
    return ORIGIN_LABELS[origin] ?? origin;
  }

  protected field(target: string): string {
    return targetLabel(target);
  }

  protected amount(value: string | undefined): number | null {
    if (value === undefined || value === null || value === '') {
      return null;
    }
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }

  protected show(regions: readonly RegionView[]): void {
    this.highlight.set(regions);
  }

  protected focus(r: PdfRecordView): void {
    const regions = r.lines.length ? r.lines : Object.values(r.sources).flat();
    if (regions.length) {
      this.highlight.set(regions);
    } else if (r.page) {
      this.page.set(r.page);
      this.highlight.set([]);
    }
  }

  protected replace(view: PdfImportView): void {
    this.resource.set(view);
    this.actionError.set(null);
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.actionError.set(e.detail || userMessage(e));
  }

  protected applyMap(v: PdfImportView, map: ExtractionMap): void {
    this.busy.set(true);
    this.api
      .updateMap(v.id, map, v.mapRevision)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (view) => {
          this.replace(view);
          this.notifications.success(`Mapa aplicado (rev. ${view.mapRevision}): ${view.summary?.records ?? 0} título(s) encontrados.`);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.notifications.error(e.detail || userMessage(e));
        },
      });
  }

  protected newMap(v: PdfImportView): void {
    this.confirm
      .confirm({ title: 'Pedir novo mapa à IA', message: 'A IA lê de novo o PDF e propõe outro mapa. As edições e exclusões feitas nesta revisão deixam de valer.', confirmLabel: 'Pedir novo mapa' })
      .subscribe(() => {
        this.busy.set(true);
        this.api
          .requestNewMap(v.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({ next: (view) => this.replace(view), error: (err: unknown) => this.fail(err) });
      });
  }

  protected approve(v: PdfImportView): void {
    this.confirm
      .confirm({
        title: 'Concordo, importar',
        message: 'O lote no layout padrão segue para a conferência da importação, onde você confere formato, linhas, grandes totais e alertas antes de gravar.',
        confirmLabel: 'Gerar lote e conferir',
        details: [
          { label: 'Títulos a importar', value: String(v.summary?.recordsToImport ?? 0) },
          { label: 'Revisão do mapa', value: String(v.mapRevision) },
        ],
      })
      .subscribe(() => {
        this.busy.set(true);
        this.api
          .approve(v.id, v.mapRevision, v.totalsJustificationRequired ? this.justification().trim() : null, this.visualConfirmed())
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (view) => {
              this.replace(view);
              this.notifications.success('Mapa aprovado: abrindo a conferência da importação.');
              if (view.reviewId) {
                void this.router.navigate(['/importacao/conferencias', view.reviewId]);
              }
            },
            error: (err: unknown) => this.fail(err),
          });
      });
  }

  protected saveTemplate(v: PdfImportView): void {
    this.confirm
      .confirm({
        title: 'Salvar mapa para este credor',
        message: 'Os próximos PDFs deste credor com o mesmo layout recebem este mapa direto, sem IA. Você continua validando cada importação.',
        confirmLabel: 'Salvar mapa',
        reason: 'required',
        reasonLabel: 'Nome do mapa',
        reasonMaxLength: 120,
      })
      .subscribe((name) => {
        this.busy.set(true);
        this.api
          .saveTemplate(v.id, name, v.mapRevision)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (t) => {
              this.notifications.success(`Mapa "${t.name}" salvo (versão ${t.version}).`);
              this.resource.reload();
            },
            error: (err: unknown) => this.fail(err),
          });
      });
  }

  protected discard(v: PdfImportView): void {
    this.confirm
      .confirm({ title: 'Descartar importação PDF', message: 'Nada foi gravado na carteira. A importação fica no histórico.', confirmLabel: 'Descartar', destructive: true, reason: 'required', reasonMaxLength: 500 })
      .subscribe((reason) => {
        this.busy.set(true);
        this.api
          .discard(v.id, reason)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({ next: (view) => this.replace(view), error: (err: unknown) => this.fail(err) });
      });
  }

  protected download(v: PdfImportView): void {
    this.api.downloadOriginal(v.id, v.fileName).subscribe((name) => this.notifications.success(`PDF baixado (${name}).`));
  }
}
