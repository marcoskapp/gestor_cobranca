import { AbstractControl, ValidationErrors } from '@angular/forms';
import { formatAmount } from '../../../shared/components/amount/amount';
import { GoalSubjectType, GoalUnit, GoalUnitOptions } from '../data/incentive.models';

/** Unidades da meta do período, na ordem dos botões. */
export const GOAL_UNITS: readonly GoalUnit[] = ['CURRENCY', 'PERCENT'];

export const GOAL_UNIT_LABELS: Record<GoalUnit, string> = {
  CURRENCY: 'Valor (R$)',
  PERCENT: 'Percentual (%)',
};

/** Limites da meta percentual (decisão 492): 0,01 a 100, até 2 casas. */
export const GOAL_PERCENT_MIN = 0.01;
export const GOAL_PERCENT_MAX = 100;

const PERCENT = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });
const NUMBER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 4 });

/** Meta formatada na unidade: "R$ 80.000,00", "12,5%"; sem unidade (meta antiga), só o número. */
export function goalTargetText(target: number, unit: GoalUnit | null): string {
  if (unit === 'CURRENCY') {
    return formatAmount(target);
  }
  if (unit === 'PERCENT') {
    return `${PERCENT.format(target)}%`;
  }
  return NUMBER.format(target);
}

/** Percentual de 0,01 a 100 com até 2 casas (o `type="number"` aceita mais casas e não valida sozinho). */
export function goalPercentValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as number | null;
  if (value === null || value === undefined || (value as unknown) === '') {
    return null;
  }
  if (!Number.isFinite(value) || value < GOAL_PERCENT_MIN || value > GOAL_PERCENT_MAX || Math.abs(Math.round(value * 100) - value * 100) > 1e-6) {
    return { goalPercent: true };
  }
  return null;
}

/** Explicação da unidade aceita, ligada ao grupo de botões (`aria-describedby`). */
export function goalUnitHint(options: GoalUnitOptions | null | undefined, subjectType: GoalSubjectType): string {
  if (!options) {
    return 'Escolha a unidade: valor em reais para a métrica "valor recuperado" ou percentual para "taxa de recuperação".';
  }
  const who = subjectType === 'USER' ? 'o negociador' : 'a equipe';
  const plan = options.planName ? `${options.planName} (versão ${options.planVersion})` : null;
  switch (options.basis) {
    case 'PLAN': {
      const only = options.units[0];
      const metric = only === 'CURRENCY' ? 'valor recuperado' : 'taxa de recuperação';
      return `${plan ? `A meta do plano ${plan}` : 'A meta dos planos vigentes que usam esta meta'} é por ${metric}: só aceita ${only === 'CURRENCY' ? 'valor em R$' : 'percentual'}.`;
    }
    case 'PLAN_WITHOUT_GOAL':
      return `${plan ? `O plano ${plan}` : 'O plano vigente'} não usa meta ${subjectType === 'USER' ? 'individual' : 'de equipe'} para ${who}. Escolha a unidade; no fechamento, a meta só vale se estiver na unidade da métrica do plano.`;
    case 'MIXED':
      return 'Membros da equipe estão em planos com métricas diferentes (R$ e %). Escolha a unidade; a meta vale só para quem estiver no plano dessa unidade.';
    default:
      return `Nenhum plano vigente no período cobre ${who}. Escolha a unidade; no fechamento, a meta só vale se estiver na unidade da métrica do plano.`;
  }
}
