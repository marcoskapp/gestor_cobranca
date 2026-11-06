/**
 * Regras de tela da correção manual do valor (funções puras, testadas em `title-amount.rules.spec.ts`). A decisão é do
 * servidor; aqui só a prévia para o usuário saber antes de enviar.
 */
import { AmountAdjustmentMode, AmountAdjustmentSettings, AmountAdjustmentView, AmountComponent } from '../../data/debt.models';

export interface AdjustmentPreview {
  changed: boolean;
  delta: number;
  absDelta: number;
  newOpenBalance: number;
  mode: AmountAdjustmentMode;
  /** A redução caberia sozinha no limite, mas não somada às reduções recentes do título: vai para aprovação. */
  cumulative: boolean;
}

const cents = (v: number) => Math.round(v * 100);

/**
 * Redução pura (nenhum componente sobe) com a soma das reduções, mais as reduções já aplicadas sem segunda pessoa no
 * mesmo título na janela do limite acumulado, até o limite → aplicada já; acima do limite ou com aumento de qualquer
 * componente (mesmo com o líquido negativo) → aprovação de outra pessoa (RB06; espelho de
 * `TitleAmountAdjustment.isPureReductionWithin`).
 */
export function adjustmentPreview(
  current: readonly { component: AmountComponent; balance: number }[],
  targets: Partial<Record<AmountComponent, number>>,
  openBalance: number,
  threshold: number,
  recentReductions: number,
): AdjustmentPreview {
  let delta = 0;
  let increases = 0;
  let decreases = 0;
  let changed = false;
  for (const row of current) {
    const target = targets[row.component];
    if (target === undefined || target === null || cents(target) === cents(row.balance)) {
      continue;
    }
    changed = true;
    const change = cents(target) - cents(row.balance);
    delta += change;
    if (change > 0) {
      increases += change;
    } else {
      decreases -= change;
    }
  }
  const pure = increases === 0 && decreases > 0;
  const withinAlone = pure && decreases <= cents(threshold);
  const mode: AmountAdjustmentMode = pure && decreases + cents(recentReductions) <= cents(threshold) ? 'WITHIN_LIMIT' : 'DUAL_CONTROL';
  return {
    changed: changed && delta !== 0,
    delta: delta / 100,
    absDelta: Math.abs(delta) / 100,
    newOpenBalance: (cents(openBalance) + delta) / 100,
    mode,
    cumulative: withinAlone && mode === 'DUAL_CONTROL',
  };
}

/** Quem pode decidir o pedido: aprovador que não é quem pediu. */
export function canDecide(a: Pick<AmountAdjustmentView, 'status' | 'requestedBy'>, userId: string | null, canApprove: boolean): boolean {
  return canApprove && a.status === 'PENDING_APPROVAL' && a.requestedBy !== userId;
}

export function canWithdraw(a: Pick<AmountAdjustmentView, 'status' | 'requestedBy'>, userId: string | null): boolean {
  return a.status === 'PENDING_APPROVAL' && a.requestedBy === userId;
}

/** Quem pode confirmar o aumento de limite proposto: aprovador com todos os credores que não é quem propôs, no prazo. */
export function canConfirmThreshold(
  s: Pick<AmountAdjustmentSettings, 'proposedThreshold' | 'proposedBy' | 'proposalExpired'>,
  userId: string | null,
  canManageLimit: boolean,
): boolean {
  return canManageLimit && s.proposedThreshold !== null && !s.proposalExpired && s.proposedBy !== userId;
}
