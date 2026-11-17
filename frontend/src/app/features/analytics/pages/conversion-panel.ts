import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { ShowMoreButton } from '../../../shared/components/show-more/show-more';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { showMore } from '../../../shared/util/show-more';
import { AnalyticsFilters } from '../components/analytics-filters';
import { BarChart } from '../components/bar-chart';
import { ChartRow, ChartSeries } from '../components/chart.models';
import { FocusChip } from '../components/focus-chip';
import { FunnelChart, FunnelStage } from '../components/funnel-chart';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PanelFrame } from '../components/panel-frame';
import { AnalyticsApi } from '../data/analytics.api';
import { count, num, percent, ratioDetail } from '../data/analytics.format';
import { GROUP_BY_LABELS, GROUP_NOUNS, groupLabel } from '../data/analytics.labels';
import { Ratio } from '../data/analytics.models';
import { focusFilter, focusable, focusableKey, unfocusFilter, urlFilter } from '../data/url-filter';
import { peopleGroupBy } from './people-scope';

const GROUPS = ['ORIGIN', 'CREDITOR', 'PORTFOLIO', 'OPERATOR', 'TEAM', 'CHANNEL', 'MONTH'];

/**
 * Conversão (F18): funil contato efetivo → proposta → acordo → 1ª parcela paga e a safra dos
 * acordos formalizados no período — valor acordado separado do efetivamente pago, quebras por
 * janela (30/60/90 dias, só acordos maduros) e renegociações.
 */
@Component({
  selector: 'app-conversion-panel',
  imports: [Amount, EmptyState, Skeleton, AnalyticsFilters, BarChart, FocusChip, FunnelChart, InfoHint, KpiCard, PanelFrame, ShowMoreButton],
  template: `
    <app-panel-frame title="Conversão" subtitle="Do contato efetivo ao pagamento da 1ª parcela; acordo não pago não é recuperação."
                     report="conversion" [filter]="state.filter()" [meta]="report()?.meta" [loading]="data.isLoading()" (refreshed)="data.reload()">
      <app-analytics-filters panel-filters [filter]="state.filter()"
                             [options]="{ period: true, creditor: true, portfolio: true, people: true, channel: true, groupBy: groups() }"
                             (changed)="state.update($event)" />
      <app-focus-chip [label]="state.focused()" (removed)="state.update(unfocus())" />
      @if (data.error()) {
        <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a conversão" message="Verifique os filtros e tente novamente.">
          <button class="btn btn-primary" type="button" (click)="data.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!report()) {
        <app-skeleton variant="card" [rows]="4" />
      } @else {
        @let r = report()!;
        @let q = r.quality;
        <section class="app-kpi-grid" aria-label="Indicadores de conversão" data-tour="analytics-kpis">
          <app-kpi-card label="Acordos formalizados" info="analytics.conversion.agreements" data-tour="kpi-agreements" [detail]="percent(r.funnel.contactToAgreement) + ' dos contatos efetivos (' + ratioDetail(r.funnel.contactToAgreement) + ')'">
            {{ count(q.agreements) }}
          </app-kpi-card>
          <app-kpi-card label="Valor acordado" info="analytics.conversion.agreed" [detail]="'Na safra do período'">
            <app-amount [value]="q.agreedAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="Valor pago" info="analytics.conversion.paid" data-tour="kpi-paid" [detail]="percent(q.paidShare) + ' do acordado (líquido de estornos)'">
            <app-amount [value]="q.paidAmount" [compact]="true" />
          </app-kpi-card>
          <app-kpi-card label="1ª parcela paga" info="analytics.conversion.first-installment" data-tour="kpi-first" [detail]="ratioDetail(r.funnel.agreementToFirstInstallment) + ' acordos'">
            {{ percent(r.funnel.agreementToFirstInstallment) }}
          </app-kpi-card>
          <app-kpi-card label="Parcelas pagas no período" info="analytics.conversion.installments-paid" [detail]="money(q.installmentsPaidAmount)">
            {{ count(q.installmentsPaid) }}
          </app-kpi-card>
          <app-kpi-card label="Renegociações" info="analytics.conversion.renegotiations" [detail]="percent(q.renegotiationRate) + ' dos acordos (' + ratioDetail(q.renegotiationRate) + ')'">
            {{ count(q.renegotiations) }}
          </app-kpi-card>
        </section>
        <div class="pn__grid app-columns">
          <section class="app-card" data-tour="analytics-funnel">
            <app-funnel-chart title="Funil do período" info="analytics.conversion.funnel" [summary]="funnelSummary()" [stages]="stages()" />
            <p class="pn__hint">Funil de fluxo: cada etapa conta os eventos do período (contato, proposta, acordo formalizado e 1ª parcela da safra).</p>
          </section>
          <section class="app-card" aria-labelledby="cv-breaks" data-tour="analytics-breaks">
            <h2 class="app-card__title" id="cv-breaks">Quebra precoce por safra <app-info-hint indicator="analytics.conversion.early-break" label="Quebra precoce por safra" /></h2>
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
          <app-bar-chart [title]="'Acordado × pago por ' + groupName().toLowerCase()" info="analytics.conversion.agreed-vs-paid" [categoryHeader]="groupName()" [summary]="rowsSummary()"
                         [series]="series" [rows]="rows()" noteHeader="Pago / acordado"
                         [limit]="timeGroup() ? null : 15" [noun]="groupNoun()" shareOf="do valor acordado"
                         tableKey="grupos" [searchInUrl]="groupBy() !== 'OPERATOR'" [selectable]="canFocus()" [selectableRow]="focusableRow" [selectHint]="'Filtrar o painel por ' + groupNoun().this + ' ' + groupNoun().one + '.'"
                         (selected)="focusOn($event)" />
        </section>
        <section class="app-card" aria-labelledby="cv-table">
          <h2 class="app-card__title" id="cv-table">Detalhe por {{ groupName().toLowerCase() }}</h2>
          <div class="pn__table-wrap">
            <table class="pn__table" #tableEl>
              <thead>
                <tr><th scope="col">{{ groupName() }}</th><th scope="col" class="num">Contatos efetivos <app-info-hint indicator="analytics.conversion.effective-contacts" label="Contatos efetivos" /></th><th scope="col" class="num">Propostas <app-info-hint indicator="analytics.conversion.proposals" label="Propostas" /></th><th scope="col" class="num">Acordos <app-info-hint indicator="analytics.conversion.agreements" label="Acordos" /></th><th scope="col" class="num">Contato → acordo <app-info-hint indicator="analytics.conversion.contact-to-agreement" label="Contato → acordo" /></th><th scope="col" class="num">1ª parcela <app-info-hint indicator="analytics.conversion.first-installment" label="1ª parcela" /></th><th scope="col" class="num">Acordado <app-info-hint indicator="analytics.conversion.agreed" label="Acordado" /></th><th scope="col" class="num">Pago <app-info-hint indicator="analytics.conversion.paid" label="Pago" /></th></tr>
              </thead>
              <tbody>
                @for (row of table.visible(); track row.key) {
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
          <app-show-more [list]="table" [table]="tableEl" [singular]="groupNoun().one" [plural]="groupNoun().many" />
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
  /** Tabela por grupo em passos de 25 (na ordem do servidor); filtro ou agrupamento novos voltam a 25. */
  protected readonly table = showMore(() => this.report()?.rows ?? [], { resetOn: () => this.state.filter() });

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
  protected readonly series: ChartSeries[] = [
    { name: 'Valor acordado', color: '--app-chart-1', format: (v) => formatAmount(v) },
    { name: 'Valor pago', color: '--app-chart-2', format: (v) => formatAmount(v) },
  ];
  /** Agrupamento por tempo fica em ordem cronológica e sem corte (não é ranking). */
  protected readonly timeGroup = computed(() => ['DAY', 'WEEK', 'MONTH'].includes(this.groupBy()));
  protected readonly canFocus = computed(() => focusable(this.groupBy()));
  protected readonly groupNoun = computed(() => GROUP_NOUNS[this.groupBy()] ?? { one: 'grupo', this: 'este', many: 'grupos' });

  /** Clique numa barra: filtra o painel pelo item (entra no histórico; o chip e o "voltar" desfazem). */
  protected focusOn(row: ChartRow): void {
    const next = focusFilter(this.state.filter(), this.groupBy(), row.key);
    if (next) {
      this.state.focus(next, `${this.groupName()}: ${row.label}`, this.groupBy());
    }
  }

  protected readonly focusableRow = (r: ChartRow) => focusableKey(r.key);

  protected unfocus() {
    return unfocusFilter(this.state.filter(), this.groupBy());
  }

  protected readonly rows = computed<ChartRow[]>(() =>
    [...(this.report()?.rows ?? [])]
      // ranking pelo valor acordado (tempo: na ordem do período)
      .sort((a, b) => (this.timeGroup() ? 0 : num(b.agreedAmount) - num(a.agreedAmount)))
      .map((r) => ({
      key: r.key,
      label: this.label(r.key, r.label),
      values: [num(r.agreedAmount), num(r.paidAmount)],
      display: [formatAmount(r.agreedAmount), formatAmount(r.paidAmount)],
      note: percent(r.paidShare),
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
