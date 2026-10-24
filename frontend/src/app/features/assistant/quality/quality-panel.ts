import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { sameValue } from '../../../shared/data/same-value';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { addDays, zonedNow } from '../../collection/data/collection.rules';
import { AGREEMENT_ORIGIN_LABELS } from '../../negotiation/data/agreement.labels';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { BarChart } from '../components/bar-chart';
import { AssistantReviewApi } from '../data/assistant.api';
import { AGREEMENT_GROUP_LABELS, GUARDRAIL_LABELS, HANDOFF_REASON_LABELS } from '../data/assistant.labels';
import { Bar, bars, fractionText } from '../data/assistant.rules';

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
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, AppDatePipe, BarChart, EmptyState, EntityPicker, PageHeader, Skeleton],
  template: `
    <app-page-header title="Qualidade do assistente" subtitle="Resultado do assistente comparado aos humanos, para decidir carteira a carteira quando avançar de fase ou ampliar a alçada." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar qualidade">
      <mat-form-field class="app-compact">
        <mat-label>Período</mat-label>
        <mat-date-range-input [rangePicker]="period">
          <input matStartDate formControlName="from" placeholder="De" />
          <input matEndDate formControlName="to" placeholder="Até" />
        </mat-date-range-input>
        <mat-datepicker-toggle matIconSuffix [for]="period" />
        <mat-date-range-picker #period />
      </mat-form-field>
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
      <app-empty-state icon="insights" tone="error" title="Não foi possível carregar a qualidade." message="Confira o período (até 1 ano) e tente novamente.">
        <button mat-stroked-button type="button" (click)="quality.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (q(); as v) {
      <p class="app-muted qp-small">{{ v.from | appDate: 'date' }} a {{ v.to | appDate: 'date' }} · {{ v.conversations }} conversa(s), {{ v.turns }} turno(s)</p>
      <section class="qp-kpis" aria-label="Indicadores do período" data-tour="quality-kpis">
        <div class="qp-kpi"><span class="qp-kpi__label">Transferência para humano</span><span class="qp-kpi__value">{{ fraction(v.handoffRate) }}</span><span class="qp-kpi__hint">das conversas atendidas</span></div>
        <div class="qp-kpi"><span class="qp-kpi__label">Reclamações</span><span class="qp-kpi__value app-numeric">{{ v.complaints }}</span><span class="qp-kpi__hint">Procon, advogado, ouvidoria ou opt-out</span></div>
        <div class="qp-kpi"><span class="qp-kpi__label">Custo por acordo</span><span class="qp-kpi__value app-numeric">{{ money(v.costPerAgreement, v.costCurrency) }}</span><span class="qp-kpi__hint">total {{ money(v.cost, v.costCurrency) }}</span></div>
        <div class="qp-kpi"><span class="qp-kpi__label">Revisões</span><span class="qp-kpi__value app-numeric">{{ v.avgScore !== null ? score(v.avgScore) + '/5' : '—' }}</span><span class="qp-kpi__hint">{{ v.reviewed }} avaliadas · {{ v.outOfPolicy }} fora da política · <a routerLink="/assistente/revisao">{{ v.pendingReviews }} pendentes</a></span></div>
        <div class="qp-kpi"><span class="qp-kpi__label">Latência média</span><span class="qp-kpi__value app-numeric">{{ v.avgLatencyMs }} ms</span><span class="qp-kpi__hint">{{ v.inputTokens + v.outputTokens }} tokens</span></div>
      </section>
      <div class="qp-grid">
        <section class="app-card qp-card" data-tour="quality-handoffs">
          <app-bar-chart title="Transferências por motivo" [data]="handoffs()" empty="Nenhuma transferência no período." />
        </section>
        <section class="app-card qp-card" data-tour="quality-origin">
          <app-bar-chart title="Acordos por origem" [data]="origins()" empty="Nenhum acordo no período." />
        </section>
        <section class="app-card qp-card" data-tour="quality-break">
          <app-bar-chart [title]="'Quebra precoce (até ' + v.earlyBreakDays + ' dias): assistente × humanos'" [data]="earlyBreak()" highlight="ASSISTANT" [format]="breakFormat" empty="Sem acordos formalizados no período." />
          <p class="app-muted qp-small">Critério de avanço: a quebra com o assistente igual ou menor que a dos humanos.</p>
        </section>
        <section class="app-card qp-card">
          <app-bar-chart title="Guardrails acionados" [data]="guardrails()" empty="Nenhum guardrail acionado." />
        </section>
        <section class="app-card qp-card">
          <app-bar-chart title="Acordos: com o assistente × só humanos" [data]="groups()" highlight="ASSISTANT" empty="Nenhum acordo no período." />
        </section>
      </div>
    }
  `,
  styles: `
    .qp-small {
      font-size: var(--app-text-xs);
    }
    .qp-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
      gap: 12px;
      margin-bottom: var(--app-gap);
    }
    .qp-kpi {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
    }
    .qp-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--mat-sys-on-surface-variant);
    }
    .qp-kpi__value {
      font-size: var(--app-text-xl);
      font-weight: 600;
    }
    .qp-kpi__hint {
      font-size: var(--app-text-xs);
      color: var(--mat-sys-on-surface-variant);
    }
    .qp-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
      gap: var(--app-gap);
    }
    .qp-card {
      margin: 0 !important;
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
    from: this.fb.control<Date | null>(fromLocalDate(addDays(this.today, -29))),
    to: this.fb.control<Date | null>(fromLocalDate(this.today)),
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
        return { from: toLocalDate(v.from), to: toLocalDate(v.to), creditorId: v.creditor[0] ?? null, portfolioId: v.creditor[0] ? (v.portfolio[0] ?? null) : null };
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
  /** Taxas (0–1) como barras; a ordem fixa (assistente primeiro) facilita a comparação. */
  protected readonly earlyBreak = computed<Bar[]>(() => {
    const rates = this.q()?.earlyBreakRate ?? {};
    const keys = ['ASSISTANT', 'HUMAN'].filter((k) => rates[k] !== undefined && rates[k] !== null);
    const max = Math.max(0, ...keys.map((k) => rates[k]));
    return keys.map((k) => ({ key: k, label: AGREEMENT_GROUP_LABELS[k] ?? k, value: rates[k], share: max ? rates[k] / max : 0, ofTotal: rates[k] }));
  });
  protected readonly breakFormat = (b: Bar) => fractionText(b.value);

  protected money(value: number | null, currency: string | null): string {
    return value === null ? '—' : `${new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)} ${currency ?? ''}`.trim();
  }

  protected score(value: number): string {
    return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(value);
  }
}
