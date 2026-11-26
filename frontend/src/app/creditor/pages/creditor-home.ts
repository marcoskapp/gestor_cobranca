import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map, of } from 'rxjs';
import { findScreen } from '../../core/onboarding/content/screens';
import { findTour } from '../../core/onboarding/content/tours';
import { loadScreenManual } from '../../core/onboarding/manual/manual-index';
import { composeScreenTour } from '../../core/onboarding/manual/screen-tour';
import { TourEngine } from '../../core/onboarding/tour/tour-engine.service';
import { Amount, formatAmount } from '../../shared/components/amount/amount';
import { BarChart } from '../../shared/components/charts/bar-chart';
import { ColumnChart } from '../../shared/components/charts/column-chart';
import { Icon } from '../../shared/components/icon/icon';
import { KpiCard, KpiTone } from '../../shared/components/kpi-card/kpi-card';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { urlTab } from '../../shared/data/url-state';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import {
  AWAITING_PERMISSIONS,
  AWAITING_PREVIEW,
  AWAITING_WHO,
  CreditorAwaitingState,
  awaitingAction,
  awaitingLink,
  awaitingQuery,
  awaitingReason,
  awaitingTitle,
  unavailableText,
} from '../data/creditor-awaiting';
import {
  CREDITOR_PERIODS,
  DEFAULT_PERIOD_PARAM,
  PERIOD_PARAMS,
  agreementsDetail,
  agreementsValue,
  dayMonthYear,
  monthlyRows,
  monthlySummary,
  periodOf,
  portfolioRows,
  portfolioSummary,
  recoveryDetail,
} from '../data/creditor-indicators';
import { CreditorOverviewApi } from '../data/creditor-overview.api';
import { CreditorSession } from '../data/creditor-session';
import { CreditorAwaiting, CreditorAwaitingItem, CreditorOverview, CreditorPermission } from '../data/creditor.models';
import { directionText } from './creditor-statements';

const COUNT = new Intl.NumberFormat('pt-BR');

/**
 * Início do portal do credor (fatia 7). Em cima, "Acordos em dia" (número principal), o saldo em cobrança, o recuperado
 * no período escolhido, o repasse estimado e o "Aguardando você"; depois a lista do que depende do credor; os gráficos
 * do recuperado por mês e por carteira; no fim, a conta da pessoa. Indicadores de `GET /overview` (período na URL, em
 * `?periodo=`), pendências de `GET /awaiting` (compartilhado com o contador do menu). Cada cartão carrega, falha e se
 * recupera sozinho, e cada fonte fora do ar no servidor (`unavailable`) derruba só os cartões dela. Tudo, menos a
 * conta, exige a permissão de visão geral do portal.
 */
@Component({
  selector: 'app-creditor-home',
  imports: [NgTemplateOutlet, RouterLink, Amount, AppDatePipe, BarChart, ColumnChart, Icon, KpiCard, PageHeader, Skeleton],
  template: `
    @if (session.me(); as m) {
      <app-page-header [title]="'Olá, ' + firstName()" [subtitle]="m.creditorName">
        @if (canOverview()) {
          <button class="btn btn-outline-secondary" type="button" (click)="startTour()">
            <app-icon name="signpost" /> Conhecer o Início
          </button>
        }
      </app-page-header>

      @if (canOverview()) {
        <div class="ch-toolbar" data-tour="creditor-home-period">
          <!-- botões a partir de sm; no celular, uma lista de escolha (os quatro botões não cabem numa linha) -->
          <div class="btn-group flex-wrap d-none d-sm-inline-flex" role="group" aria-label="Período dos indicadores">
            @for (p of periods; track p.param) {
              <button type="button" class="btn btn-outline-secondary" [class.active]="period.value() === p.param"
                      [attr.aria-pressed]="period.value() === p.param" (click)="period.set(p.param)">{{ p.label }}</button>
            }
          </div>
          <div class="d-sm-none ch-toolbar__select">
            <label class="visually-hidden" for="ch-periodo">Período dos indicadores</label>
            <select id="ch-periodo" class="form-select" [value]="period.value()" (change)="choosePeriod($event)">
              @for (p of periods; track p.param) {
                <option [value]="p.param" [selected]="period.value() === p.param">{{ p.label }}</option>
              }
            </select>
          </div>
          <p class="ch-toolbar__meta app-muted mb-0" aria-live="polite">
            @if (ov(); as o) {
              @if (o.from && o.to) {
                <span>{{ o.from | appDate: 'date' }} a {{ o.to | appDate: 'date' }}@if (o.previousFrom && o.previousTo) {, comparado com {{ o.previousFrom | appDate: 'date' }} a {{ o.previousTo | appDate: 'date' }}}</span>
              }
              <span>{{ o.dataUntil ? 'Dados até ' + (o.dataUntil | appDate) : 'Os números ainda estão sendo preparados.' }}</span>
            }
          </p>
        </div>

        <!-- falha do resumo: um aviso só, com um só "Tentar novamente"; os cartões e gráficos dizem "Indisponível agora" -->
        @if (overviewWarning(); as w) {
          <div class="alert alert-warning d-flex flex-wrap align-items-center gap-2" role="alert">
            <app-icon name="exclamation-triangle" />
            <span class="flex-grow-1">{{ w }}</span>
            <button class="btn btn-outline-secondary btn-sm" type="button" (click)="reloadOverview()">Tentar novamente</button>
          </div>
        }

        <div class="app-kpi-grid" data-tour="creditor-home-kpis">
          <app-kpi-card label="Acordos em dia" info="creditor.home.agreements" [manualLink]="false" emphasis
                        [variant]="agreementsEmpty() ? 'text' : 'number'" [tone]="agreementsTone()" [detail]="agreementsText()">
            @if (indicators(); as o) {
              {{ agreementsValue(o.agreements!) }}
            } @else {
              <ng-container *ngTemplateOutlet="kpiState" />
            }
          </app-kpi-card>
          <app-kpi-card label="Saldo em cobrança" info="creditor.home.balance" [manualLink]="false" [detail]="balanceText()">
            @if (indicators(); as o) {
              <app-amount [value]="o.balance!.amount" [compact]="true" />
            } @else {
              <ng-container *ngTemplateOutlet="kpiState" />
            }
          </app-kpi-card>
          <app-kpi-card label="Recuperado no período" info="creditor.home.recovered" [manualLink]="false" [detail]="recoveryText()">
            @if (indicators(); as o) {
              <app-amount [value]="o.recovery!.recovered" [compact]="true" />
            } @else {
              <ng-container *ngTemplateOutlet="kpiState" />
            }
          </app-kpi-card>
          <app-kpi-card label="Repasse estimado" info="creditor.home.transfer" [manualLink]="false" [detail]="transferText()">
            @if (transfer(); as t) {
              <app-amount [value]="abs(t.netTransfer)" [compact]="true" />
            } @else {
              <ng-container *ngTemplateOutlet="kpiState" />
            }
          </app-kpi-card>
          <app-kpi-card [label]="awaitingLabel()" info="creditor.home.awaiting" [manualLink]="false" [detail]="awaitingDetail()"
                        [tone]="awaitingTone()">
            @if (awaiting(); as a) {
              {{ count(a.total) }}
            } @else if (awaitingFailed()) {
              —
            } @else {
              <span class="placeholder-glow" aria-hidden="true"><span class="placeholder col-4"></span></span>
              <span class="visually-hidden">Carregando</span>
            }
          </app-kpi-card>
        </div>

        <section class="app-card" aria-labelledby="ch-aguardando" data-tour="creditor-home-awaiting">
          <div class="ch-card-head">
            <h2 class="app-card__title" id="ch-aguardando">
              {{ awaitingLabel() }}@if (awaiting(); as a) { ({{ count(a.total) }}) }
            </h2>
            @if (awaiting(); as a) {
              @if (a.items.length > preview) {
                <button class="btn btn-link" type="button" (click)="expanded.set(!expanded())" [attr.aria-expanded]="expanded()"
                        aria-controls="ch-aguardando-lista">
                  {{ expanded() ? 'Ver menos' : 'Ver todas (' + count(a.total) + ')' }}
                </button>
              }
            }
          </div>
          @if (awaiting(); as a) {
            @if (a.unavailable.length) {
              <div class="alert alert-warning d-flex flex-wrap align-items-center gap-2" role="alert">
                <app-icon name="exclamation-triangle" />
                <span class="flex-grow-1">Não foi possível carregar {{ unavailable(a) }} agora; a lista abaixo pode estar incompleta.</span>
                <button class="btn btn-outline-secondary btn-sm" type="button" (click)="reloadAwaiting()">Tentar novamente</button>
              </div>
            }
            @if (a.items.length) {
              <ul class="list-unstyled mb-0" id="ch-aguardando-lista">
                @for (item of shown(); track item.kind + item.id) {
                  <li class="ch-item" [class.ch-item--late]="item.overdue">
                    <app-icon class="ch-item__icon" [name]="icon(item)" />
                    <div class="ch-item__text">
                      <a class="link-body-emphasis" [routerLink]="link(item)" [queryParams]="query(item)">{{ title(item) }}</a>
                      <span class="app-subline ch-item__action" [class.ch-late]="item.overdue">
                        @if (item.overdue) { <span class="visually-hidden">Atenção: </span> }{{ action(item) }}
                      </span>
                      @if (reason(item); as r) {
                        <span class="app-subline">{{ r }}</span>
                      }
                      @if (!item.youCanAct) {
                        <span class="app-subline">{{ who(item) }}</span>
                      }
                    </div>
                  </li>
                }
              </ul>
              @if (a.total > preview) {
                <p class="app-muted ch-foot mb-0">
                  @if (expanded() && a.total > a.items.length) {
                    <span>Mostrando os {{ count(a.items.length) }} mais urgentes de {{ count(a.total) }}.</span>
                  }
                  @for (l of lists(a); track l.path) {
                    <a [routerLink]="['/credor', slug(), l.path]">{{ l.label }} ({{ count(l.count) }})</a>
                  }
                </p>
              }
            } @else if (!a.unavailable.length) {
              <p class="mb-0">Nada depende de você agora.</p>
              <p class="app-muted mb-0">Quando a assessoria pedir a sua posição ou enviar um demonstrativo, o pedido aparece aqui com o prazo.</p>
            }
          } @else if (awaitingFailed()) {
            <div class="alert alert-warning d-flex flex-wrap align-items-center gap-2 mb-0" role="alert">
              <app-icon name="exclamation-triangle" />
              <span>Não foi possível carregar as pendências agora.</span>
              <button class="btn btn-outline-secondary btn-sm" type="button" (click)="reloadAwaiting()">Tentar novamente</button>
              <a class="btn btn-link btn-sm" [routerLink]="['/credor', slug(), 'contestacoes']">Abrir contestações</a>
            </div>
          } @else {
            <app-skeleton variant="lines" [rows]="3" />
          }
        </section>

        <div class="app-columns ch-columns" data-tour="creditor-home-charts">
          <section class="app-card" aria-label="Recuperado nos últimos 6 meses">
            @if (indicators(); as o) {
              <app-column-chart
                title="Recuperado nos últimos 6 meses"
                info="creditor.home.monthly"
                [manualLink]="false"
                [summary]="monthlySummary()"
                [series]="{ name: 'Recuperado', color: '--app-chart-1' }"
                [rows]="monthly()"
                categoryHeader="Mês"
                emptyText="Nada recuperado nos últimos 6 meses."
              />
            } @else if (indicatorsFailed()) {
              <ng-container *ngTemplateOutlet="unavailableChart; context: { $implicit: 'Recuperado nos últimos 6 meses' }" />
            } @else {
              <app-skeleton variant="lines" [rows]="5" />
            }
          </section>
          <section class="app-card" aria-label="Recuperado por carteira">
            @if (indicators(); as o) {
              <app-bar-chart
                title="Recuperado por carteira"
                info="creditor.home.by-portfolio"
                [manualLink]="false"
                [summary]="portfolioSummary()"
                [series]="[{ name: 'Recuperado', color: '--app-chart-1', format: money }]"
                [rows]="portfolios()"
                categoryHeader="Carteira"
                emptyText="Nada recuperado no período escolhido."
              />
            } @else if (indicatorsFailed()) {
              <ng-container *ngTemplateOutlet="unavailableChart; context: { $implicit: 'Recuperado por carteira' }" />
            } @else {
              <app-skeleton variant="lines" [rows]="5" />
            }
          </section>
        </div>
      }

      <section class="app-card" aria-labelledby="ch-conta" data-tour="creditor-home-account">
        <h2 class="app-card__title" id="ch-conta">Sua conta</h2>
        <dl class="app-definition-list">
          <dt>Credor</dt>
          <dd>{{ m.creditorName }}</dd>
          <dt>Assessoria</dt>
          <dd>{{ m.tenantName }}</dd>
          <dt>Perfil</dt>
          <dd>{{ m.roleLabel }}</dd>
          <dt>E-mail</dt>
          <dd>{{ m.email }}</dd>
          @if (m.lastLoginAt) {
            <dt>Acesso anterior</dt>
            <dd>{{ m.lastLoginAt | appDate }}</dd>
          }
        </dl>
        <p class="app-muted mb-0">
          Cada ação no portal fica registrada. Se notar algo que não fez, avise a assessoria.
          @if (canOverview()) {
            <a [routerLink]="['/credor', slug(), 'atividade']">Ver minha atividade</a>
          }
        </p>
      </section>

      <!-- estado do valor de um cartão do resumo: carregando ou "—" (o detalhe diz o motivo) -->
      <ng-template #kpiState>
        @if (overview.isLoading()) {
          <span class="placeholder-glow" aria-hidden="true"><span class="placeholder col-8"></span></span>
          <span class="visually-hidden">Carregando</span>
        } @else {
          —
        }
      </ng-template>
      <!-- gráfico sem dado: só o título e "Indisponível agora" (o aviso e o "Tentar novamente" ficam acima dos cartões) -->
      <ng-template #unavailableChart let-title>
        <h2 class="app-card__title">{{ title }}</h2>
        <p class="app-muted mb-0">Indisponível agora.</p>
      </ng-template>
    }
  `,
  styles: `
    .ch-toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--app-space-2) var(--app-space-3);
      margin-bottom: var(--app-space-3);
    }
    .ch-toolbar__select {
      flex: 1 1 100%;
    }
    .ch-toolbar__meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0 var(--app-space-3);
      font-size: var(--app-text-sm);
    }
    .ch-card-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--app-space-2);
    }
    .ch-item {
      display: flex;
      align-items: flex-start;
      gap: var(--app-space-2);
      padding: var(--app-space-2) 0;
    }
    .ch-item + .ch-item {
      border-top: var(--bs-border-width) solid var(--app-border);
    }
    .ch-item__icon {
      flex: none;
      margin-top: 2px;
      color: var(--bs-secondary-color);
    }
    .ch-item--late .ch-item__icon,
    .ch-late {
      color: var(--app-danger-fg);
    }
    .ch-late {
      font-weight: var(--app-weight-semibold);
    }
    .ch-item__text {
      min-width: 0;
    }
    .ch-foot {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-1) var(--app-space-3);
      margin-top: var(--app-space-2);
      padding-top: var(--app-space-2);
      border-top: var(--bs-border-width) solid var(--app-border);
    }
    @media (min-width: 768px) {
      .ch-columns {
        grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      }
    }
  `,
})
export class CreditorHome {
  protected readonly session = inject(CreditorSession);
  private readonly api = inject(CreditorOverviewApi);
  private readonly awaitingState = inject(CreditorAwaitingState);
  private readonly tour = inject(TourEngine);
  private readonly route = inject(ActivatedRoute);
  protected readonly slug = toSignal(this.route.parent?.paramMap.pipe(map((p) => p.get('slug') ?? '')) ?? of(''), { initialValue: '' });

  protected readonly firstName = computed(() => this.session.me()?.name.trim().split(/\s+/)[0] ?? '');
  /** Indicadores, pendências e gráficos: só com a visão geral do portal (sem ela, só a conta e nenhuma chamada). */
  protected readonly canOverview = computed(() => this.session.has(CreditorPermission.OVERVIEW));
  protected readonly preview = AWAITING_PREVIEW;
  protected readonly periods = CREDITOR_PERIODS;
  protected readonly count = (n: number) => COUNT.format(n);
  protected readonly abs = (value: number) => Math.abs(Number(value));
  protected readonly money = (value: number) => formatAmount(value);
  protected readonly agreementsValue = agreementsValue;

  // ---------------------------------------------------------------- resumo (GET /overview)
  /** Período em `?periodo=` (padrão "este mês", fora da URL); trocar não cria entrada no histórico. */
  protected readonly period = urlTab(PERIOD_PARAMS, DEFAULT_PERIOD_PARAM, 'periodo');
  protected readonly overview = rxResource({
    params: () => (this.canOverview() ? periodOf(this.period.value()) : undefined),
    stream: ({ params }) => this.api.overview(params),
  });
  protected readonly reloadOverview = () => this.overview.reload();
  protected readonly ov = computed<CreditorOverview | null>(() => (this.overview.hasValue() ? this.overview.value()! : null));
  /** Resposta com os indicadores (a fonte deles respondeu). */
  protected readonly indicators = computed(() => {
    const o = this.ov();
    return o && !o.unavailable.includes('INDICATORS') && o.agreements && o.balance && o.recovery ? o : null;
  });
  protected readonly indicatorsFailed = computed(() => !!this.overview.error() || (!!this.ov() && !this.indicators()));
  protected readonly transfer = computed(() => {
    const o = this.ov();
    return o && !o.unavailable.includes('TRANSFER') ? o.transfer : null;
  });
  protected readonly transferFailed = computed(() => !!this.overview.error() || (!!this.ov() && !this.transfer()));

  private failedText(failed: boolean): string | null {
    return failed ? 'Indisponível agora' : null;
  }

  /** Aviso único acima dos cartões quando o resumo (ou uma fonte dele) falhou; nulo quando tudo carregou. */
  protected readonly overviewWarning = computed(() => {
    if (this.overview.error()) {
      return 'Não foi possível carregar os indicadores agora.';
    }
    const parts = [
      ...(this.ov() && !this.indicators() ? ['os indicadores', 'os gráficos'] : []),
      ...(this.ov() && !this.transfer() ? ['o repasse estimado'] : []),
    ];
    if (!parts.length) {
      return null;
    }
    const what = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} e ${parts.at(-1)}`;
    return `Não foi possível carregar ${what} agora.`;
  });

  protected choosePeriod(event: Event): void {
    this.period.set((event.target as HTMLSelectElement).value);
  }

  protected readonly agreementsEmpty = computed(() => {
    const a = this.indicators()?.agreements;
    return !!a && (a.ratio === null || a.active === 0);
  });
  protected readonly agreementsTone = computed<KpiTone | null>(() => (this.indicators()?.agreements?.alert ? 'warning' : null));
  protected readonly agreementsText = computed(() => {
    const a = this.indicators()?.agreements;
    return a ? agreementsDetail(a) : this.failedText(this.indicatorsFailed());
  });
  protected readonly balanceText = computed(() => {
    const b = this.indicators()?.balance;
    if (!b) {
      return this.failedText(this.indicatorsFailed());
    }
    return `${COUNT.format(b.titles)} ${b.titles === 1 ? 'título' : 'títulos'} em cobrança em ${dayMonthYear(b.referenceDate)}`;
  });
  protected readonly recoveryText = computed(() => {
    const r = this.indicators()?.recovery;
    return r ? recoveryDetail(r) : this.failedText(this.indicatorsFailed());
  });
  protected readonly transferText = computed(() => {
    const t = this.transfer();
    if (!t) {
      return this.failedText(this.transferFailed());
    }
    const since = t.periodStart ? `Período em aberto desde ${dayMonthYear(t.periodStart)}. ` : '';
    return `${since}${directionText(t.direction, true)}; o valor final sai no fechamento`;
  });

  protected readonly monthly = computed(() => monthlyRows(this.indicators()?.monthly ?? []));
  protected readonly monthlySummary = computed(() => monthlySummary(this.indicators()?.monthly ?? []));
  protected readonly portfolios = computed(() => portfolioRows(this.indicators()?.byPortfolio ?? []).filter((r) => r.values[0] > 0));
  protected readonly portfolioSummary = computed(() => portfolioSummary(this.indicators()?.byPortfolio ?? []));

  // ---------------------------------------------------------------- pendências (GET /awaiting, o mesmo do menu)
  private readonly awaitingResource = this.awaitingState.resource;
  protected readonly awaiting = computed<CreditorAwaiting | null>(() =>
    this.awaitingResource.hasValue() ? this.awaitingResource.value()! : null,
  );
  protected readonly awaitingFailed = computed(() => !!this.awaitingResource.error());
  protected readonly reloadAwaiting = () => this.awaitingResource.reload();
  /**
   * "Aguardando você" quando a pessoa resolve ao menos um item; "Aguardando o seu time" quando nenhum é dela. Sem item,
   * vale o perfil: quem não responde por nada (Consulta) vê o bloco do time.
   */
  protected readonly awaitingLabel = computed(() => {
    const items = this.awaiting()?.items ?? [];
    const mine = items.length
      ? items.some((i) => i.youCanAct)
      : AWAITING_PERMISSIONS.some((p) => this.session.has(p));
    return mine ? 'Aguardando você' : 'Aguardando o seu time';
  });
  /** "Ver todas" abre a lista inteira no próprio cartão (as pendências são de listas diferentes). */
  protected readonly expanded = signal(false);
  protected readonly shown = computed(() => {
    const items = this.awaiting()?.items ?? [];
    return this.expanded() ? items : items.slice(0, AWAITING_PREVIEW);
  });
  protected readonly awaitingDetail = computed(() => {
    if (this.awaitingFailed()) {
      return 'Indisponível agora';
    }
    const a = this.awaiting();
    if (!a) {
      return null;
    }
    if (a.unavailable.length) {
      return 'Parte das pendências indisponível agora';
    }
    if (a.overdue) {
      return `${this.count(a.overdue)} com prazo vencido`;
    }
    if (a.dueSoon) {
      return `${this.count(a.dueSoon)} ${a.dueSoon === 1 ? 'vence' : 'vencem'} em até 2 dias`;
    }
    return a.total ? 'Nenhuma com prazo vencido' : 'Nada pendente';
  });
  protected readonly awaitingTone = computed<KpiTone | null>(() => {
    const a = this.awaiting();
    return a?.overdue ? 'danger' : a?.dueSoon ? 'warning' : null;
  });

  protected readonly title = awaitingTitle;
  protected readonly action = awaitingAction;
  protected readonly reason = awaitingReason;
  protected readonly unavailable = (a: CreditorAwaiting) => unavailableText(a.unavailable);
  protected readonly who = (item: CreditorAwaitingItem) => AWAITING_WHO[item.kind];
  protected link(item: CreditorAwaitingItem): unknown[] {
    return awaitingLink(item, this.slug());
  }
  protected readonly query = awaitingQuery;
  /** Listas de destino com itens, para o rodapé do cartão (as pendências vêm de listas diferentes). */
  protected lists(a: CreditorAwaiting): { path: string; label: string; count: number }[] {
    return [
      { path: 'contestacoes', label: 'Contestações', count: a.counts.disputes },
      { path: 'prestacao-de-contas', label: 'Prestação de contas', count: a.counts.statements },
      { path: 'pagamentos', label: 'Pagamentos', count: a.counts.rejectedPayments },
    ].filter((l) => l.count > 0);
  }
  protected icon(item: CreditorAwaitingItem): string {
    if (item.overdue) {
      return 'exclamation-triangle';
    }
    return item.kind === 'DISPUTE' ? 'chat-square-text' : item.kind === 'STATEMENT' ? 'file-earmark-text' : 'cash-coin';
  }

  // ---------------------------------------------------------------- tour leve (sem gravar desfecho: o portal não tem o estado de onboarding do app interno)
  protected async startTour(): Promise<void> {
    const screen = findScreen(`/credor/${this.slug()}/inicio`);
    const authored = findTour('creditor.home') ?? null;
    if (!screen || !authored) {
      return;
    }
    const manual = await loadScreenManual(screen.key).catch(() => undefined);
    const tour = composeScreenTour(screen, manual, authored);
    if (tour) {
      void this.tour.start({ ...tour, transient: true });
    }
  }
}
