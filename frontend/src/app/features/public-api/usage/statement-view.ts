import { Component, input } from '@angular/core';
import { Amount } from '../../../shared/components/amount/amount';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { METRIC_LABELS, formatCycle, prorationLabel } from '../data/public-api.labels';
import { StatementView } from '../data/public-api.models';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Linhas do extrato de um ciclo (quantidade inteira, franquia, cobrável, valor), total, detalhamento por token e hash
 * do consumo usado no cálculo.
 */
@Component({
  selector: 'app-statement-view',
  imports: [Amount, AppDatePipe],
  template: `
    @let s = statement();
    <p class="sv-small app-muted">
      {{ creditorName() }} · ciclo {{ cycle(s) }} ({{ s.cycleDays }} dias, fecha no dia {{ s.usageClosingDay }}) ·
      {{ s.pricingVersion ? 'Contrato ' + s.contractNumber + ', condições v' + s.pricingVersion : 'Contrato sem condições de cobrança' }}
      @if (s.closedAt) {
        · fechado em {{ s.closedAt | appDate }} (revisão {{ s.revision }})
      } @else {
        · prévia{{ s.ended ? ' (ciclo encerrado)' : ' (ciclo em curso)' }}
      }
      @if (s.reason) {
        · motivo: {{ s.reason }}
      }
    </p>
    @if (proration(s); as p) {
      <div class="alert alert-info sv-small py-2" role="note">
        Ciclo de transição (mudança do dia de fechamento): mensalidade e franquias proporcionais a {{ p }}. Mensalidade
        arredondada a centavos (meio para o par); franquias arredondadas para cima.
      </div>
    }
    <div class="table-responsive">
      <table class="table table-sm">
        <caption class="visually-hidden">Linhas do extrato</caption>
        <thead>
          <tr><th scope="col">Item</th><th scope="col" class="text-end">Consumo</th><th scope="col" class="text-end">Franquia</th><th scope="col" class="text-end">Cobrável</th><th scope="col" class="text-end">Valor</th></tr>
        </thead>
        <tbody>
          @for (l of s.lines; track l.metric) {
            <tr>
              <td>{{ labels[l.metric] }}</td>
              <td class="text-end app-numeric">{{ n(l.quantity) }}</td>
              <td class="text-end app-numeric">{{ n(l.included) }}</td>
              <td class="text-end app-numeric">{{ n(l.billable) }}</td>
              <td class="text-end"><app-amount [value]="l.amount" /></td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="app-muted">O contrato não tem condições de cobrança das integrações vigentes no início do ciclo: só o consumo é registrado.</td></tr>
          }
          <tr><th scope="row" colspan="4">Total</th><td class="text-end"><strong><app-amount [value]="s.totalAmount" /></strong></td></tr>
        </tbody>
      </table>
    </div>
    @if (tokens(s).length) {
      <div class="table-responsive">
        <table class="table table-sm">
          <caption class="visually-hidden">Detalhamento por token</caption>
          <thead>
            <tr><th scope="col">Token</th><th scope="col" class="text-end">Chamadas</th><th scope="col" class="text-end">Cobráveis</th><th scope="col" class="text-end">MB recebidos</th><th scope="col" class="text-end">MB enviados</th><th scope="col" class="text-end">Registros</th></tr>
          </thead>
          <tbody>
            @for (b of tokens(s); track b.key) {
              <tr>
                <td>{{ b.label || b.key }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['requests'] ?? 0) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableRequests'] ?? 0) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbIn'] ?? 0) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['billableMbOut'] ?? 0) }}</td>
                <td class="text-end app-numeric">{{ n(b.totals['records'] ?? 0) }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
    <p class="app-muted sv-small">Cobram-se só chamadas bem-sucedidas (2xx/3xx). Dados em MB inteiros (1 MB = 1.048.576 bytes), arredondados para cima por token e direção no ciclo.</p>
    <p class="app-mono sv-small sv-hash">Hash do consumo: {{ s.usageHash }}</p>
  `,
  styles: `
    :host {
      display: block;
      margin-top: 12px;
    }
    .sv-small {
      font-size: var(--app-text-xs);
    }
    .sv-hash {
      word-break: break-all;
    }
  `,
})
export class StatementViewPanel {
  readonly statement = input.required<StatementView>();
  readonly creditorName = input('');
  protected readonly labels = METRIC_LABELS;

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected cycle(s: StatementView): string {
    return formatCycle(s.periodStart, s.periodEnd);
  }

  protected proration(s: StatementView): string | null {
    return prorationLabel(s.cycleDays, s.referenceCycleDays);
  }

  protected tokens(s: StatementView): StatementView['breakdown'] {
    return s.breakdown.filter((b) => b.kind === 'TOKEN');
  }
}
