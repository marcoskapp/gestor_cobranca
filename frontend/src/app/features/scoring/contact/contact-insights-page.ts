import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { FilterBar } from '../../../shared/components/filter-bar/filter-bar';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { clearOnChange } from '../../../shared/data/dependent-filter';
import { activeFilterCount } from '../../../shared/data/filter-count';
import { sameValue } from '../../../shared/data/same-value';
import { syncFormWithUrl, urlParam } from '../../../shared/data/url-state';
import { FORM_FIELD } from '../../../shared/forms/field';
import { isoDate, toLocalDate } from '../../../shared/models/dates';
import { BarChart } from '../../analytics/components/bar-chart';
import { ColumnChart } from '../../analytics/components/column-chart';
import { PERSON_TYPE_LABELS } from '../../debt/data/debt.labels';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { ContactInsightsFilter, ContactInsightsView, ContactOrigin, InsightPersonType } from '../data/contact-intelligence.models';
import {
  INSIGHT_PERSON_TYPES,
  SLOT_HOURS,
  agreementRows,
  bestHeatCell,
  channelRows,
  defaultInsightsRange,
  firstSuccessRows,
  heatmapRows,
  insightsRangeProblem,
  medianText,
  rateOrDash,
  slotLabel,
} from '../data/contact-intelligence.rules';
import { ContactIntelligenceApi } from '../data/scoring.api';
import { SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { integer, percent } from '../data/scoring.rules';

/**
 * Inteligência de contato (`/score/contato`): retrato agregado de quando e por onde o contato funciona
 * na operação — mapa de calor dia × faixa horária, tentativas até o 1º atendimento, taxa por canal,
 * contatos até o acordo e até o acordo cumprido, e o corte pessoa física × jurídica. Filtros na URL;
 * o servidor limita ao escopo de credores (fora dele, tudo zerado). Cada gráfico tem tabela equivalente.
 */
@Component({
  selector: 'app-contact-insights-page',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...FORM_FIELD, PageHeader, FilterBar, EntityPicker, Icon, InfoHint, KpiCard, EmptyState, Skeleton, BarChart, ColumnChart],
  template: `
    <app-page-header title="Inteligência de contato" subtitle="Quando e por onde o contato com o devedor funciona, e quantas tentativas levam a um acordo cumprido." />

    <app-filter-bar [activeCount]="activeFilters()">
      <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar inteligência de contato">
        <app-field label="De">
          <div class="input-group">
            <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" autocomplete="off" />
            <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Até">
          <div class="input-group">
            <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" autocomplete="off" />
            <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
        </div>
        <app-field label="Tipo de pessoa">
          <select appInput formControlName="personType">
            <option value="">Todos</option>
            @for (t of personTypes; track t) {
              <option [ngValue]="t">{{ personLabels[t] }}</option>
            }
          </select>
        </app-field>
      </form>
    </app-filter-bar>

    @if (problem(); as p) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-triangle" /><span>{{ p }}</span></div>
    } @else if (report.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a inteligência de contato." [message]="errorText()">
        <button class="btn btn-outline-primary" type="button" (click)="report.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!data()) {
      <div aria-busy="true"><app-skeleton variant="card" [rows]="4" /></div>
    } @else if (empty()) {
      <app-empty-state
        icon="telephone"
        title="Nenhuma tentativa de contato no período."
        message="Os dados aparecem conforme as ligações e mensagens com contato do devedor são registradas. Amplie o período ou retire filtros."
      />
    } @else {
      @let d = data()!;
      @let t = d.totals;
      <div [attr.aria-busy]="report.isLoading()">
        <section class="app-kpi-grid" aria-label="Resumo do período" data-tour="contact-kpis">
          <app-kpi-card label="Taxa de atendimento ou resposta" info="scoring.contact.success-rate" [detail]="int(t.successes) + ' de ' + int(t.attempts) + ' tentativas'">{{ pct(t.rate) }}</app-kpi-card>
          <app-kpi-card label="Tentativas" info="scoring.contact.attempts" [detail]="int(t.debtorInitiated) + ' iniciadas pelo devedor'">{{ int(t.attempts) }}</app-kpi-card>
          <app-kpi-card label="Acordos" info="scoring.contact.agreements" [detail]="int(t.completed) + ' cumpridos · ' + int(t.broken) + ' quebrados'">{{ int(t.agreements) }}</app-kpi-card>
          <app-kpi-card label="Taxa de cumprimento" info="scoring.contact.fulfillment" [detail]="int(t.completed) + ' de ' + int(t.completed + t.broken) + ' cumpridos ou quebrados'">{{ pct(t.fulfillmentRate) }}</app-kpi-card>
        </section>

        <section class="app-card" aria-labelledby="ci-heat" data-tour="contact-heatmap">
          <div class="d-flex align-items-center gap-1">
            <h2 class="app-card__title mb-0" id="ci-heat">Dia da semana × faixa horária</h2>
            <app-info-hint indicator="scoring.contact.heatmap" label="Dia da semana × faixa horária" />
          </div>
          <p class="ci-small" id="ci-heat-s">{{ heatSummary() }}</p>
          <div class="table-responsive">
            <table class="table table-sm align-middle mb-0 ci-heat" aria-describedby="ci-heat-s">
              <caption class="visually-hidden">Taxa de atendimento ou resposta por dia da semana e faixa horária</caption>
              <thead>
                <tr>
                  <th scope="col">Dia</th>
                  @for (h of slots; track h) {
                    <th scope="col" class="text-center">{{ slot(h) }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (r of heat(); track r.weekday) {
                  <tr>
                    <th scope="row" class="fw-normal"><abbr [attr.title]="r.long">{{ r.short }}</abbr></th>
                    @for (c of r.cells; track c.startHour) {
                      <td class="text-center app-numeric ci-heat__cell" [class.ci-heat__cell--few]="c.rate === null" [style.--ci-heat]="c.intensity * 0.6 + '%'" [attr.title]="c.description">
                        <span aria-hidden="true">{{ c.text }}</span>
                        <span class="visually-hidden">{{ c.description }}</span>
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="ci-small">Cor mais forte = taxa maior. "—": poucas tentativas (menos de {{ int(d.minAttempts) }} na faixa), sem taxa para não tirar conclusão de poucos casos. Tentativas entre 0h e 6h não aparecem no mapa.</p>
        </section>

        <div class="app-columns">
          <section class="app-card" data-tour="contact-first-success">
            <app-column-chart
              title="Tentativas até o 1º atendimento"
              info="scoring.contact.first-success"
              [summary]="curveSummary()"
              categoryHeader="Tentativas"
              [series]="{ name: 'Contatos', color: '--app-chart-1' }"
              [rows]="curve()"
              [money]="false"
              [axisFractionDigits]="0"
            />
          </section>
          <section class="app-card">
            <app-bar-chart
              title="Taxa por canal"
              info="scoring.contact.channel-rate"
              [summary]="channelSummary()"
              categoryHeader="Canal"
              noteHeader="Base"
              [series]="[{ name: 'Taxa', color: '--app-chart-1' }]"
              [rows]="channelRowsList()"
            />
          </section>
        </div>

        <section class="app-card" data-tour="contact-agreements">
          <app-bar-chart
            title="Contatos até o acordo"
            info="scoring.contact.agreements-by-attempts"
            [summary]="agreementSummary()"
            categoryHeader="Tentativas antes do acordo"
            noteHeader="Acordos e cumprimento"
            [series]="[{ name: 'Cumpridos', color: '--app-chart-1' }, { name: 'Quebrados', color: '--app-chart-2' }]"
            [rows]="agreements()"
          />
          <dl class="ci-medians">
            <div><dt>Mediana até o acordo</dt><dd class="app-numeric">{{ median(d.medianAttemptsToAgreement, 'tentativas') }}</dd></div>
            <div><dt>Mediana até o acordo cumprido</dt><dd class="app-numeric">{{ median(d.medianAttemptsToCompletedAgreement, 'tentativas') }}</dd></div>
            <div><dt>Do 1º contato ao acordo</dt><dd class="app-numeric">{{ median(d.medianDaysFirstContactToAgreement, 'dias (mediana)') }}</dd></div>
          </dl>
          <p class="ci-small">Taxa de cumprimento = cumpridos ÷ (cumpridos + quebrados). Acordos em vigor e cancelados ficam fora da taxa.</p>
        </section>

        <section class="app-card" aria-labelledby="ci-person">
          <h2 class="app-card__title" id="ci-person">Pessoa física × pessoa jurídica</h2>
          @if (d.byPersonType.length) {
            <div class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Tipo de pessoa</th>
                    <th scope="col" class="text-end">Tentativas</th>
                    <th scope="col" class="text-end">Taxa <app-info-hint indicator="scoring.contact.success-rate" label="Taxa de atendimento ou resposta" /></th>
                    <th scope="col" class="text-end">Acordos</th>
                    <th scope="col" class="text-end">Cumpridos</th>
                    <th scope="col" class="text-end">Quebrados</th>
                    <th scope="col" class="text-end">Cumprimento <app-info-hint indicator="scoring.contact.fulfillment" label="Taxa de cumprimento" /></th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of d.byPersonType; track p.personType) {
                    <tr>
                      <th scope="row" class="fw-normal">{{ personLabels[p.personType] }}</th>
                      <td class="text-end app-numeric">{{ int(p.attempts) }}</td>
                      <td class="text-end app-numeric">{{ rateOrDash(p.rate, p.attempts, d.minAttempts) }}</td>
                      <td class="text-end app-numeric">{{ int(p.agreements) }}</td>
                      <td class="text-end app-numeric">{{ int(p.completed) }}</td>
                      <td class="text-end app-numeric">{{ int(p.broken) }}</td>
                      <td class="text-end app-numeric">{{ pct(p.fulfillmentRate) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <p class="ci-small" role="status">Sem tentativas por tipo de pessoa no período.</p>
          }
        </section>

        <section class="app-card" aria-labelledby="ci-origin">
          <h2 class="app-card__title" id="ci-origin">Contato novo × antigo</h2>
          <p class="ci-small mt-0">Contato novo é o que chegou numa carga depois que o devedor já estava cadastrado.</p>
          @if (d.byContactOrigin?.length) {
            <div class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col">Contato</th>
                    <th scope="col" class="text-end">Tentativas</th>
                    <th scope="col" class="text-end">Atendidas ou respondidas</th>
                    <th scope="col" class="text-end">Taxa <app-info-hint indicator="scoring.contact.success-rate" label="Taxa de atendimento ou resposta" /></th>
                  </tr>
                </thead>
                <tbody>
                  @for (o of d.byContactOrigin; track o.origin) {
                    <tr>
                      <th scope="row" class="fw-normal">{{ originLabels[o.origin] }}</th>
                      <td class="text-end app-numeric">{{ int(o.attempts) }}</td>
                      <td class="text-end app-numeric">{{ int(o.successes) }}</td>
                      <td class="text-end app-numeric">{{ rateOrDash(o.rate, o.attempts, d.minAttempts) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <p class="ci-small" role="status">Sem tentativas por contato identificado no período.</p>
          }
        </section>

        <p class="ci-small">{{ d.notice }}</p>
      </div>
    }
  `,
  styles: `
    .ci-small {
      margin: 8px 0 0;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .ci-heat {
      min-width: 520px;
      th abbr {
        text-decoration: none;
      }
    }
    .ci-heat__cell {
      --ci-heat: 0%;
      background: color-mix(in srgb, var(--app-chart-1) var(--ci-heat), var(--bs-body-bg));
      color: var(--bs-body-color);
      font-weight: var(--app-weight-medium);
    }
    .ci-heat__cell--few {
      background: var(--bs-body-bg);
      color: var(--bs-secondary-color);
      font-weight: normal;
    }
    .ci-medians {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 24px;
      margin: 12px 0 0;
      dt {
        font-size: var(--app-text-sm);
        font-weight: normal;
        color: var(--bs-secondary-color);
      }
      dd {
        margin: 0;
        font-weight: var(--app-weight-semibold);
      }
    }
  `,
})
export class ContactInsightsPage {
  private readonly api = inject(ContactIntelligenceApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly personTypes = INSIGHT_PERSON_TYPES;
  protected readonly personLabels = PERSON_TYPE_LABELS;
  protected readonly originLabels: Record<ContactOrigin, string> = { NEW: 'Recebido em carga posterior', EXISTING: 'Demais contatos' };
  protected readonly slots = SLOT_HOURS;
  protected readonly slot = (h: number) => slotLabel(h);
  protected readonly int = integer;
  protected readonly pct = percent;
  protected readonly median = medianText;
  protected readonly rateOrDash = rateOrDash;

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly range = defaultInsightsRange(toLocalDate(new Date())!);
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      from: this.fb.control<string | null>(this.range.from),
      to: this.fb.control<string | null>(this.range.to),
      creditor: this.fb.control<string[]>([]),
      portfolio: this.fb.control<string[]>([]),
      personType: this.fb.control<InsightPersonType | ''>(''),
    }),
    {
      from: urlParam.date('de'),
      to: urlParam.date('ate'),
      creditor: urlParam.ids('credor'),
      portfolio: urlParam.ids('carteira'),
      personType: urlParam.choice<InsightPersonType | ''>('pessoa', [...INSIGHT_PERSON_TYPES]),
    },
  );
  protected readonly activeFilters = activeFilterCount(this.filters);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.filters.controls.creditor.value[0] ?? null);

  constructor() {
    // a carteira é do credor: trocar o credor limpa a carteira escolhida
    clearOnChange(this.filters.controls.creditor, this.filters.controls.portfolio, [] as string[]);
  }

  private readonly value = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly problem = computed(() => insightsRangeProblem(this.value().from, this.value().to));

  protected readonly report = rxResource({
    params: (): ContactInsightsFilter | undefined => (this.problem() ? undefined : this.value()),
    stream: ({ params }) => this.api.insights(params),
  });
  protected readonly data = computed<ContactInsightsView | null>(() => (this.report.hasValue() ? (this.report.value() ?? null) : null));
  protected readonly empty = computed(() => {
    const t = this.data()?.totals;
    return !!t && t.attempts === 0 && t.agreements === 0;
  });
  protected readonly errorText = computed(() => {
    const e = toApiError(this.report.error());
    return (e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e);
  });

  protected readonly heat = computed(() => heatmapRows(this.data()?.heatmap ?? [], this.data()?.minAttempts ?? 0));
  protected readonly heatSummary = computed(() => {
    const best = bestHeatCell(this.heat());
    return best
      ? `Melhor combinação no período: ${best.day.toLowerCase()}, ${slotLabel(best.startHour)}, com ${percent(best.rate)} (${integer(best.successes)} de ${integer(best.attempts)} tentativas).`
      : 'Nenhuma faixa com tentativas suficientes para mostrar a taxa.';
  });
  protected readonly curve = computed(() => firstSuccessRows(this.data()?.firstSuccessCurve ?? []));
  protected readonly curveSummary = computed(() => {
    const d = this.data();
    if (!d) {
      return '';
    }
    const half = [...d.firstSuccessCurve].sort((a, b) => a.attempts - b.attempts).find((p) => p.cumulativeShare >= 0.5);
    const head = half ? `Metade dos contatos atendidos teve o 1º sucesso em até ${half.attempts >= 11 ? '11 ou mais' : integer(half.attempts)} tentativas.` : 'Contatos pelo número de tentativas até o 1º sucesso.';
    return `${head} ${integer(d.neverAnswered)} contatos tiveram tentativas e nenhum sucesso no período.`;
  });
  protected readonly channelRowsList = computed(() => channelRows(this.data()?.channels ?? [], this.data()?.minAttempts ?? 0));
  protected readonly channelSummary = computed(() => {
    const d = this.data();
    const valid = (d?.channels ?? []).filter((c) => c.rate !== null && c.attempts >= (d?.minAttempts ?? 0));
    const best = valid.sort((a, b) => b.rate! - a.rate!)[0];
    return best
      ? `Taxa de atendimento ou resposta por canal. Maior taxa: ${channelRows([best], 0)[0].label} (${percent(best.rate)}).`
      : 'Taxa de atendimento ou resposta por canal.';
  });
  protected readonly agreements = computed(() => agreementRows(this.data()?.agreementsByAttempts ?? []));
  protected readonly agreementSummary = computed(() => {
    const rows = (this.data()?.agreementsByAttempts ?? []).filter((r) => r.fulfillmentRate !== null);
    const best = [...rows].sort((a, b) => b.fulfillmentRate! - a.fulfillmentRate!)[0];
    const base = 'Acordos cumpridos e quebrados pelo número de tentativas de contato antes do acordo.';
    return best ? `${base} Maior cumprimento: ${agreementRows([best])[0].label.toLowerCase()} (${percent(best.fulfillmentRate)}).` : base;
  });

  private current(): ContactInsightsFilter {
    const v = this.filters.getRawValue();
    return {
      from: isoDate(v.from) ?? '',
      to: isoDate(v.to) ?? '',
      creditorId: v.creditor[0] ?? null,
      portfolioId: v.portfolio[0] ?? null,
      personType: v.personType || null,
    };
  }
}
