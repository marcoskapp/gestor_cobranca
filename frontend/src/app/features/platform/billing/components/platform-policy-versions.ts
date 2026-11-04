import { Component, input } from '@angular/core';
import { Amount } from '../../../../shared/components/amount/amount';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { formatCycle } from '../../../public-api/data/public-api.labels';
import { PolicyVersionView, PolicyView } from '../../data/platform-billing.models';
import { tiersSummary } from '../platform-billing.labels';

const NUMBER = new Intl.NumberFormat('pt-BR');
const PRICE = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 6 });

/** Vigência e versões da política de cobrança da plataforma (a que vale no ciclo em andamento fica marcada). */
@Component({
  selector: 'app-platform-policy-versions',
  imports: [Amount, AppDatePipe],
  template: `
    @let p = policy();
    @if (!p.policyId) {
      <p class="app-muted mb-0">Sem política da plataforma: nada é cobrado da assessoria; o consumo aparece só como informação.</p>
    } @else {
      <p class="app-muted ppv-small">
        Vigência desde {{ date(p.startDate!) }}{{ p.endDate ? ' até ' + date(p.endDate) : ', sem data de término' }}. A primeira
        versão vale já no ciclo em andamento; as seguintes, a partir do próximo ciclo.
      </p>
      <div class="table-responsive">
        <table class="table table-sm mb-0">
          <caption class="visually-hidden">Versões da política de cobrança da plataforma</caption>
          <thead>
            <tr>
              <th scope="col">Versão</th>
              <th scope="col">Registrada em</th>
              <th scope="col" class="text-end">Fechamento</th>
              <th scope="col" class="text-end">Mensalidade</th>
              <th scope="col">Chamadas (franquia · preço)</th>
              <th scope="col">MB rec. · MB env. · registros</th>
            </tr>
          </thead>
          <tbody>
            @for (v of p.versions; track v.id) {
              <tr>
                <td>
                  v{{ v.version }}
                  @if (v.appliesToCurrentCycle) { <span class="app-status app-status--info">ciclo atual</span> }
                  @if (v.reason) { <div class="app-muted ppv-small">{{ v.reason }}</div> }
                </td>
                <td>{{ v.registeredAt | appDate }}</td>
                <td class="text-end">dia {{ v.closingDay }}</td>
                <td class="text-end"><app-amount [value]="v.monthlyFee" /></td>
                <td class="ppv-small">{{ n(v.includedRequests) }} · {{ requestPrice(v) }}</td>
                <td class="ppv-small">
                  {{ n(v.includedMbIn) }} a {{ price(v.pricePerMbIn) }} · {{ n(v.includedMbOut) }} a {{ price(v.pricePerMbOut) }} ·
                  {{ n(v.includedRecords) }} a {{ price(v.pricePerRecord) }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: `
    .ppv-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class PlatformPolicyVersions {
  readonly policy = input.required<PolicyView>();

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected price(value: number): string {
    return PRICE.format(value);
  }

  protected date(value: string): string {
    return formatCycle(value, value).split(' a ')[0];
  }

  protected requestPrice(v: PolicyVersionView): string {
    return v.requestTiers.length ? 'faixas: ' + tiersSummary(v.requestTiers) : `${this.price(v.pricePerRequest ?? 0)} por chamada`;
  }
}
