import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { sameValue } from '../../../shared/data/same-value';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { isoDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { addDays, zonedNow } from '../../collection/data/collection.rules';
import { AGREEMENT_ORIGIN_LABELS } from '../../negotiation/data/agreement.labels';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { BarChart } from '../components/bar-chart';
import { AssistantReviewApi } from '../data/assistant.api';
import { AGREEMENT_GROUP_LABELS, GUARDRAIL_LABELS, HANDOFF_REASON_LABELS, PIX_DENIAL_LABELS } from '../data/assistant.labels';
import { Bar, bars, earlyBreakBars, earlyBreakBaseText, fractionText } from '../data/assistant.rules';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';

interface QualityFilter {
  from: string | null;
  to: string | null;
  creditorId: string | null;
  portfolioId: string | null;
}

/**
 * Qualidade do assistente por período (critérios de avanço de fase, `assistente-ia.md` §7–8):
 * transferências por motivo, guardrails, acordos por origem, quebra precoce assistente × humanos,
 * reclamações, custo por acordo e revisões. Gráficos com valores em texto (acessíveis).
 */
@Component({
  selector: 'app-quality-panel',
  imports: [ReactiveFormsModule, RouterLink, ...FORM_FIELD, NgbInputDatepicker, Icon, AppDatePipe, BarChart, EmptyState, EntityPicker, PageHeader, Skeleton, KpiCard],
  template: `
    <app-page-header title="Qualidade do assistente" subtitle="Resultado do assistente comparado aos humanos, para decidir carteira a carteira quando avançar de fase ou ampliar a alçada." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar qualidade">
      <app-field class="qp-date" label="De">
        <div class="input-group">
          <input appInput formControlName="from" ngbDatepicker #fromPicker="ngbDatepicker" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button class="btn btn-outline-secondary" type="button" (click)="fromPicker.toggle()" aria-label="Abrir calendário da data inicial">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <app-field class="qp-date" label="Até">
        <div class="input-group">
          <input appInput formControlName="to" ngbDatepicker #toPicker="ngbDatepicker" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button class="btn btn-outline-secondary" type="button" (click)="toPicker.toggle()" aria-label="Abrir calendário da data final">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      @if (filters.controls.creditor.value.length) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
        </div>
      }
    </form>

    @if (quality.isLoading() && !q()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (quality.status() === 'error') {
      <app-empty-state icon="graph-up" tone="error" title="Não foi possível carregar a qualidade." message="Confira o período (até 1 ano) e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="quality.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (q(); as v) {
      <p class="app-muted qp-small">{{ v.from | appDate: 'date' }} a {{ v.to | appDate: 'date' }} · {{ num(v.conversations) }} conversa(s), {{ num(v.turns) }} turno(s)</p>
      <section class="app-kpi-grid" aria-label="Indicadores do período" data-tour="quality-kpis">
        <app-kpi-card label="Transferência para humano" info="assistant.quality.handoff-rate" detail="das conversas atendidas">{{ fraction(v.handoffRate) }}</app-kpi-card>
        <app-kpi-card label="Reclamações" info="assistant.quality.complaints" detail="Procon, advogado, ouvidoria ou opt-out">{{ v.complaints }}</app-kpi-card>
        <app-kpi-card label="Custo por acordo" info="assistant.quality.cost-per-agreement" [detail]="'total ' + money(v.cost, v.costCurrency)">{{ money(v.costPerAgreement, v.costCurrency) }}</app-kpi-card>
        <app-kpi-card label="Revisões" info="assistant.quality.reviews">
          {{ v.avgScore !== null ? score(v.avgScore) + '/5' : '—' }}
          <span appKpiExtra class="app-kpi__detail">{{ v.reviewed }} avaliadas · {{ v.outOfPolicy }} fora da política · <a routerLink="/assistente/revisao">{{ v.pendingReviews }} pendentes</a></span>
        </app-kpi-card>
        <app-kpi-card label="Latência média" info="assistant.quality.latency" [detail]="num(v.inputTokens + v.outputTokens) + ' tokens'">{{ num(v.avgLatencyMs) }} ms</app-kpi-card>
      </section>
      <div class="qp-grid app-columns">
        <section class="app-card" data-tour="quality-handoffs">
          <app-bar-chart title="Transferências por motivo" info="assistant.quality.handoff-rate" [data]="handoffs()" empty="Nenhuma transferência no período." />
        </section>
        <section class="app-card" data-tour="quality-origin">
          <app-bar-chart title="Acordos por origem" info="assistant.quality.origins" [data]="origins()" empty="Nenhum acordo no período." />
        </section>
        <section class="app-card" data-tour="quality-break">
          <app-bar-chart [title]="'Quebra precoce (até ' + v.earlyBreakDays + ' dias): assistente × humanos'" info="assistant.quality.early-break" [data]="earlyBreak()" highlight="ASSISTANT" [format]="breakFormat" [empty]="'Nenhum acordo do período completou os ' + v.earlyBreakDays + ' dias.'" />
          @if (earlyBreakBase(); as base) {
            <p class="app-muted qp-small" data-testid="early-break-base">{{ base }}.</p>
          }
          <p class="app-muted qp-small">A taxa considera só os acordos formalizados há pelo menos {{ v.earlyBreakDays }} dias. Critério de avanço: a quebra com o assistente igual ou menor que a dos humanos.</p>
        </section>
        <section class="app-card">
          <app-bar-chart title="Guardrails acionados" info="assistant.quality.guardrails" [data]="guardrails()" empty="Nenhum guardrail acionado." />
        </section>
        <section class="app-card">
          <app-bar-chart title="Acordos: com o assistente × só humanos" info="assistant.quality.origins" [data]="groups()" highlight="ASSISTANT" empty="Nenhum acordo no período." />
        </section>
        @if (v.pix; as pix) {
          <section class="app-card" data-tour="quality-pix">
            <app-bar-chart title="Pix na conversa: recusas por motivo" info="assistant.quality.pix" [data]="pixDenied()" empty="Nenhuma recusa de Pix no período." />
            <p class="app-muted qp-small">{{ pix.issued }} gerado(s) · {{ pix.delivered }} entregue(s) · {{ pix.suggested }} sugerido(s) · {{ pix.paid }} pago(s), {{ pix.paidWithin24h }} em até 24 h · conversão gerado → pago {{ fraction(pix.conversionRate) }} ({{ fraction(pix.paidWithin24hRate) }} em 24 h)</p>
          </section>
        }
      </div>
    }
  `,
  styles: `
    .qp-small {
      font-size: var(--app-text-xs);
    }
    .qp-date {
      width: 170px !important;
    }
    @media (max-width: 599.98px) {
      .qp-date {
        width: calc(50% - 6px) !important;
      }
    }
    .qp-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
      gap: var(--app-gap);
    }
    @media (max-width: 599px) {
      .qp-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class QualityPanel {
  private readonly api = inject(AssistantReviewApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly fraction = fractionText;

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly today = zonedNow().date;
  protected readonly filters = this.fb.group({
    // modelo ISO (yyyy-MM-dd) do ngbDatepicker
    from: this.fb.control<string | null>(addDays(this.today, -29)),
    to: this.fb.control<string | null>(this.today),
    creditor: this.fb.control<string[]>([]),
    portfolio: this.fb.control<string[]>([]),
  });
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.filters.controls.creditor.value[0] ?? null);
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): QualityFilter => {
        const v = this.filters.getRawValue();
        // data digitada ainda não reconhecida (o campo mostra o erro) não entra no filtro
        return { from: isoDate(v.from), to: isoDate(v.to), creditorId: v.creditor[0] ?? null, portfolioId: v.creditor[0] ? (v.portfolio[0] ?? null) : null };
      }),
    ),
    { initialValue: { from: addDays(this.today, -29), to: this.today, creditorId: null, portfolioId: null } as QualityFilter, equal: sameValue },
  );
  protected readonly quality = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.quality(params.from, params.to, params.creditorId, params.portfolioId) });
  protected readonly q = computed(() => (this.quality.hasValue() ? this.quality.value()! : null));

  protected readonly handoffs = computed(() => bars(this.q()?.handoffsByReason, HANDOFF_REASON_LABELS));
  protected readonly origins = computed(() => bars(this.q()?.agreementsByOrigin, AGREEMENT_ORIGIN_LABELS));
  protected readonly guardrails = computed(() => bars(this.q()?.guardrails, GUARDRAIL_LABELS));
  protected readonly groups = computed(() => bars(this.q()?.agreementsByGroup, AGREEMENT_GROUP_LABELS));
  protected readonly pixDenied = computed(() => bars(this.q()?.pix?.deniedByReason, PIX_DENIAL_LABELS));
  /** Taxas (0–1) como barras; a ordem fixa (assistente primeiro) facilita a comparação. */
  protected readonly earlyBreak = computed<Bar[]>(() => earlyBreakBars(this.q(), AGREEMENT_GROUP_LABELS));
  protected readonly earlyBreakBase = computed(() => earlyBreakBaseText(this.q(), AGREEMENT_GROUP_LABELS));
  protected readonly breakFormat = (b: Bar) => fractionText(b.value);

  protected money(value: number | null, currency: string | null): string {
    return value === null ? '—' : `${new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)} ${currency ?? ''}`.trim();
  }

  protected num(value: number): string {
    return new Intl.NumberFormat('pt-BR').format(value);
  }

  protected score(value: number): string {
    return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(value);
  }
}
