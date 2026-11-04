import { Component, computed, input } from '@angular/core';
import { InfoHint } from '../../../../shared/components/info-hint/info-hint';
import { KpiCard } from '../../../analytics/components/kpi-card';
import { TOKEN_STATE_LABELS, tokenStateTone } from '../../../public-api/data/public-api.labels';
import { TokenState } from '../../../public-api/data/public-api.models';
import { TrafficView } from '../../data/platform-billing.models';
import { REJECTION_REASON_LABELS } from '../platform-billing.labels';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Tráfego da API no ciclo, separando o cobrável do não cobrável: chamadas autenticadas (cobráveis e não cobráveis) e
 * recusadas na autenticação (tentativas de tráfego de tokens pausados, revogados, expirados, de assessoria suspensa…),
 * por credor, por token e por motivo da recusa. Mostra o tráfego também quando não há valor (assessoria suspensa ou sem
 * política).
 */
@Component({
  selector: 'app-platform-traffic-panel',
  imports: [InfoHint, KpiCard],
  template: `
    @let t = traffic().totals;
    <div class="ptp-kpis" data-tour="traffic">
      <app-kpi-card label="Chamadas autenticadas" info="platform.traffic.requests" [detail]="n(t.mbIn) + ' MB recebidos · ' + n(t.mbOut) + ' MB enviados'">{{ n(t.requests) }}</app-kpi-card>
      <app-kpi-card label="Cobráveis" info="platform.billing.billable-requests" [detail]="n(t.billableMbIn) + '/' + n(t.billableMbOut) + ' MB cobráveis'">{{ n(t.billableRequests) }}</app-kpi-card>
      <app-kpi-card label="Não cobráveis" info="platform.traffic.non-billable" detail="Excesso (429), erros do credor (4xx) e falhas (5xx)">{{ n(t.nonBillableRequests) }}</app-kpi-card>
      <app-kpi-card label="Recusadas na autenticação" info="platform.traffic.rejected" data-tour="kpi-rejected" detail="Tentativas de tráfego, nunca cobradas">{{ n(t.rejectedRequests) }}</app-kpi-card>
    </div>

    @if (traffic().rejections.length) {
      <h3 class="app-section-title ptp-title">
        Recusas por motivo
        <app-info-hint indicator="platform.traffic.rejected" label="Chamadas recusadas na autenticação" />
      </h3>
      <ul class="ptp-reasons">
        @for (r of traffic().rejections; track r.reason) {
          <li><span>{{ reasonLabels[r.reason] }}</span><span class="app-numeric">{{ n(r.attempts) }}</span></li>
        }
      </ul>
    }

    @if (tokens().length) {
      <h3 class="app-section-title ptp-title">
        Tráfego por credor e token
        <app-info-hint indicator="platform.traffic.by-token" label="Tráfego por credor e token" />
      </h3>
      <div class="table-responsive">
        <table class="table table-sm mb-0">
          <caption class="visually-hidden">Tráfego da API por credor e token no ciclo</caption>
          <thead>
            <tr>
              <th scope="col">Credor / token</th>
              <th scope="col">Situação</th>
              <th scope="col" class="text-end">Autenticadas</th>
              <th scope="col" class="text-end">Cobráveis</th>
              <th scope="col" class="text-end">Não cobráveis</th>
              <th scope="col" class="text-end">Recusadas</th>
              <th scope="col" class="text-end">MB rec./env.</th>
              <th scope="col" class="text-end">Registros</th>
            </tr>
          </thead>
          <tbody>
            @for (c of creditors(); track c.key) {
              <tr class="ptp-creditor">
                <th scope="row" colspan="2">{{ c.label || 'Credor ' + short(c.key) }}</th>
                <td class="text-end app-numeric">{{ n(c.totals.requests) }}</td>
                <td class="text-end app-numeric">{{ n(c.totals.billableRequests) }}</td>
                <td class="text-end app-numeric">{{ n(c.totals.nonBillableRequests) }}</td>
                <td class="text-end app-numeric">{{ n(c.totals.rejectedRequests) }}</td>
                <td class="text-end app-numeric">{{ n(c.totals.mbIn) }}/{{ n(c.totals.mbOut) }}</td>
                <td class="text-end app-numeric">{{ n(c.totals.records) }}</td>
              </tr>
              @for (k of tokensOf(c.key); track k.key) {
                <tr>
                  <td class="ptp-token">{{ k.label || short(k.key) }}</td>
                  <td>
                    @if (k.tokenState; as s) {
                      <span class="badge rounded-pill border fw-normal" [class]="stateClass(s)">{{ stateLabels[s] }}</span>
                    }
                  </td>
                  <td class="text-end app-numeric">{{ n(k.totals.requests) }}</td>
                  <td class="text-end app-numeric">{{ n(k.totals.billableRequests) }}</td>
                  <td class="text-end app-numeric">{{ n(k.totals.nonBillableRequests) }}</td>
                  <td class="text-end app-numeric" [class.text-warning-emphasis]="k.totals.rejectedRequests > 0">{{ n(k.totals.rejectedRequests) }}</td>
                  <td class="text-end app-numeric">{{ n(k.totals.mbIn) }}/{{ n(k.totals.mbOut) }}</td>
                  <td class="text-end app-numeric">{{ n(k.totals.records) }}</td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
    } @else {
      <p class="app-muted ptp-small mb-0">Nenhum tráfego da API no ciclo.</p>
    }
  `,
  styles: `
    .ptp-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 180px), 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }
    .ptp-title {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .ptp-reasons {
      list-style: none;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));
      gap: 4px 16px;
      font-size: var(--app-text-sm);
    }
    .ptp-reasons li {
      display: flex;
      justify-content: space-between;
      gap: 8px;
    }
    .ptp-creditor th {
      font-weight: 500;
    }
    .ptp-token {
      padding-left: 1.5rem;
    }
    .ptp-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class PlatformTrafficPanel {
  readonly traffic = input.required<TrafficView>();

  protected readonly stateLabels = TOKEN_STATE_LABELS;
  protected readonly reasonLabels = REJECTION_REASON_LABELS;
  protected readonly creditors = computed(() => this.traffic().rows.filter((r) => r.kind === 'CREDITOR'));
  protected readonly tokens = computed(() => this.traffic().rows.filter((r) => r.kind === 'TOKEN'));

  protected tokensOf(creditorId: string) {
    return this.tokens().filter((t) => t.creditorId === creditorId);
  }

  protected stateClass(state: TokenState): string {
    const tone = tokenStateTone(state);
    return `bg-${tone}-subtle text-${tone}-emphasis border-${tone}-subtle`;
  }

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected short(id: string): string {
    return id.slice(0, 8);
  }
}
