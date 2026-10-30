import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, catchError, finalize, forkJoin, interval, map, of, startWith } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permission';
import { Amount } from '../../shared/components/amount/amount';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { KpiCard } from '../analytics/components/kpi-card';
import { CreditorLookup } from '../organization/data/organization.lookups';
import { PublicApiUsageApi } from '../public-api/data/public-api.api';
import { METRIC_LABELS, formatCycle, prorationLabel } from '../public-api/data/public-api.labels';
import { BillingPositionView } from '../public-api/data/public-api.models';
import { TransfersApi } from '../settlement/data/settlement.api';
import { SettlementPositionView } from '../settlement/data/settlement.models';
import { CumulativeChart, mergeCumulative } from './cumulative-chart';

/** Intervalo da atualização automática (o consumo da API chega em lotes de ~1 min; a posição de repasse tem cache de 60 s). */
export const POSITION_REFRESH_MS = 60_000;

const DIRECTION_LABELS: Record<SettlementPositionView['direction'], string> = {
  AGENCY_TO_CREDITOR: 'A assessoria repassa ao credor',
  CREDITOR_TO_AGENCY: 'O credor deve à assessoria',
  NONE: 'Sem valor a repassar',
};

/**
 * Posição do credor a qualquer momento, sem esperar o fechamento: o ciclo de consumo da API em andamento (módulo
 * publicapi) e o repasse estimado do período em aberto (módulo settlement), compostos aqui na tela — nenhum módulo do
 * backend depende do outro. Cada bloco aparece conforme a permissão (consumo da API / prestação de contas).
 */
@Component({
  selector: 'app-creditor-position',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, AppDatePipe, CumulativeChart, EmptyState, EntityPicker, Icon, KpiCard, PageHeader],
  template: `
    <app-page-header title="Posição do credor" subtitle="Faturamento da API e repasse do período em aberto, atualizados agora — sem esperar o fechamento." />

    <form class="app-filters" [formGroup]="form" role="search" aria-label="Escolher credor" data-tour="position-filters">
      <div class="app-filters__wide">
        <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
      </div>
      <div class="app-filters__actions">
        <div class="form-check form-switch mb-0">
          <input class="form-check-input" type="checkbox" role="switch" id="cp-auto" formControlName="auto" />
          <label class="form-check-label" for="cp-auto">Atualizar a cada minuto</label>
        </div>
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="refresh()" [disabled]="!creditorId() || loading()" data-tour="position-refresh">
          <app-icon name="arrow-clockwise" />
          Atualizar
        </button>
      </div>
    </form>

    @if (!creditorId()) {
      <app-empty-state icon="building" title="Escolha um credor" message="A posição mostra o ciclo de consumo da API e o repasse do período em aberto do credor." />
    } @else {
      @if (canBilling()) {
        <section class="app-card" aria-labelledby="cp-billing" data-tour="position-billing">
          <div class="cp-head">
            <h2 class="app-card__title" id="cp-billing">Faturamento da API — ciclo em andamento</h2>
            @if (billing(); as b) {
              <span class="app-muted cp-small">{{ b.updatedAt ? 'Consumo atualizado em ' : 'Sem consumo gravado no ciclo' }}{{ b.updatedAt ? (b.updatedAt | appDate: 'datetime') : '' }} · gravado em lotes de cerca de {{ b.flushIntervalSeconds }} s</span>
            }
          </div>
          @if (billingError()) {
            <div class="alert alert-danger" role="alert">Não foi possível carregar o faturamento da API.</div>
          } @else if (billing(); as b) {
            <p class="app-muted cp-small">
              Ciclo {{ cycle(b.periodStart, b.periodEnd) }} (fecha no dia {{ b.usageClosingDay }}) · dia {{ b.daysElapsed }} de {{ b.cycleDays }}, faltam {{ b.daysRemaining }}
              · {{ b.pricingVersion ? 'contrato ' + b.contractNumber + ', condições v' + b.pricingVersion : 'contrato sem condições de cobrança (valor zero)' }}
              @if (proration(b); as p) { · ciclo de transição: mensalidade e franquias proporcionais a {{ p }} }
            </p>
            <div class="cp-kpis">
              <app-kpi-card label="Valor até agora" detail="Mensalidade do ciclo + excedente consumido"><app-amount [value]="b.amountSoFar" /></app-kpi-card>
              <app-kpi-card label="Projeção no fechamento" detail="Média diária × dias restantes"><app-amount [value]="b.projection.amount" /></app-kpi-card>
              <app-kpi-card label="Chamadas cobráveis" detail="Respostas 2xx/3xx">{{ n(b.billableRequests) }}</app-kpi-card>
              <app-kpi-card label="Dados" detail="MB inteiros por token (1 MB = 1.048.576 bytes)">{{ n(b.billableMbIn) }} MB rec. · {{ n(b.billableMbOut) }} MB env.</app-kpi-card>
              <app-kpi-card label="Registros" detail="Processados no ciclo">{{ n(b.records) }}</app-kpi-card>
            </div>
            @if (b.allowances.length) {
              <h3 class="app-section-title">Franquias do ciclo</h3>
              <ul class="cp-allowances">
                @for (a of b.allowances; track a.metric) {
                  <li>
                    <span>{{ labels[a.metric] }}</span>
                    <span class="app-numeric">{{ n(a.used) }} de {{ n(a.included) }}{{ a.percent === null ? ' (sem franquia)' : ' (' + pct(a.percent) + ')' }}</span>
                    <div class="progress" role="progressbar" [attr.aria-label]="'Uso da franquia: ' + labels[a.metric]" [attr.aria-valuenow]="a.percent ?? 0" aria-valuemin="0" aria-valuemax="100">
                      <div class="progress-bar" [class.bg-warning]="(a.percent ?? 0) >= 100" [style.width.%]="min(a.percent ?? 0, 100)"></div>
                    </div>
                  </li>
                }
              </ul>
            }
            <app-cumulative-chart
              title="Valor acumulado do ciclo"
              [summary]="billingSummary()"
              [rows]="billingRows()"
              currentLabel="Ciclo atual"
              previousLabel="Ciclo anterior"
              [projection]="{ day: b.cycleDays, value: b.projection.amount }"
            />
            @if (tokens(b).length) {
              <div class="table-responsive mt-3">
                <table class="table table-sm mb-0">
                  <caption class="visually-hidden">Consumo do ciclo por token</caption>
                  <thead><tr><th scope="col">Token</th><th scope="col" class="text-end">Chamadas</th><th scope="col" class="text-end">Cobráveis</th><th scope="col" class="text-end">MB rec.</th><th scope="col" class="text-end">MB env.</th><th scope="col" class="text-end">Registros</th></tr></thead>
                  <tbody>
                    @for (t of tokens(b); track t.key) {
                      <tr>
                        <td>{{ t.label || t.key }}</td>
                        <td class="text-end app-numeric">{{ n(t.totals['requests'] ?? 0) }}</td>
                        <td class="text-end app-numeric">{{ n(t.totals['billableRequests'] ?? 0) }}</td>
                        <td class="text-end app-numeric">{{ n(t.totals['billableMbIn'] ?? 0) }}</td>
                        <td class="text-end app-numeric">{{ n(t.totals['billableMbOut'] ?? 0) }}</td>
                        <td class="text-end app-numeric">{{ n(t.totals['records'] ?? 0) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          } @else {
            <p class="app-muted" role="status">Carregando…</p>
          }
        </section>
      }

      @if (canSettlement()) {
        <section class="app-card" aria-labelledby="cp-settlement" data-tour="position-settlement">
          <div class="cp-head">
            <h2 class="app-card__title" id="cp-settlement">Repasse ao credor — período em aberto</h2>
            @if (settlement(); as s) {
              <span class="app-muted cp-small">Calculado em {{ s.calculatedAt | appDate: 'datetime' }}{{ s.cached ? ' (reaproveitado, sem alterações desde então)' : '' }}</span>
            }
          </div>
          @if (settlementError()) {
            <div class="alert alert-danger" role="alert">Não foi possível carregar a posição do repasse.</div>
          } @else if (settlement(); as s) {
            <p class="app-muted cp-small">
              De {{ s.periodStart | appDate: 'date' }} até hoje ({{ s.asOf | appDate: 'date' }}) · fechamento previsto em {{ s.expectedPeriodEnd | appDate: 'date' }}
              @if (s.openStatementId) { · inclui o demonstrativo em aberto ({{ statusLabel(s.openStatementStatus) }}) }
              · estimativa: calculada como o demonstrativo calcularia se fechado agora; pode mudar até o fechamento.
            </p>
            <div class="cp-kpis">
              <app-kpi-card label="Líquido estimado" [detail]="direction(s)"><app-amount [value]="s.totals.netTransfer" /></app-kpi-card>
              <app-kpi-card label="Recuperado" [detail]="'Na assessoria · direto ao credor: ' + money(s.totals.receivedByCreditor)"><app-amount [value]="s.totals.recovered" /></app-kpi-card>
              <app-kpi-card label="Comissão apurada" [detail]="'Valores fixos: ' + money(s.totals.fixedFees)"><app-amount [value]="s.totals.commission" /></app-kpi-card>
              <app-kpi-card label="Honorários da assessoria" detail="Retidos, fora da base"><app-amount [value]="s.totals.agencyHonorarium" /></app-kpi-card>
              <app-kpi-card label="Despesas e ajustes" [detail]="'Despesas ' + money(s.totals.expenses) + ' · ajustes ' + money(s.totals.adjustments)"><app-amount [value]="s.totals.adjustments - s.totals.expenses" /></app-kpi-card>
            </div>
            @if (s.totals.pendingReconciliation || s.totals.pendingRules) {
              <div class="alert alert-warning d-flex gap-2 align-items-start cp-small" role="note">
                <app-icon name="exclamation-triangle" />
                <span>Fora do líquido: <strong><app-amount [value]="s.totals.pendingReconciliation" /></strong> aguardando conciliação e
                  <strong><app-amount [value]="s.totals.pendingRules" /></strong> sem regra de comissão vigente.</span>
              </div>
            }
            <app-cumulative-chart
              title="Líquido acumulado do período"
              [summary]="settlementSummary()"
              [rows]="settlementRows()"
              currentLabel="Período em aberto"
              [previousLabel]="s.previous ? 'Demonstrativo nº ' + s.previous.sequenceNumber : 'Período anterior'"
            />
            <h3 class="app-section-title">Demonstrativos aprovados com saldo</h3>
            @if (!s.closedWithBalance.length) {
              <p class="app-muted">Nenhum saldo pendente de demonstrativos aprovados.</p>
            } @else {
              <p class="cp-small">A repassar: <strong><app-amount [value]="s.outstandingToTransfer" /></strong> · a receber do credor: <strong><app-amount [value]="s.outstandingToReceive" /></strong></p>
              <div class="table-responsive">
                <table class="table table-sm mb-0">
                  <caption class="visually-hidden">Demonstrativos aprovados com saldo</caption>
                  <thead><tr><th scope="col">Nº</th><th scope="col">Período</th><th scope="col" class="text-end">Líquido</th><th scope="col" class="text-end">Repassado</th><th scope="col" class="text-end">Saldo</th></tr></thead>
                  <tbody>
                    @for (c of s.closedWithBalance; track c.statementId) {
                      <tr>
                        <td>{{ c.sequenceNumber }}</td>
                        <td>{{ cycle(c.periodStart, c.periodEnd) }}</td>
                        <td class="text-end"><app-amount [value]="c.netTransfer" /></td>
                        <td class="text-end"><app-amount [value]="c.transferred" /></td>
                        <td class="text-end"><app-amount [value]="c.outstanding" /></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          } @else {
            <p class="app-muted" role="status">Carregando…</p>
          }
        </section>
      }
    }
  `,
  styles: `
    .cp-head { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 4px 12px; }
    .cp-small { font-size: var(--app-text-xs); }
    .cp-kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 190px), 1fr)); gap: 12px; margin-bottom: 16px; }
    .cp-allowances { list-style: none; padding: 0; margin: 0 0 16px; display: grid; gap: 8px;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr)); font-size: var(--app-text-xs); }
    .cp-allowances li { display: grid; grid-template-columns: 1fr auto; gap: 2px 8px; }
    .cp-allowances .progress { grid-column: 1 / -1; height: 6px; }
  `,
})
export class CreditorPosition {
  private readonly auth = inject(AuthService);
  private readonly billingApi = inject(PublicApiUsageApi);
  private readonly settlementApi = inject(TransfersApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly labels = METRIC_LABELS;

  protected readonly canBilling = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_USAGE_VIEW));
  protected readonly canSettlement = computed(() => this.auth.hasPermission(Permission.SETTLEMENT_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>(this.route.snapshot.queryParamMap.get('credor') ? [this.route.snapshot.queryParamMap.get('credor')!] : []),
    auto: [true],
  });
  protected readonly creditorId = toSignal(
    this.form.controls.creditor.valueChanges.pipe(startWith(this.form.controls.creditor.value), map((v): string | null => v[0] ?? null)),
    { initialValue: null as string | null },
  );

  protected readonly billing = signal<BillingPositionView | null>(null);
  protected readonly settlement = signal<SettlementPositionView | null>(null);
  protected readonly billingError = signal(false);
  protected readonly settlementError = signal(false);
  protected readonly loading = signal(false);
  private timer: Subscription | null = null;

  protected readonly billingRows = computed(() => {
    const b = this.billing();
    return b ? mergeCumulative(b.daily, b.previousDaily, (p) => p.amount) : [];
  });
  protected readonly settlementRows = computed(() => {
    const s = this.settlement();
    return s ? mergeCumulative(s.daily, s.previousDaily, (p) => p.netTransfer) : [];
  });
  protected readonly billingSummary = computed(() => {
    const b = this.billing();
    if (!b) {
      return '';
    }
    const prev = b.previousDaily.find((p) => p.day === b.daysElapsed);
    return `Até o dia ${b.daysElapsed}: ${this.money(b.amountSoFar)}` + (prev ? `; no mesmo dia do ciclo anterior: ${this.money(prev.amount)}.` : '.');
  });
  protected readonly settlementSummary = computed(() => {
    const s = this.settlement();
    if (!s || !s.daily.length) {
      return '';
    }
    const day = s.daily[s.daily.length - 1].day;
    const prev = s.previousDaily.find((p) => p.day === day);
    return `Até o dia ${day}: ${this.money(s.totals.netTransfer)}` + (prev ? `; no mesmo dia do período anterior: ${this.money(prev.netTransfer)}.` : '.');
  });

  constructor() {
    this.form.controls.creditor.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => {
      void this.router.navigate([], { queryParams: { credor: v[0] ?? null }, queryParamsHandling: 'merge', replaceUrl: true });
      this.refresh();
    });
    this.form.controls.auto.valueChanges.pipe(startWith(this.form.controls.auto.value), takeUntilDestroyed())
      .subscribe((auto) => this.schedule(auto));
    if (this.creditorId()) {
      this.refresh();
    }
  }

  /** Recarrega os dois blocos (cada um só se o perfil puder vê-lo). */
  protected refresh(): void {
    const id = this.form.controls.creditor.value[0];
    if (!id) {
      this.billing.set(null);
      this.settlement.set(null);
      return;
    }
    this.loading.set(true);
    const none = of(null);
    forkJoin({
      billing: this.canBilling()
        ? this.billingApi.position(id).pipe(catchError(() => {
            this.billingError.set(true);
            return none;
          }))
        : none,
      settlement: this.canSettlement()
        ? this.settlementApi.position(id).pipe(catchError(() => {
            this.settlementError.set(true);
            return none;
          }))
        : none,
    })
      .pipe(finalize(() => this.loading.set(false)), takeUntilDestroyed(this.destroyRef))
      .subscribe(({ billing, settlement }) => {
        if (billing) {
          this.billingError.set(false);
          this.billing.set(billing);
        }
        if (settlement) {
          this.settlementError.set(false);
          this.settlement.set(settlement);
        }
      });
  }

  private schedule(auto: boolean): void {
    this.timer?.unsubscribe();
    this.timer = auto
      ? interval(POSITION_REFRESH_MS).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
          if (!document.hidden) {
            this.refresh();
          }
        })
      : null;
  }

  protected n(value: number): string {
    return new Intl.NumberFormat('pt-BR').format(value);
  }

  protected pct(value: number): string {
    return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(value)}%`;
  }

  protected money(value: number): string {
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
  }

  protected min(a: number, b: number): number {
    return Math.min(a, b);
  }

  protected cycle(start: string, end: string): string {
    return formatCycle(start, end);
  }

  protected proration(b: BillingPositionView): string | null {
    return prorationLabel(b.cycleDays, b.referenceCycleDays);
  }

  protected direction(s: SettlementPositionView): string {
    return DIRECTION_LABELS[s.direction];
  }

  protected statusLabel(status: string | null): string {
    return status === 'DRAFT' ? 'rascunho' : status === 'CALCULATED' ? 'calculado' : status === 'REVIEWED' ? 'revisado' : '';
  }

  protected tokens(b: BillingPositionView): BillingPositionView['breakdown'] {
    return b.breakdown.filter((t) => t.kind === 'TOKEN');
  }
}
