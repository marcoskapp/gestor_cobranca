import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { nameMap } from '../../../shared/data/name-map';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, PortfolioNames } from '../../organization/data/organization.lookups';
import { PoliciesApi } from '../data/negotiation.api';
import { NEGOTIATION_PERMISSIONS } from '../negotiation.routes';
import { percent } from '../data/negotiation.labels';
import {
  ALL_DELAYS,
  DELAY_LABELS,
  PolicyHealthApi,
  PolicyHealthFilter,
  SegmentHealth,
  VALUE_BAND_LABELS,
  ValueBand,
  share,
  suggestionText,
} from '../data/policy-health';

const VALUE_BANDS = Object.keys(VALUE_BAND_LABELS) as ValueBand[];
const MAX_DAYS = 400;

/** Período: início antes do fim e no máximo 400 dias (a mesma regra do servidor). */
export function rangeProblem(v: { from: string | null; to: string | null }): string | null {
  if (v.from && v.to) {
    if (v.to < v.from) {
      return 'A data final é anterior à inicial.';
    }
    const days = (Date.parse(v.to) - Date.parse(v.from)) / 86_400_000;
    if (days > MAX_DAYS) {
      return `Escolha um período de até ${MAX_DAYS} dias.`;
    }
  }
  return null;
}

/**
 * Saúde da política (decisão 1087): resultados da política do credor por carteira × faixa de atraso e sugestões
 * explicáveis de revisão. Só agregados; devedores com marcador de vulnerabilidade ficam fora; a sugestão leva à política,
 * onde quem gerencia cria o rascunho — nada muda sozinho.
 */
@Component({
  selector: 'app-policy-health',
  imports: [ReactiveFormsModule, RouterLink, NgbInputDatepicker, ...FORM_FIELD, Amount, AppDatePipe, EmptyState, EntityPicker, FilterBar, Icon, InfoHint, PageHeader, Skeleton],
  template: `
    <app-page-header title="Saúde das políticas" subtitle="Como a política de negociação de cada credor está funcionando, por carteira e faixa de atraso, com sugestões de revisão.">
      @if (canSeePolicies()) {
        <a class="btn btn-outline-secondary d-inline-flex align-items-center gap-2" routerLink="/negociacao/politicas" [queryParams]="creditorParam()">
          <app-icon name="shield-check" />
          Políticas de cobrança
        </a>
      }
    </app-page-header>
    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar a análise da política">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Acordos de" hint="Padrão: últimos 180 dias">
          <div class="input-group">
            <input appInput ngbDatepicker #df="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" />
            <button type="button" class="btn btn-outline-secondary" (click)="df.toggle()" aria-label="Abrir calendário (de)"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Até" [error]="rangeError() ?? undefined">
          <div class="input-group">
            <input appInput ngbDatepicker #dt="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" />
            <button type="button" class="btn btn-outline-secondary" (click)="dt.toggle()" aria-label="Abrir calendário (até)"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Valor do acordo" hint="Vale para os acordos; as colunas do portal não têm valor de acordo">
          <select appInput formControlName="valueBand">
            <option value="">Todos</option>
            @for (b of valueBands; track b) {
              <option [value]="b">{{ valueLabels[b] }}</option>
            }
          </select>
        </app-field>
      </form>
    </app-filter-bar>

    @if (!filter() && !rangeError()) {
      <app-empty-state icon="clipboard-data" title="Escolha o credor" message="A análise é por credor: escolha um para ver os resultados da política dele." />
    } @else if (rangeError()) {
      <app-empty-state icon="calendar-x" title="Ajuste o período" [message]="rangeError()!" />
    } @else if (health.isLoading() && !health.hasValue()) {
      <app-skeleton variant="table" [rows]="6" />
    } @else if (health.error()) {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a análise." message="Confira se o credor está no seu escopo e tente de novo.">
        <button type="button" class="btn btn-primary" (click)="health.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (health.value(); as h) {
      <p class="ph-note" role="note">
        <app-icon name="info-circle" />
        <span>
          As sugestões apontam sinais com amostra suficiente e <strong>não alteram a política</strong>: abra a política do credor, crie um rascunho e publique o que decidir.
          Antes de ampliar desconto, prefira um teste controlado (experimento de campanha). Devedores com marcador de vulnerabilidade ficam fora da análise.
          Dados de {{ h.from | appDate: 'date' }} a {{ h.to | appDate: 'date' }}, atualizados a cada 15 minutos.
        </span>
      </p>

      <section aria-labelledby="ph-sugg" class="mb-4">
        <h2 class="app-section-title" id="ph-sugg">Sugestões de revisão</h2>
        @for (sg of suggestions(); track sg.kind + '|' + sg.portfolioId + '|' + sg.delayBucket) {
          <article class="ph-sugg" data-testid="policy-suggestion">
            <h3 class="ph-sugg__title">{{ sg.text.title }}</h3>
            <p class="app-muted mb-1">{{ segmentLabel(sg.portfolioId, sg.delayBucket) }}</p>
            <p class="mb-1">{{ sg.text.evidence }}</p>
            <p class="mb-2"><strong>O que avaliar:</strong> {{ sg.text.action }}</p>
            <div class="d-flex flex-wrap gap-3">
              @if (canSeePolicies()) {
                @if (policyLink(); as link) {
                  <a [routerLink]="link">Abrir a política do credor</a>
                } @else {
                  <a routerLink="/negociacao/politicas" [queryParams]="creditorParam()">Políticas do credor</a>
                }
              }
              @if (canSeeAgreements()) {
                <a routerLink="/negociacao/acordos" [queryParams]="creditorParam()">Ver acordos do credor</a>
              }
            </div>
          </article>
        } @empty {
          <p class="app-muted" data-testid="policy-no-suggestion">
            Nenhum sinal com amostra suficiente no período (mínimo de {{ h.minAgreements }} acordos, {{ h.minMature }} acordos com 60 dias ou {{ h.minPortal }} consultas no portal por recorte).
          </p>
        }
      </section>

      <section aria-labelledby="ph-seg">
        <h2 class="app-section-title" id="ph-seg">Resultados por recorte</h2>
        <div class="table-responsive">
          <table class="table table-sm align-middle ph-table">
            <caption class="visually-hidden">Resultados da política por carteira e faixa de atraso</caption>
            <thead>
              <tr>
                <th scope="col">Carteira</th>
                <th scope="col">Atraso</th>
                <th scope="col" class="text-end">Acordos</th>
                <th scope="col" class="text-end">Quebra até 60 dias <app-info-hint indicator="negotiation.health.early-break" label="Quebra até 60 dias" /></th>
                <th scope="col" class="text-end">Pago sobre acordado <app-info-hint indicator="negotiation.health.paid-over-agreed" label="Pago sobre acordado" /></th>
                <th scope="col" class="text-end">Uso do desconto <app-info-hint indicator="negotiation.health.envelope-usage" label="Uso do desconto máximo" /></th>
                <th scope="col" class="text-end">Portal sem oferta <app-info-hint indicator="negotiation.health.portal-without-offer" label="Portal sem oferta" /></th>
                <th scope="col" class="text-end">"Não cabe" <app-info-hint indicator="negotiation.health.cannot-afford" label="Nenhuma condição cabe" /></th>
              </tr>
            </thead>
            <tbody>
              @for (s of segments(); track s.portfolioId + '|' + s.delayBucket) {
                <tr [class.table-warning]="flagged(s)">
                  <td>
                    @if (flagged(s)) {
                      <app-icon name="exclamation-triangle" /><span class="visually-hidden">Com sugestão de revisão: </span>
                    }
                    {{ portfolioName(s.portfolioId) }}
                  </td>
                  <td>{{ delayLabel(s.delayBucket) }}</td>
                  <td class="text-end app-numeric">{{ s.agreements || '—' }}</td>
                  <td class="text-end app-numeric">{{ s.matureAgreements ? share(s.earlyBreakRate) + ' de ' + s.matureAgreements : '—' }}</td>
                  <td class="text-end app-numeric">{{ share(s.paidOverAgreed) }}</td>
                  <td class="text-end app-numeric">{{ s.averageEnvelopeUsage === null ? '—' : pct(s.averageEnvelopeUsage) + ' (' + share(s.highEnvelopeShare) + ' ≥ 90%)' }}</td>
                  <td class="text-end app-numeric">{{ s.portalSimulations ? share(s.portalWithoutOfferShare) + ' de ' + s.portalSimulations : '—' }}</td>
                  <td class="text-end app-numeric">{{ s.cannotAfford || '—' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="8" class="app-muted">Sem acordos nem consultas no portal no período.</td></tr>
              }
            </tbody>
            @if (h.segments.length) {
              <tfoot>
                <tr class="fw-semibold">
                  <td colspan="2">Total do credor</td>
                  <td class="text-end app-numeric">{{ h.total.agreements }}</td>
                  <td class="text-end app-numeric">{{ h.total.matureAgreements ? share(h.total.earlyBreakRate) + ' de ' + h.total.matureAgreements : '—' }}</td>
                  <td class="text-end app-numeric">{{ share(h.total.paidOverAgreed) }}</td>
                  <td class="text-end app-numeric">{{ h.total.averageEnvelopeUsage === null ? '—' : pct(h.total.averageEnvelopeUsage) }}</td>
                  <td class="text-end app-numeric">{{ h.total.portalSimulations ? share(h.total.portalWithoutOfferShare) + ' de ' + h.total.portalSimulations : '—' }}</td>
                  <td class="text-end app-numeric">{{ h.total.cannotAfford || '—' }}</td>
                </tr>
              </tfoot>
            }
          </table>
        </div>
        <p class="app-muted small">Valor acordado no período: <app-amount [value]="h.total.agreedAmount" /> · recebido: <app-amount [value]="h.total.paidAmount" />. Linhas com o ícone de alerta têm sugestão.</p>
      </section>
    }
  `,
  styles: `
    .ph-note { display: flex; gap: 8px; align-items: flex-start; padding: 12px; border-radius: var(--bs-border-radius); background: var(--bs-tertiary-bg); }
    .ph-sugg { padding: 12px 16px; margin-bottom: 12px; border: 1px solid var(--bs-border-color); border-left: 4px solid var(--bs-warning); border-radius: var(--bs-border-radius); }
    .ph-sugg__title { font-size: var(--app-text-md); font-weight: var(--app-weight-semibold); margin: 0 0 4px; }
    .ph-table th { white-space: nowrap; }
  `,
})
export class PolicyHealth {
  private readonly api = inject(PolicyHealthApi);
  private readonly auth = inject(AuthService);
  /** Mesmas permissões da rota de políticas (área + dados): sem elas, nem link nem consulta. */
  protected readonly canSeePolicies = computed(
    () => this.auth.hasAnyPermission(NEGOTIATION_PERMISSIONS.policies) && this.auth.hasPermission(Permission.POLICY_VIEW),
  );
  protected readonly canSeeAgreements = computed(() => this.auth.hasAnyPermission(NEGOTIATION_PERMISSIONS.agreements));
  private readonly policies = inject(PoliciesApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly valueBands = VALUE_BANDS;
  protected readonly valueLabels = VALUE_BAND_LABELS;
  protected readonly share = share;

  private readonly fb = inject(FormBuilder).nonNullable;
  /** Filtros na URL (`?credor=&de=&ate=&valor=`): o link da política leva direto ao credor. */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      creditor: this.fb.control<string[]>([]),
      from: this.fb.control<string | null>(null),
      to: this.fb.control<string | null>(null),
      valueBand: [''],
    }),
    { creditor: urlParam.ids('credor'), from: urlParam.date('de'), to: urlParam.date('ate'), valueBand: urlParam.choice('valor', VALUE_BANDS) },
  );
  protected readonly filter = toSignal(this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())), {
    initialValue: this.current(),
    equal: sameValue,
  });
  protected readonly activeFilters = activeFilterCount(this.filters);

  protected readonly health = rxResource({ params: () => this.filter() ?? undefined, stream: ({ params }) => this.api.health(params) });
  /** Política publicada do credor (o link "Abrir a política"); sem ela, a lista filtrada pelo credor. */
  private readonly published = rxResource({
    params: () => (this.canSeePolicies() ? this.filter()?.creditorId : undefined),
    stream: ({ params }) =>
      this.policies.search({ creditorId: params, status: 'PUBLISHED' }, { page: 0, size: 1 }).pipe(
        map((p) => p.content[0]?.id ?? null),
        catchError(() => of(null)),
      ),
  });
  protected readonly policyLink = computed(() => {
    const id = this.published.hasValue() ? this.published.value() : null;
    return id ? ['/negociacao/politicas', id] : null;
  });
  protected readonly creditorParam = computed(() => {
    const id = this.filter()?.creditorId;
    return id ? { credor: id } : {};
  });

  /** Carteiras pelo nome (as faixas mantêm a ordem do servidor, do menor ao maior atraso). */
  protected readonly segments = computed(() => {
    const h = this.health.hasValue() ? this.health.value() : null;
    return h ? [...h.segments].sort((a, b) => this.portfolioName(a.portfolioId).localeCompare(this.portfolioName(b.portfolioId), 'pt-BR')) : [];
  });
  private readonly portfolios = nameMap(inject(PortfolioNames), () => (this.health.hasValue() ? this.health.value().segments.map((s) => s.portfolioId) : []));
  protected readonly suggestions = computed(() => {
    const h = this.health.hasValue() ? this.health.value() : null;
    if (!h) {
      return [];
    }
    return h.suggestions.map((sg) => {
      const segment = h.segments.find((s) => s.portfolioId === sg.portfolioId && s.delayBucket === sg.delayBucket) ?? h.total;
      return { ...sg, text: suggestionText(sg.kind, segment, h.total, h.minMature) };
    });
  });

  protected flagged(s: SegmentHealth): boolean {
    return this.suggestions().some((sg) => sg.portfolioId === s.portfolioId && sg.delayBucket === s.delayBucket);
  }

  protected portfolioName(id: string | null): string {
    return id ? this.portfolios.name(id) : 'Sem carteira';
  }

  protected delayLabel(bucket: string | null): string {
    return bucket === null ? ALL_DELAYS : (DELAY_LABELS[bucket] ?? bucket);
  }

  protected segmentLabel(portfolioId: string | null, bucket: string | null): string {
    return `${this.portfolioName(portfolioId)} · ${this.delayLabel(bucket)}`;
  }

  protected pct(value: number): string {
    return percent(value, 1);
  }

  /** Período inválido não vai ao servidor: a mensagem aparece no campo "Até". */
  protected readonly rangeError = toSignal(this.filters.valueChanges.pipe(startWith(null), map(() => rangeProblem(this.filters.getRawValue()))), {
    initialValue: rangeProblem(this.filters.getRawValue()),
  });

  private current(): PolicyHealthFilter | null {
    const v = this.filters.getRawValue();
    const creditorId = v.creditor[0];
    return creditorId && !rangeProblem(v) ? { creditorId, from: v.from || null, to: v.to || null, valueBand: (v.valueBand || null) as ValueBand | null } : null;
  }
}
