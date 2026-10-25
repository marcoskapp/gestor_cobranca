import { Component, computed, inject, input } from '@angular/core';
import { Amount } from '../../../shared/components/amount/amount';
import { nameMap } from '../../../shared/data/name-map';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import {
  ACCELERATOR_MODE_LABELS,
  CREDIT_MODEL_LABELS,
  GOAL_METRIC_LABELS,
  GOAL_SCOPE_LABELS,
  ORIGINS,
  ORIGIN_LABELS,
  POOL_DISTRIBUTION_LABELS,
  TERMINATION_POLICY_LABELS,
  TIER_MODE_LABELS,
} from '../data/incentive.labels';
import { PlanRules, SegmentSelector } from '../data/incentive.models';
import { factorText, pct, segmentText } from '../data/incentive.rules';

/** Regras de uma versão de plano em linguagem de negócio (somente leitura). */
@Component({
  selector: 'app-plan-rules-summary',
  imports: [Amount],
  template: `
    @let r = rules();
    <h3 class="app-section-title">Elegibilidade</h3>
    <dl class="app-definition-list mb-3">
      <dt>Perfis</dt>
      <dd>{{ r.eligibility.roleCodes.length ? r.eligibility.roleCodes.join(', ') : 'Qualquer perfil' }}</dd>
      <dt>Equipes</dt>
      <dd>{{ r.eligibility.teamIds.length ? names(r.eligibility.teamIds, teams.name) : 'Qualquer equipe' }}</dd>
      <dt>Credores e carteiras</dt>
      <dd>
        {{ r.eligibility.creditorIds.length ? names(r.eligibility.creditorIds, creditors.name) : 'Todos os credores' }}
        · {{ r.eligibility.portfolioIds.length ? names(r.eligibility.portfolioIds, portfolios.name) : 'todas as carteiras' }}
      </dd>
      <dt>Dias trabalhados mínimos</dt>
      <dd>{{ r.eligibility.minWorkedDays || 'Sem exigência' }}</dd>
    </dl>

    <h3 class="app-section-title">Componentes</h3>
    <dl class="app-definition-list mb-3">
      <dt>Percentual sobre recuperado</dt>
      <dd>
        @if (r.recoveryRate; as rr) {
          {{ tierModes[rr.mode] }}
          @for (t of rr.tables; track $index) {
            <div class="mt-1">
              <span class="app-muted">{{ segment(t.segment) }}:</span>
              @for (tier of t.tiers; track tier.fromAmount; let last = $last) {
                a partir de <app-amount [value]="tier.fromAmount" /> → {{ pct(tier.ratePercent) }}{{ last ? '' : ';' }}
              }
            </div>
          }
        } @else {
          Não usa
        }
      </dd>
      <dt>Valor fixo por acordo</dt>
      <dd>
        @if (r.fixedPerAgreement) {
          <app-amount [value]="r.fixedPerAgreement.amount" /> por acordo com 1ª parcela paga
        } @else {
          Não usa
        }
      </dd>
      <dt>Meta</dt>
      <dd>
        @if (r.goal; as g) {
          {{ goalMetrics[g.metric] }}, {{ goalScopes[g.scope].toLowerCase() }}{{ g.defaultTarget !== null ? ' · meta padrão ' + g.defaultTarget : ' · só com meta cadastrada' }}.
          Abaixo de {{ pct(g.triggerPercent) }} paga {{ factor(g.belowTriggerFactor) }}; acima de {{ pct(g.acceleratorThresholdPercent) }} acelera {{ factor(g.acceleratorFactor) }}
          ({{ acceleratorModes[g.acceleratorMode].toLowerCase() }}){{ g.capPercent !== null ? '; teto ' + pct(g.capPercent) : '; sem teto' }}.
        } @else {
          Sem meta
        }
      </dd>
      <dt>Multiplicadores de dificuldade</dt>
      <dd>
        @for (m of r.difficultyMultipliers; track $index) {
          <div>{{ segment(m.segment) }}: {{ factor(m.factor) }}</div>
        } @empty {
          1,0 para todos os segmentos
        }
      </dd>
      <dt>Pool da equipe</dt>
      <dd>
        @if (r.teamPool; as p) {
          {{ pct(p.ratePercent) }} do recuperado da equipe{{ p.includeAssistantAutonomous ? ', inclusive acordos autônomos do assistente' : '' }} · {{ poolDistributions[p.distribution] }}
        } @else {
          Não usa
        }
      </dd>
    </dl>

    <h3 class="app-section-title">Qualidade</h3>
    <dl class="app-definition-list mb-3">
      <dt>Quebra precoce</dt>
      <dd>
        @if (r.quality.earlyBreak; as e) {
          −{{ pct(e.deflatorPercent) }} se a quebra em {{ e.windowDays }} dias passar de {{ pct(e.thresholdPercent) }} da safra (mínimo {{ e.minSample }} acordos)
        } @else {
          Sem deflator
        }
      </dd>
      <dt>Uso do envelope</dt>
      <dd>
        @if (r.quality.envelope; as e) {
          −{{ pct(e.deflatorPercent) }} se o uso médio passar de {{ pct(e.thresholdPercent) }} (mínimo {{ e.minSample }} acordos)
        } @else {
          Sem deflator
        }
      </dd>
      <dt>Portão de monitoria</dt>
      <dd>
        @if (r.quality.monitoringGate; as m) {
          Nota média ≥ {{ m.minScore }}; sem avaliação no período {{ m.suspendWithoutEvaluation ? 'suspende' : 'libera' }}
        } @else {
          Sem portão
        }
      </dd>
      <dt>Violação grave de compliance</dt>
      <dd>{{ r.quality.zeroOnSevereViolation ? 'Zera o variável do período (com contestação)' : 'Não zera' }}</dd>
    </dl>

    <h3 class="app-section-title">Atribuição de crédito</h3>
    <dl class="app-definition-list mb-3">
      @for (o of origins; track o) {
        <dt>{{ originLabels[o] }}</dt>
        <dd>{{ pct(r.attribution.originWeights[o]) }}</dd>
      }
      <dt>Janela de influência</dt>
      <dd>{{ r.attribution.influenceWindowDays }} dias</dd>
      <dt>Mudança de carteira</dt>
      <dd>{{ creditModels[r.attribution.creditModel] }}</dd>
      <dt>Renegociação própria</dt>
      <dd>{{ pct(r.attribution.ownRenegotiationWeightPercent) }}</dd>
      <dt>Desligamento</dt>
      <dd>{{ terminationPolicies[r.attribution.terminationPolicy] }} ({{ r.attribution.terminationGraceDays }} dias úteis)</dd>
    </dl>

    <h3 class="app-section-title">Limites e carência</h3>
    <dl class="app-definition-list mb-0">
      <dt>Teto por período</dt>
      <dd>
        @if (r.limits.periodCap !== null) {
          <app-amount [value]="r.limits.periodCap" />
        } @else {
          Sem teto
        }
      </dd>
      <dt>Carência</dt>
      <dd>{{ r.clearing.graceBusinessDays }} dias úteis após a confirmação do pagamento</dd>
      <dt>Estorno após fechamento</dt>
      <dd>{{ r.clearing.clawbackEnabled ? 'Habilitado (validado com jurídico/RH)' : 'Desabilitado (padrão)' }}</dd>
    </dl>
  `,
})
export class PlanRulesSummary {
  readonly rules = input.required<PlanRules>();
  protected readonly pct = pct;
  protected readonly factor = factorText;
  protected readonly origins = ORIGINS;
  protected readonly originLabels = ORIGIN_LABELS;
  protected readonly tierModes = TIER_MODE_LABELS;
  protected readonly goalMetrics = GOAL_METRIC_LABELS;
  protected readonly goalScopes = GOAL_SCOPE_LABELS;
  protected readonly acceleratorModes = ACCELERATOR_MODE_LABELS;
  protected readonly creditModels = CREDIT_MODEL_LABELS;
  protected readonly terminationPolicies = TERMINATION_POLICY_LABELS;
  protected readonly poolDistributions = POOL_DISTRIBUTION_LABELS;

  private readonly segments = computed(() => {
    const r = this.rules();
    return [r.eligibility as unknown as SegmentSelector, ...(r.recoveryRate?.tables.map((t) => t.segment) ?? []), ...r.difficultyMultipliers.map((m) => m.segment)];
  });
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.segments().flatMap((s) => s.creditorIds));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.segments().flatMap((s) => s.portfolioIds));
  protected readonly teams = nameMap(inject(TeamLookup), () => this.rules().eligibility.teamIds);

  protected segment(s: SegmentSelector): string {
    return segmentText(s, this.creditors.name, this.portfolios.name);
  }

  protected names(ids: readonly string[], name: (id: string) => string): string {
    return ids.map(name).join(', ');
  }
}
