import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow } from '../components/chart.models';
import { FunnelChart, FunnelStage } from '../components/funnel-chart';
import { KpiCard } from '../components/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, num, percent, ratioDetail } from '../data/analytics.format';
import { GROUP_BY_LABELS, groupLabel } from '../data/analytics.labels';
import { Ratio } from '../data/analytics.models';
import { urlFilter } from '../data/url-filter';
import { peopleGroupBy } from './people-scope';

const GROUPS = ['ORIGIN', 'CREDITOR', 'PORTFOLIO', 'OPERATOR', 'TEAM', 'CHANNEL', 'MONTH'];

/**
 * Conversão (F18): funil contato efetivo → proposta → acordo → 1ª parcela paga e a safra dos
 * acordos formalizados no período — valor acordado separado do efetivamente pago, quebras por
 * janela (30/60/90 dias, só acordos maduros) e renegociações.
 */
@Component({
  selector: 'app-conversion-panel',
  imports: [Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, FunnelChart, KpiCard, PanelFrame],
  template: `
    <app-panel-frame title="Conversão" subtitle="Do contato efetivo ao pagamento da 1ª parcela; acordo não pago não é recuperação."
                     report="conversion" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()"
                             [options]="{ period: true, creditor: true, portfolio: true, people: true, channel: true, groupBy: groups() }"
                             (changed)="state.update($event)" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a conversão" message="Verifique os filtros e tente novamente.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let q = r.quality;
        <section class="pn__kpis" aria-label="Indicadores de conversão" data-tour="analytics-kpis">
          <app-kpi-card label="Acordos formalizados" [detail]="percent(r.funnel.contactToAgreement) + ' dos contatos efetivos (' + ratioDetail(r.funnel.contactToAgreement) + ')'">
            {{ count(q.agreements) }}
          </app-kpi-card>
          <app-kpi-card label="Valor acordado" [detail]="'Na safra do período'">
            <app-amount [value]="q.agreedAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Valor pago" [detail]="percent(q.paidShare) + ' do acordado (líquido de estornos)'">
            <app-amount [value]="q.paidAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="1ª parcela paga" [detail]="ratioDetail(r.funnel.agreementToFirstInstallment) + ' acordos'">
            {{ percent(r.funnel.agreementToFirstInstallment) }}
          </app-kpi-card>
          <app-kpi-card label="Parcelas pagas no período" [detail]="money(q.installmentsPaidAmount)">
            {{ count(q.installmentsPaid) }}
          </app-kpi-card>
          <app-kpi-card label="Renegociações" [detail]="percent(q.renegotiationRate) + ' dos acordos (' + ratioDetail(q.renegotiationRate) + ')'">
            {{ count(q.renegotiations) }}
          </app-kpi-card>
        </section>
        <div class="pn__grid">
          <section class="app-card" data-tour="analytics-funnel">
            <app-funnel-chart title="Funil do período" [summary]="funnelSummary()" [stages]="stages()" />
            <p class="pn__hint">Funil de fluxo: cada etapa conta os eventos do período (contato, proposta, acordo formalizado e 1ª parcela da safra).</p>
          </section>
          <section class="app-card" aria-labelledby="cv-breaks">
            <h2 class="app-card__title" id="cv-breaks">Quebra precoce por safra</h2>
            <div class="pn__table-wrap">
              <table class="pn__table">
                <thead><tr><th scope="col">Janela</th><th scope="col" class="num">Acordos maduros</th><th scope="col" class="num">Quebrados</th><th scope="col" class="num">Taxa</th></tr></thead>
                <tbody>
                  @for (b of q.breaks; track b.windowDays) {
                    <tr>
                      <th scope="row">Até {{ b.windowDays }} dias</th>
                      <td class="num app-numeric">{{ count(b.matured) }}</td>
                      <td class="num app-numeric">{{ count(b.broken) }}</td>
                      <td class="num">{{ percent(b.rate) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="pn__hint">Maduro = formalizado há pelo menos a janela; acordos mais recentes ainda não entram na base.</p>
          </section>
        </div>
        <section class="app-card">
          <app-bar-chart [title]="'Acordado × pago por ' + groupName().toLowerCase()" [categoryHeader]="groupName()" [summary]="rowsSummary()"
                         [series]="[{ name: 'Valor acordado', color: '--app-chart-1' }, { name: 'Valor pago', color: '--app-chart-2' }]"
                         [rows]="rows()" noteHeader="Pago / acordado" />
        </section>
        <section class="app-card" aria-labelledby="cv-table">
          <h2 class="app-card__title" id="cv-table">Detalhe por {{ groupName().toLowerCase() }}</h2>
          <div class="pn__table-wrap">
            <table class="pn__table">
              <thead>
                <tr><th scope="col">{{ groupName() }}</th><th scope="col" class="num">Contatos efetivos</th><th scope="col" class="num">Propostas</th><th scope="col" class="num">Acordos</th><th scope="col" class="num">Contato → acordo</th><th scope="col" class="num">1ª parcela</th><th scope="col" class="num">Acordado</th><th scope="col" class="num">Pago</th></tr>
              </thead>
              <tbody>
                @for (row of r.rows; track row.key) {
                  <tr>
                    <th scope="row">{{ label(row.key, row.label) }}</th>
                    <td class="num app-numeric">{{ row.effectiveContacts === null ? '—' : count(row.effectiveContacts) }}</td>
                    <td class="num app-numeric">{{ row.proposals === null ? '—' : count(row.proposals) }}</td>
                    <td class="num app-numeric">{{ count(row.agreements) }}</td>
                    <td class="num">{{ percent(row.contactToAgreement) }}</td>
                    <td class="num">{{ percent(row.firstInstallmentRate) }}<span class="sub">{{ ratioDetail(row.firstInstallmentRate) }}</span></td>
                    <td class="num"><app-amount [value]="row.agreedAmount" /></td>
                    <td class="num"><app-amount [value]="row.paidAmount" /></td>
                  </tr>
                } @empty {
                  <tr><td colspan="8">Nenhum acordo, proposta ou contato efetivo no período.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    </app-panel-frame>
  `,
  styleUrl: './panel.scss',
})
export class ConversionPanel {
  private readonly api = inject(AnalyticsApi);
  private readonly auth = inject(AuthService);
  protected readonly groups = computed(() => peopleGroupBy(GROUPS, this.auth));
  protected readonly state = urlFilter(GROUPS, 'ORIGIN');
  protected readonly count = count;
  protected readonly percent = percent;
  protected readonly ratioDetail = ratioDetail;
  protected readonly money = formatAmount;

  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.conversion(params) });
  protected readonly report = valueOr(this.data, undefined);
  protected readonly groupBy = computed(() => this.state.filter().groupBy ?? 'ORIGIN');
  protected readonly groupName = computed(() => GROUP_BY_LABELS[this.groupBy()] ?? 'Grupo');

  protected readonly stages = computed<FunnelStage[]>(() => {
    const f = this.report()?.funnel;
    if (!f) {
      return [];
    }
    const conv = (r: Ratio) => `${percent(r)} · ${ratioDetail(r)}`;
    return [
      { label: 'Contatos efetivos', value: f.effectiveContacts, display: count(f.effectiveContacts) },
      { label: 'Propostas', value: f.proposals, display: count(f.proposals), conversion: conv(f.contactToProposal) },
      { label: 'Acordos', value: f.agreements, display: count(f.agreements), conversion: conv(f.proposalToAgreement) },
      { label: '1ª parcela paga', value: f.firstInstallmentsPaid, display: count(f.firstInstallmentsPaid), conversion: conv(f.agreementToFirstInstallment) },
    ];
  });
  protected readonly funnelSummary = computed(() => {
    const f = this.report()?.funnel;
    return f
      ? `${count(f.effectiveContacts)} contatos efetivos, ${count(f.proposals)} propostas, ${count(f.agreements)} acordos e ${count(f.firstInstallmentsPaid)} com a 1ª parcela paga.`
      : '';
  });
  protected readonly rows = computed<ChartRow[]>(() =>
    (this.report()?.rows ?? []).slice(0, 12).map((r) => ({
      key: r.key,
      label: this.label(r.key, r.label),
      values: [num(r.agreedAmount), Math.max(0, num(r.paidAmount))],
      display: [formatAmount(r.agreedAmount), formatAmount(r.paidAmount)],
      note: `${percent(r.paidShare)} pago`,
    })),
  );
  protected readonly rowsSummary = computed(() => {
    const q = this.report()?.quality;
    return q ? `Valor acordado ${formatAmount(q.agreedAmount)} e efetivamente pago ${formatAmount(q.paidAmount)} (${percent(q.paidShare)}) na safra do período.` : '';
  });

  protected label(key: string, serverLabel: string): string {
    return groupLabel(this.groupBy(), key, serverLabel);
  }
}
