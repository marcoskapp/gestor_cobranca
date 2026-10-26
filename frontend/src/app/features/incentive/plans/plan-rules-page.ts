import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { CommissionPlansApi } from '../data/incentive.api';
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
import { GoalMetric, PlanRules, PlanView } from '../data/incentive.models';
import {
  cloneRules,
  defaultEarlyBreak,
  defaultEnvelope,
  defaultFixed,
  defaultGate,
  defaultGoal,
  defaultPool,
  defaultRateTable,
  defaultRecoveryRate,
  emptySegment,
  normalizeRules,
  validateRules,
} from '../data/plan-rules';
import { incentiveError } from '../statements/statement-dialogs';
import { IdsField } from './ids-field';
import { SegmentEditor } from './segment-editor';

const options = <T extends string>(labels: Record<T, string>, skip: readonly T[] = []) =>
  (Object.entries(labels) as [T, string][]).filter(([v]) => !skip.includes(v)).map(([value, label]) => ({ value, label }));

type Optional = 'recoveryRate' | 'fixedPerAgreement' | 'goal' | 'teamPool' | 'earlyBreak' | 'envelope' | 'monitoringGate';

/**
 * Edição das regras de um rascunho de plano (`PlanRules`): elegibilidade, componentes, meta,
 * multiplicadores, qualidade, atribuição, pool, limites e carência. Valida como o servidor antes
 * de salvar; versões publicadas são somente leitura.
 */
@Component({
  selector: 'app-incentive-plan-rules-page',
  imports: [FormsModule, RouterLink, ...FORM_FIELD, EmptyState, Icon, IdsField, PageHeader, SegmentEditor, Skeleton],
  templateUrl: './plan-rules-page.html',
  styleUrl: '../incentive.scss',
  styles: `
    .re-group {
      margin: 0;
      padding: 0;
      border: 0;
      min-width: 0;
    }
    .re-sub {
      margin: 12px 0 4px;
      font-weight: 500;
    }
    .re-row {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 4px 16px;
      align-items: start;
    }
    .re-block {
      padding: 10px 12px;
      margin-bottom: 8px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
    }
    .re-switch {
      margin-bottom: 8px;
    }
  `,
})
export class PlanRulesPage {
  readonly id = input.required<string>();
  private readonly api = inject(CommissionPlansApi);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly teams = inject(TeamLookup);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly portfolios = inject(PortfolioLookup).source();

  protected readonly tierModes = options(TIER_MODE_LABELS);
  /** Recuperado × esperado não é apurado pelo motor (o servidor recusa): fora das opções. */
  protected readonly goalMetrics = options(GOAL_METRIC_LABELS, ['RECOVERED_VS_EXPECTED'] as GoalMetric[]);
  protected readonly goalScopes = options(GOAL_SCOPE_LABELS);
  protected readonly acceleratorModes = options(ACCELERATOR_MODE_LABELS);
  protected readonly creditModels = options(CREDIT_MODEL_LABELS);
  protected readonly terminationPolicies = options(TERMINATION_POLICY_LABELS);
  protected readonly poolDistributions = options(POOL_DISTRIBUTION_LABELS);
  protected readonly origins = ORIGINS;
  protected readonly originLabels = ORIGIN_LABELS;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly plan = valueOr(this.resource, undefined);
  /** Cópia editável (o editor altera no lugar e avisa por `touch`). */
  protected readonly model = signal<PlanRules | null>(null);
  protected readonly header = signal({ name: '', description: '', priority: 0 });
  protected readonly roleText = signal('');
  private readonly version = signal(0);
  protected readonly dirty = signal(false);
  protected readonly submitted = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly issues = computed(() => {
    this.version();
    const m = this.model();
    return m ? validateRules(normalizeRules(this.withRoles(m))) : [];
  });

  constructor() {
    effect(() => {
      const p = this.plan();
      if (p && !this.model()) {
        this.load(p);
      }
      this.breadcrumbs.setCurrentLabel(p ? `${p.name} · v${p.version} · regras` : null);
    });
  }

  private load(p: PlanView): void {
    this.model.set(cloneRules(p.rules));
    this.header.set({ name: p.name, description: p.description ?? '', priority: p.priority });
    this.roleText.set(p.rules.eligibility.roleCodes.join(', '));
    this.dirty.set(false);
  }

  protected touch(): void {
    this.dirty.set(true);
    this.version.update((v) => v + 1);
  }

  protected setHeader(field: 'name' | 'description' | 'priority', value: string | number): void {
    this.header.update((h) => ({ ...h, [field]: value }));
    this.touch();
  }

  protected setRoles(text: string): void {
    this.roleText.set(text);
    this.touch();
  }

  protected has(component: Optional): boolean {
    this.version();
    const m = this.model()!;
    switch (component) {
      case 'earlyBreak':
      case 'envelope':
      case 'monitoringGate':
        return m.quality[component] !== null;
      default:
        return m[component] !== null;
    }
  }

  /** Liga (com os valores de referência) ou desliga um componente opcional. */
  protected toggle(component: Optional, on: boolean): void {
    const m = this.model()!;
    switch (component) {
      case 'recoveryRate':
        m.recoveryRate = on ? defaultRecoveryRate() : null;
        break;
      case 'fixedPerAgreement':
        m.fixedPerAgreement = on ? defaultFixed() : null;
        break;
      case 'goal':
        m.goal = on ? defaultGoal() : null;
        break;
      case 'teamPool':
        m.teamPool = on ? defaultPool() : null;
        break;
      case 'earlyBreak':
        m.quality.earlyBreak = on ? defaultEarlyBreak() : null;
        break;
      case 'envelope':
        m.quality.envelope = on ? defaultEnvelope() : null;
        break;
      case 'monitoringGate':
        m.quality.monitoringGate = on ? defaultGate() : null;
        break;
    }
    this.touch();
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected addTable(): void {
    this.model()!.recoveryRate!.tables.push(defaultRateTable());
    this.touch();
  }

  protected removeTable(i: number): void {
    this.model()!.recoveryRate!.tables.splice(i, 1);
    this.touch();
  }

  protected addTier(t: number): void {
    const tiers = this.model()!.recoveryRate!.tables[t].tiers;
    const last = tiers[tiers.length - 1];
    tiers.push({ fromAmount: (last?.fromAmount ?? 0) + 50000, ratePercent: last?.ratePercent ?? 1 });
    this.touch();
  }

  protected removeTier(t: number, i: number): void {
    this.model()!.recoveryRate!.tables[t].tiers.splice(i, 1);
    this.touch();
  }

  protected addMultiplier(): void {
    this.model()!.difficultyMultipliers.push({ segment: emptySegment(), factor: 1 });
    this.touch();
  }

  protected removeMultiplier(i: number): void {
    this.model()!.difficultyMultipliers.splice(i, 1);
    this.touch();
  }

  protected revert(): void {
    const p = this.plan();
    if (p) {
      this.model.set(null);
      this.load(p);
      this.submitted.set(false);
      this.version.update((v) => v + 1);
    }
  }

  protected save(): void {
    const p = this.plan()!;
    this.submitted.set(true);
    const h = this.header();
    if (this.issues().length || !h.name.trim() || this.saving()) {
      return;
    }
    const rules = normalizeRules(this.withRoles(this.model()!));
    this.saving.set(true);
    this.error.set(null);
    this.api
      .update(p.id, { code: p.code, name: h.name.trim(), description: h.description.trim() || null, priority: Number(h.priority), rules })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.dirty.set(false);
          this.notifications.success('Regras salvas no rascunho.');
          void this.router.navigate(['/remuneracao/planos', saved.id]);
        },
        error: (err: unknown) => this.error.set(incentiveError(err)),
      });
  }

  private withRoles(m: PlanRules): PlanRules {
    return {
      ...m,
      eligibility: {
        ...m.eligibility,
        roleCodes: this.roleText()
          .split(/[,;\s]+/)
          .map((c) => c.trim().toUpperCase())
          .filter(Boolean),
      },
    };
  }
}
