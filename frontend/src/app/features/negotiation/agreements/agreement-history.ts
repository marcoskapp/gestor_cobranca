import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { PageRequest } from '../../../shared/models/page';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AgreementsApi } from '../data/agreement.api';
import { AGREEMENT_ORIGIN_LABELS } from '../data/agreement.labels';
import { AgreementSummary, AgreementView } from '../data/agreement.models';
import { scheduleSummary } from '../data/agreement.rules';

const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 0 });

/** Cumprimento em texto ("67%"), ou "—" sem acordo quitado nem quebrado. */
export function fulfillmentText(s: Pick<AgreementSummary, 'fulfillmentRate'>): string {
  return s.fulfillmentRate === null ? '—' : PERCENT.format(s.fulfillmentRate);
}

/** Base do cumprimento, sempre visível ao lado da taxa ("2 quitados de 3 encerrados"). */
export function fulfillmentDetail(s: Pick<AgreementSummary, 'settled' | 'broken'>): string {
  const ended = s.settled + s.broken;
  return ended === 0 ? 'nenhum quitado ou quebrado ainda' : `${s.settled} quitado(s) de ${ended} encerrado(s)`;
}

/** Filtro da lista de acordos (`/negociacao/acordos`) com o mesmo recorte do histórico. */
export function agreementListParams(scope: { debtorId: string | null; creditorId: string | null }): Record<string, string> {
  const params: Record<string, string> = {};
  if (scope.debtorId) {
    params['devedor'] = scope.debtorId;
  }
  if (scope.creditorId) {
    params['credor'] = scope.creditorId;
  }
  return params;
}

/**
 * Histórico de acordos (desenho `acordos-ficha-lateral-e-atencao-especial.md` §A1): resumo calculado no servidor
 * (acordos, em vigor, cumprimento, total pago) e a lista de todos os acordos do caso (`caseId`) ou do devedor
 * (`debtorId`, com o credor), do mais recente ao mais antigo. Cada acordo abre a tela própria numa aba nova — na estação
 * o atendimento não é interrompido. Só com `AGREEMENT_VIEW` (quem usa esconde a aba sem a permissão).
 *
 * ```html
 * <app-agreement-history [caseId]="c.id" [creditorId]="c.creditorId" [debtorId]="c.debtorId" />
 * <app-agreement-history [debtorId]="d.id" />
 * ```
 */
@Component({
  selector: 'app-agreement-history',
  imports: [RouterLink, NgbTooltip, Icon, Amount, KpiCard, StatusChip, DataTable, CellDef, AppDatePipe],
  template: `
    @if (canView()) {
      <section class="app-kpi-grid ah-kpis" aria-label="Resumo dos acordos" [attr.aria-busy]="summary.isLoading()" data-tour="agreement-history">
        @if (summaryValue(); as s) {
          <app-kpi-card label="Acordos" info="negotiation.history.count" [detail]="s.renegotiated ? s.renegotiated + ' renegociado(s)' : ''">{{ s.total }}</app-kpi-card>
          <app-kpi-card label="Em vigor" info="negotiation.history.active">{{ s.active }}</app-kpi-card>
          <app-kpi-card label="Cumprimento" info="negotiation.history.fulfillment" [detail]="detail(s)" [tone]="s.broken > s.settled ? 'warning' : null">{{ rate(s) }}</app-kpi-card>
          <app-kpi-card label="Total pago" info="negotiation.history.paid" [detail]="'de ' + amount(s.totalAmount) + ' acordados'"><app-amount [value]="s.paidAmount" /></app-kpi-card>
        } @else if (summary.error()) {
          <p class="app-muted mb-0">Não foi possível carregar o resumo dos acordos. <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="summary.reload()">Tentar novamente</button></p>
        }
      </section>

      <app-data-table
        caption="Histórico de acordos"
        [rows]="rows()"
        [columns]="columns()"
        [loading]="list.isLoading()"
        [failed]="list.status() === 'error'"
        [page]="tablePage()"
        (pageChange)="page.set($event)"
        (retry)="list.reload()"
        emptyIcon="person-hearts"
        [emptyMessage]="caseId() ? 'Nenhum acordo neste caso.' : 'Nenhum acordo deste devedor nos credores do seu escopo.'"
        emptyHint="Acordos nascem da formalização de uma proposta aceita pelo devedor."
      >
        <ng-template appCell="formalizedAt" [appCellOf]="rows()" let-a>
          <a [routerLink]="['/negociacao/acordos', a.id]" target="_blank" rel="noopener" class="ah-open">
            {{ a.formalizedAt | appDate: 'date' }}<app-icon name="box-arrow-up-right" /><span class="visually-hidden"> (abre o acordo em nova aba)</span>
          </a>
          <div class="app-muted">{{ origins[a.origin] }}</div>
        </ng-template>
        <ng-template appCell="creditor" [appCellOf]="rows()" let-a>
          {{ creditors.name(a.creditorId) }}
          <div class="app-muted">{{ portfolios.name(a.portfolioId) }}</div>
        </ng-template>
        <ng-template appCell="total" [appCellOf]="rows()" let-a>
          <app-amount [value]="a.totalAmount" />
          <div class="app-muted">pago <app-amount [value]="a.paidAmount" /></div>
        </ng-template>
        <ng-template appCell="progress" [appCellOf]="rows()" let-a>
          @let s = schedule(a);
          <span class="app-numeric">{{ s.paid }} de {{ s.total }} pagas</span>
          @if (active(a) && s.overdue) {
            <div class="ah-late">{{ s.overdue }} vencida(s)</div>
          } @else if (active(a) && s.next) {
            <div class="app-muted">próxima {{ s.next.dueDate | appDate: 'date' }}</div>
          }
        </ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-a>
          <span class="ah-status" [ngbTooltip]="a.statusReason" [attr.tabindex]="a.statusReason ? 0 : null">
            <app-status-chip domain="agreement" [status]="a.status" />
          </span>
          @if (a.renegotiationNumber > 0) {
            <div class="app-muted">renegociação {{ a.renegotiationNumber }}</div>
          }
          @if (endedOn(a); as end) {
            <div class="app-muted">em {{ end | appDate: 'date' }}</div>
          }
        </ng-template>
      </app-data-table>

      <div class="ah-foot">
        <a [routerLink]="['/negociacao/acordos']" [queryParams]="listParams()" target="_blank" rel="noopener">
          Ver na lista de acordos<app-icon name="box-arrow-up-right" /><span class="visually-hidden"> (abre em nova aba)</span>
        </a>
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .ah-kpis { margin-bottom: var(--app-space-3); }
    .ah-open { display: inline-flex; align-items: center; gap: 4px; }
    .ah-late { font-weight: var(--app-weight-semibold); color: var(--bs-danger-text-emphasis); }
    .ah-status { display: inline-flex; }
    .ah-foot { display: flex; justify-content: flex-end; margin-top: var(--app-space-2); font-size: var(--app-text-sm); }
    .ah-foot a { display: inline-flex; align-items: center; gap: 4px; }
  `,
})
export class AgreementHistory {
  /** Acordos do caso (ficha do caso). */
  readonly caseId = input<string | null>(null);
  /** Acordos do devedor em todos os credores do escopo (ficha do devedor); com `caseId`, só para o link da lista. */
  readonly debtorId = input<string | null>(null);
  /** Credor do caso: só para o link "Ver na lista de acordos". */
  readonly creditorId = input<string | null>(null);
  /** Muda quando a tela formaliza ou altera um acordo (recarrega). */
  readonly revision = input(0);

  private readonly api = inject(AgreementsApi);
  private readonly auth = inject(AuthService);

  protected readonly origins = AGREEMENT_ORIGIN_LABELS;
  protected readonly canView = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));
  /** Página da lista; volta para a primeira quando o caso ou o devedor muda (mantém a quantidade por página). */
  protected readonly page = linkedSignal<string, PageRequest>({
    source: () => `${this.caseId() ?? ''}|${this.debtorId() ?? ''}`,
    computation: (_, previous) => ({ page: 0, size: previous?.value.size ?? 20 }),
  });

  private readonly scope = computed(() => {
    if (!this.canView()) {
      return undefined;
    }
    const caseId = this.caseId();
    const debtorId = caseId ? null : this.debtorId();
    return caseId || debtorId ? { caseId: caseId ?? null, debtorId, revision: this.revision() } : undefined;
  });

  protected readonly summary = rxResource({
    params: () => this.scope(),
    stream: ({ params }) => this.api.summary({ creditorId: null, debtorId: params.debtorId, caseId: params.caseId }),
  });

  /** Resumo carregado (o `value()` de um recurso com erro lança: a falha mostra o aviso com "Tentar novamente"). */
  protected readonly summaryValue = computed(() => (this.summary.hasValue() ? this.summary.value() : null));

  protected readonly list = rxResource({
    params: () => {
      const scope = this.scope();
      return scope ? { ...scope, page: this.page() } : undefined;
    },
    stream: ({ params }) =>
      this.api.search({ creditorId: null, debtorId: params.debtorId, caseId: params.caseId, status: null }, { ...params.page, sort: 'formalizedAt,desc' }),
  });
  protected readonly rows = computed<readonly AgreementView[]>(() => (this.list.hasValue() ? this.list.value().content : []));
  protected readonly tablePage = computed(() => {
    const p = this.list.hasValue() ? this.list.value() : null;
    return p ? { index: p.page, size: p.size, total: p.totalElements } : null;
  });

  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((a) => a.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((a) => a.portfolioId));

  /** Na ficha do devedor aparece o credor e a carteira de cada acordo; no caso, todos são do mesmo credor. */
  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'formalizedAt', header: 'Formalizado em', mobile: 'title' },
    ...(this.caseId() ? [] : [{ key: 'creditor', header: 'Credor', mobile: 'meta' } as TableColumn]),
    { key: 'total', header: 'Total', align: 'end', mobile: 'meta' },
    { key: 'progress', header: 'Parcelas', mobile: 'meta' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
  ]);

  protected readonly listParams = computed(() =>
    agreementListParams({ debtorId: this.debtorId(), creditorId: this.caseId() ? this.creditorId() : null }),
  );

  protected schedule(a: AgreementView) {
    return scheduleSummary(a);
  }

  protected active(a: AgreementView): boolean {
    return a.status === 'FORMALIZED' || a.status === 'IN_PAYMENT';
  }

  /** Quando o acordo saiu de vigor (quitação ou encerramento). */
  protected endedOn(a: AgreementView): string | null {
    return a.status === 'SETTLED' ? a.settledAt : this.active(a) ? null : a.endedAt;
  }

  protected rate(s: AgreementSummary): string {
    return fulfillmentText(s);
  }

  protected detail(s: AgreementSummary): string {
    return fulfillmentDetail(s);
  }

  protected amount(value: number): string {
    return formatAmount(value);
  }
}
