import { Component, computed, input } from '@angular/core';
import { Amount } from '../../../../shared/components/amount/amount';
import { InfoHint } from '../../../../shared/components/info-hint/info-hint';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { formatCycle, prorationLabel } from '../../../public-api/data/public-api.labels';
import { BreakdownView, ChargeLineView, PlatformStatementView } from '../../data/platform-billing.models';
import { PLATFORM_CYCLE_KIND_LABELS, PLATFORM_METRIC_LABELS, tierRange } from '../platform-billing.labels';

const NUMBER = new Intl.NumberFormat('pt-BR');
const PRICE = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 6 });

/** Linhas do extrato (ou da posição): consumo, franquia, cobrável, preço, valor e as sublinhas das faixas de volume. */
@Component({
  selector: 'app-platform-charge-lines',
  imports: [Amount, InfoHint],
  template: `
    <div class="table-responsive">
      <table class="table table-sm">
        <caption class="visually-hidden">Linhas do extrato da plataforma</caption>
        <thead>
          <tr>
            <th scope="col">Item</th>
            <th scope="col" class="text-end">Consumo</th>
            <th scope="col" class="text-end">Franquia</th>
            <th scope="col" class="text-end">Cobrável</th>
            <th scope="col" class="text-end">Preço</th>
            <th scope="col" class="text-end">Valor <app-info-hint indicator="platform.statement.total" label="Valor do extrato da plataforma" /></th>
          </tr>
        </thead>
        <tbody>
          @for (l of lines(); track l.metric) {
            <tr>
              <td>{{ labels[l.metric] }}</td>
              <td class="text-end app-numeric">{{ n(l.quantity) }}</td>
              <td class="text-end app-numeric">{{ n(l.included) }}</td>
              <td class="text-end app-numeric">{{ n(l.billable) }}</td>
              <td class="text-end app-numeric">{{ l.unitPrice === null ? 'por faixa' : price(l.unitPrice) }}</td>
              <td class="text-end"><app-amount [value]="l.amount" /></td>
            </tr>
            @for (t of l.tiers; track t.from) {
              <tr class="pcl-tier">
                <td>Faixa {{ range(t) }}</td>
                <td></td>
                <td></td>
                <td class="text-end app-numeric">{{ n(t.units) }}</td>
                <td class="text-end app-numeric">{{ price(t.unitPrice) }}</td>
                <td class="text-end"><app-amount [value]="t.amount" /></td>
              </tr>
            }
          }
          <tr><th scope="row" colspan="5">Total</th><td class="text-end"><strong><app-amount [value]="total()" /></strong></td></tr>
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .pcl-tier td {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .pcl-tier td:first-child {
      padding-left: 1.5rem;
    }
  `,
})
export class PlatformChargeLines {
  readonly lines = input.required<readonly ChargeLineView[]>();
  readonly total = input.required<number>();
  protected readonly labels = PLATFORM_METRIC_LABELS;

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected price(value: number): string {
    return PRICE.format(value);
  }

  protected range(t: { from: number; to: number | null }): string {
    return tierRange(t);
  }
}

/** Detalhamento do consumo cobrável: por credor, por token (com o credor dono) e por operação. */
@Component({
  selector: 'app-platform-breakdown-tables',
  imports: [InfoHint],
  template: `
    @if (creditors().length) {
      <h3 class="app-section-title pbt-title">
        Por credor
        <app-info-hint indicator="platform.billing.by-creditor" label="Consumo por credor" />
      </h3>
      <div class="table-responsive">
        <table class="table table-sm">
          <caption class="visually-hidden">Consumo cobrável por credor</caption>
          <thead><tr><th scope="col">Credor</th><th scope="col" class="text-end">Chamadas</th><th scope="col" class="text-end">Cobráveis</th><th scope="col" class="text-end">MB rec.</th><th scope="col" class="text-end">MB env.</th><th scope="col" class="text-end">Registros</th></tr></thead>
          <tbody>
            @for (b of creditors(); track b.key) {
              <tr>
                <td>{{ b.label || 'Credor ' + short(b.key) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['requests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableRequests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbIn']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbOut']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['records']) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    @if (tokens().length) {
      <h3 class="app-section-title">Por token (inclusive revogados ou expirados)</h3>
      <div class="table-responsive">
        <table class="table table-sm">
          <caption class="visually-hidden">Consumo cobrável por token</caption>
          <thead><tr><th scope="col">Token</th><th scope="col">Credor</th><th scope="col" class="text-end">Chamadas</th><th scope="col" class="text-end">Cobráveis</th><th scope="col" class="text-end">MB rec.</th><th scope="col" class="text-end">MB env.</th><th scope="col" class="text-end">Registros</th></tr></thead>
          <tbody>
            @for (b of tokens(); track b.key) {
              <tr>
                <td>{{ b.label || short(b.key) }}</td>
                <td>{{ creditorName(b.creditorId) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['requests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableRequests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbIn']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbOut']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['records']) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    @if (routes().length) {
      <h3 class="app-section-title">Por operação</h3>
      <div class="table-responsive">
        <table class="table table-sm mb-0">
          <caption class="visually-hidden">Consumo por operação</caption>
          <thead><tr><th scope="col">Operação</th><th scope="col" class="text-end">Chamadas</th><th scope="col" class="text-end">Cobráveis</th><th scope="col" class="text-end">Registros</th></tr></thead>
          <tbody>
            @for (b of routes(); track b.key) {
              <tr>
                <td class="app-mono">{{ b.key }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['requests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableRequests']) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['records']) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: `
    .pbt-title {
      display: flex;
      align-items: center;
      gap: 4px;
    }
  `,
})
export class PlatformBreakdownTables {
  readonly breakdown = input.required<readonly BreakdownView[]>();

  protected readonly creditors = computed(() => this.breakdown().filter((b) => b.kind === 'CREDITOR'));
  protected readonly tokens = computed(() => this.breakdown().filter((b) => b.kind === 'TOKEN'));
  protected readonly routes = computed(() => this.breakdown().filter((b) => b.kind === 'ROUTE'));
  private readonly names = computed(() => new Map(this.creditors().map((c) => [c.key, c.label])));

  protected n(value: number | undefined): string {
    return NUMBER.format(value ?? 0);
  }

  protected short(id: string): string {
    return id.slice(0, 8);
  }

  protected creditorName(id: string | null): string {
    return id ? (this.names().get(id) ?? this.short(id)) : '';
  }
}

/** Extrato da plataforma (fechado ou prévia): cabeçalho do ciclo, linhas, detalhamento e a marca do consumo. */
@Component({
  selector: 'app-platform-statement-panel',
  imports: [AppDatePipe, PlatformBreakdownTables, PlatformChargeLines],
  template: `
    @let s = statement();
    <p class="app-muted psp-small">
      Ciclo {{ cycle() }} ({{ s.cycleDays }} dias, {{ kindLabels[s.cycleKind] }}, fecha no dia {{ s.closingDay }}) · política v{{ s.policyVersion }} ·
      {{ n(s.creditors) }} credores e {{ n(s.tokens) }} tokens com consumo
      @if (s.closedAt) {
        · fechado em {{ s.closedAt | appDate: 'datetime' }} (revisão {{ s.revision }}{{ s.closedBy ? ', pela equipe da plataforma' : ', automático' }})
      } @else {
        · prévia{{ s.ended ? ' (ciclo encerrado)' : ' (ciclo em curso)' }}
      }
      @if (s.reason) { · motivo: {{ s.reason }} }
    </p>
    @if (proration(); as p) {
      <div class="alert alert-info psp-small py-2" role="note">
        Ciclo proporcional: mensalidade, franquias e limites das faixas proporcionais a {{ p }}. Mensalidade arredondada a
        centavos (meio para o par); franquias e limites arredondados para cima.
      </div>
    }
    <app-platform-charge-lines [lines]="s.lines" [total]="s.totalAmount" />
    <app-platform-breakdown-tables [breakdown]="s.breakdown" />
    <p class="app-muted psp-small mt-2 mb-0">Só chamadas bem-sucedidas (2xx/3xx) de todos os tokens da assessoria. Marca do consumo: <span class="app-mono">{{ s.usageHash }}</span></p>
  `,
  styles: `
    .psp-small {
      font-size: var(--app-text-xs);
      overflow-wrap: anywhere;
    }
  `,
})
export class PlatformStatementPanel {
  readonly statement = input.required<PlatformStatementView>();
  protected readonly kindLabels = PLATFORM_CYCLE_KIND_LABELS;
  protected readonly cycle = computed(() => formatCycle(this.statement().periodStart, this.statement().periodEnd));
  protected readonly proration = computed(() => prorationLabel(this.statement().cycleDays, this.statement().referenceCycleDays));

  protected n(value: number): string {
    return NUMBER.format(value);
  }
}
