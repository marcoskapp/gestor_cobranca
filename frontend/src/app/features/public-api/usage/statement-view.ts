import { Component, input } from '@angular/core';
import { Amount } from '../../../shared/components/amount/amount';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { METRIC_LABELS } from '../data/public-api.labels';
import { StatementView } from '../data/public-api.models';

const NUMBER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 6 });

/** Linhas do extrato (consumo, franquia, cobrável, valor), total e hash do consumo usado no cálculo. */
@Component({
  selector: 'app-statement-view',
  imports: [Amount, AppDatePipe],
  template: `
    @let s = statement();
    <p class="sv-small app-muted">
      {{ creditorName() }} · {{ s.periodStart.slice(5, 7) }}/{{ s.periodStart.slice(0, 4) }} ·
      {{ s.pricePlanCode ? 'Tabela ' + s.pricePlanCode + ' v' + s.pricePlanVersion : 'Sem tabela vigente' }}
      @if (s.closedAt) {
        · fechado em {{ s.closedAt | appDate }} (revisão {{ s.revision }})
      } @else {
        · prévia
      }
      @if (s.reason) {
        · motivo: {{ s.reason }}
      }
    </p>
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
            <tr><td colspan="5" class="app-muted">Sem tabela de preços vigente no mês: só o consumo é registrado.</td></tr>
          }
          <tr><th scope="row" colspan="4">Total</th><td class="text-end"><strong><app-amount [value]="s.totalAmount" /></strong></td></tr>
        </tbody>
      </table>
    </div>
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
}
