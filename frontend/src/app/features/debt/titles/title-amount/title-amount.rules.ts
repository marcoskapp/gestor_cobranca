/**
 * Regras de tela da correção manual do valor (funções puras, testadas em `title-amount.rules.spec.ts`). A decisão é do
 * servidor; aqui só a prévia para o usuário saber antes de enviar.
 */
import { AmountAdjustmentMode, AmountAdjustmentView, AmountComponent } from '../../data/debt.models';

export interface AdjustmentPreview {
  changed: boolean;
  delta: number;
  absDelta: number;
  newOpenBalance: number;
  mode: AmountAdjustmentMode;
}

const cents = (v: number) => Math.round(v * 100);

/** Redução até o limite → aplicada já; acima do limite ou qualquer aumento → aprovação de outra pessoa (RB06). */
export function adjustmentPreview(
  current: readonly { component: AmountComponent; balance: number }[],
  targets: Partial<Record<AmountComponent, number>>,
  openBalance: number,
  threshold: number,
): AdjustmentPreview {
  let delta = 0;
  let changed = false;
  for (const row of current) {
    const target = targets[row.component];
    if (target === undefined || target === null || cents(target) === cents(row.balance)) {
      continue;
    }
    changed = true;
    delta += cents(target) - cents(row.balance);
  }
  const mode: AmountAdjustmentMode = delta < 0 && -delta <= cents(threshold) ? 'WITHIN_LIMIT' : 'DUAL_CONTROL';
  return { changed: changed && delta !== 0, delta: delta / 100, absDelta: Math.abs(delta) / 100, newOpenBalance: (cents(openBalance) + delta) / 100, mode };
}

/** Quem pode decidir o pedido: aprovador que não é quem pediu. */
export function canDecide(a: Pick<AmountAdjustmentView, 'status' | 'requestedBy'>, userId: string | null, canApprove: boolean): boolean {
  return canApprove && a.status === 'PENDING_APPROVAL' && a.requestedBy !== userId;
}

export function canWithdraw(a: Pick<AmountAdjustmentView, 'status' | 'requestedBy'>, userId: string | null): boolean {
  return a.status === 'PENDING_APPROVAL' && a.requestedBy === userId;
}
