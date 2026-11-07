import { Component, computed, input } from '@angular/core';
import { Amount } from '../../../../shared/components/amount/amount';
import { InfoHint } from '../../../../shared/components/info-hint/info-hint';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { KpiCard } from '../../../../shared/components/kpi-card/kpi-card';
import { formatCycle, prorationLabel } from '../../../public-api/data/public-api.labels';
import { PlatformPositionView } from '../../data/platform-billing.models';
import { PLATFORM_CYCLE_KIND_LABELS, PLATFORM_METRIC_LABELS } from '../platform-billing.labels';
import { PlatformChargeLines, PlatformBreakdownTables } from './platform-statement-parts';

const NUMBER = new Intl.NumberFormat('pt-BR');
const PERCENT = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });

/**
 * Posição do ciclo em andamento da cobrança da plataforma a uma assessoria (consumo somado de todos os tokens, de
 * todos os credores): valor até agora, projeção pela média diária, consumo cobrável, uso das franquias, linhas com as
 * faixas de volume e detalhamento por credor, token e operação. Usado na área da plataforma e na visão da assessoria.
 */
@Component({
  selector: 'app-platform-position-panel',
  imports: [Amount, AppDatePipe, InfoHint, KpiCard, PlatformBreakdownTables, PlatformChargeLines],
  template: `
    @let p = position();
    <p class="app-muted pp-small">
      Ciclo {{ cycle() }} ({{ p.cycleKind === 'POLICY_END' ? 'termina no fim da vigência' : 'fecha no dia ' + p.closingDay }}) · dia
      {{ p.daysElapsed }} de {{ p.cycleDays }}, faltam {{ p.daysRemaining }} ·
      {{ p.hasPolicy ? 'política da plataforma v' + p.policyVersion : 'sem política da plataforma: o consumo aparece só como informação' }}
      @if (proration(); as pr) { · {{ kindLabels[p.cycleKind] }}: mensalidade e franquias proporcionais a {{ pr }} }
      · {{ p.updatedAt ? 'consumo atualizado em ' : 'sem consumo gravado no ciclo' }}{{ p.updatedAt ? (p.updatedAt | appDate: 'datetime') : '' }}
    </p>
    <div class="app-kpi-grid">
      <app-kpi-card label="Valor até agora" info="platform.billing.amount" data-tour="kpi-amount" detail="Mensalidade do ciclo + excedente consumido"><app-amount [value]="p.amountSoFar" /></app-kpi-card>
      <app-kpi-card label="Projeção no fechamento" info="platform.billing.projection" data-tour="kpi-projection" detail="Consumo + média diária × dias restantes"><app-amount [value]="p.projection.amount" /></app-kpi-card>
      <app-kpi-card label="Chamadas cobráveis" info="platform.billing.billable-requests" [detail]="'de ' + n(p.requests) + ' chamadas, em ' + n(p.tokens) + ' tokens'">{{ n(p.billableRequests) }}</app-kpi-card>
      <app-kpi-card label="Dados recebidos e enviados" info="platform.billing.data" detail="MB inteiros por token (1 MB = 1.048.576 bytes)">{{ n(p.billableMbIn) }} / {{ n(p.billableMbOut) }} MB</app-kpi-card>
      <app-kpi-card label="Registros processados" info="platform.billing.records" [detail]="'De ' + n(p.creditors) + ' credores'">{{ n(p.records) }}</app-kpi-card>
    </div>

    @if (p.allowances.length) {
      <h3 class="app-section-title pp-title" data-tour="allowances">
        Franquias do ciclo
        <app-info-hint indicator="platform.billing.allowance" label="Uso das franquias" />
      </h3>
      <ul class="pp-allowances">
        @for (a of p.allowances; track a.metric) {
          <li>
            <span>{{ metricLabels[a.metric] }}</span>
            <span class="app-numeric">{{ n(a.used) }} de {{ n(a.included) }}{{ a.percent === null ? ' (sem franquia)' : ' (' + pct(a.percent) + '%)' }}</span>
            <div class="progress" role="progressbar" [attr.aria-label]="'Uso da franquia: ' + metricLabels[a.metric]" [attr.aria-valuenow]="a.percent ?? 0" aria-valuemin="0" aria-valuemax="100">
              <div class="progress-bar" [class.bg-warning]="(a.percent ?? 0) >= 100" [style.width.%]="min(a.percent ?? 0, 100)"></div>
            </div>
          </li>
        }
      </ul>
    }

    @if (p.lines.length) {
      <h3 class="app-section-title">Valor se o ciclo fechasse agora</h3>
      <app-platform-charge-lines [lines]="p.lines" [total]="p.amountSoFar" />
    }
    <div data-tour="breakdown">
      <app-platform-breakdown-tables [breakdown]="p.breakdown" />
    </div>
    @if (p.lastStatement; as last) {
      <p class="app-muted pp-small mb-0">
        Último extrato fechado: ciclo {{ range(last.periodStart, last.periodEnd) }} (revisão {{ last.revision }}) —
        <app-amount [value]="last.totalAmount" />
      </p>
    }
  `,
  styles: `
    .pp-small {
      font-size: var(--app-text-xs);
    }
    .pp-title {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .pp-allowances {
      list-style: none;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));
      gap: 8px 16px;
    }
    .pp-allowances li {
      display: grid;
      gap: 4px;
      grid-template-columns: 1fr auto;
      font-size: var(--app-text-sm);
    }
    .pp-allowances .progress {
      grid-column: 1 / -1;
      height: 6px;
    }
  `,
})
export class PlatformPositionPanel {
  readonly position = input.required<PlatformPositionView>();

  protected readonly metricLabels = PLATFORM_METRIC_LABELS;
  protected readonly kindLabels = PLATFORM_CYCLE_KIND_LABELS;
  protected readonly cycle = computed(() => formatCycle(this.position().periodStart, this.position().periodEnd));
  protected readonly proration = computed(() => prorationLabel(this.position().cycleDays, this.position().referenceCycleDays));

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected pct(value: number): string {
    return PERCENT.format(value);
  }

  protected min(a: number, b: number): number {
    return Math.min(a, b);
  }

  protected range(start: string, end: string): string {
    return formatCycle(start, end);
  }
}
