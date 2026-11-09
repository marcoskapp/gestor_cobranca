/**
 * Textos da aprovação de propostas no padrão único das filas (ux.md §18): quanto passa da alçada de quem negociou, em
 * valor e %, e o prazo da aprovação na fila. Funções puras: as regras de alçada continuam no servidor.
 */
import { formatAmount } from '../../../shared/components/amount/amount';
import type { ApprovalReason } from '../../../shared/approval/approval-decision';
import { ApprovalAssessmentView, AuthorityGapView, ProposalView } from '../data/agreement.models';
import { percent } from '../data/negotiation.labels';
import { violationText } from '../data/agreement.rules';

/** Nível 99 do perfil = até o topo da escada da política. */
const TOP_LEVEL = 99;

const GAP_LABELS: Record<AuthorityGapView['dimension'], { label: string; hint?: string }> = {
  LEVEL: { label: 'Nível da escada' },
  DISCOUNT_SHARE: { label: 'Desconto', hint: 'fração usada do desconto máximo da política' },
  INSTALLMENTS: { label: 'Pagamentos' },
  AMOUNT: { label: 'Valor do acordo' },
  DOWN_PAYMENT_SHARE: { label: 'Entrada', hint: 'mínimo em % do valor negociado' },
  RENEGOTIATIONS: { label: 'Renegociações do acordo' },
};

/** Pontos percentuais ("22 p.p."). */
function points(value: number): string {
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value)} p.p.`;
}

function above(excess: number, limit: number | null): string {
  return limit && limit > 0 ? ` (${percent((excess / limit) * 100, 0)} acima)` : '';
}

function limitText(g: AuthorityGapView, value: number | null): string {
  switch (g.dimension) {
    case 'LEVEL':
      return value === null ? '—' : value >= TOP_LEVEL ? 'toda a escada' : `nível ${value}`;
    case 'DISCOUNT_SHARE':
      return percent(value);
    case 'AMOUNT':
      return value === null ? 'sem limite' : formatAmount(value);
    case 'DOWN_PAYMENT_SHARE':
      return value === null ? '—' : `mín. ${percent(value)}`;
    default:
      return value === null ? '—' : String(value);
  }
}

function requestedText(g: AuthorityGapView): string {
  switch (g.dimension) {
    case 'LEVEL':
      return `nível ${g.requested}`;
    case 'DISCOUNT_SHARE':
    case 'DOWN_PAYMENT_SHARE':
      return percent(g.requested);
    case 'AMOUNT':
      return formatAmount(g.requested);
    default:
      return String(g.requested);
  }
}

function excessText(g: AuthorityGapView): string | null {
  const limit = g.proposerLimit;
  if (limit === null) {
    return null;
  }
  switch (g.dimension) {
    case 'LEVEL':
      return `+${g.requested - limit} ${g.requested - limit === 1 ? 'nível' : 'níveis'}`;
    case 'DISCOUNT_SHARE':
      return `+${points(g.requested - limit)}${above(g.requested - limit, limit)}`;
    case 'AMOUNT':
      return `+${formatAmount(g.requested - limit)}${above(g.requested - limit, limit)}`;
    case 'DOWN_PAYMENT_SHARE':
      return `${points(limit - g.requested)} abaixo`;
    default:
      return `+${g.requested - limit}${above(g.requested - limit, limit)}`;
  }
}

/** Linhas de "Por que precisa de aprovação" com o pedido, o limite de quem negociou, quanto passa e a alçada de quem decide. */
export function authorityGapReasons(a: ApprovalAssessmentView): ApprovalReason[] {
  const withApprover = a.approverProfileCode !== null;
  return a.gaps.map((g) => ({
    ...GAP_LABELS[g.dimension],
    requested: requestedText(g),
    limit: limitText(g, g.proposerLimit),
    excess: excessText(g),
    approverLimit: withApprover ? limitText(g, g.approverLimit) : null,
    covered: withApprover ? g.approverCovers : null,
  }));
}

/**
 * Motivos registrados na proposta que a avaliação não mediu (perfil de quem negociou removido, ou motivo sem medida,
 * como a renegociação): aparecem como texto traduzido.
 */
export function unmeasuredViolations(p: ProposalView, a: ApprovalAssessmentView | null): string[] {
  const measured = new Set((a?.gaps ?? []).map((g) => DIMENSION_CODES[g.dimension]));
  return p.authorityViolations.filter((code) => !measured.has(code)).map(violationText);
}

const DIMENSION_CODES: Record<AuthorityGapView['dimension'], string> = {
  LEVEL: 'authority.level_exceeded',
  DISCOUNT_SHARE: 'authority.discount_exceeded',
  INSTALLMENTS: 'authority.installments_exceeded',
  AMOUNT: 'authority.amount_exceeded',
  DOWN_PAYMENT_SHARE: 'authority.down_payment_below_minimum',
  RENEGOTIATIONS: 'authority.renegotiations_exceeded',
};

/** Desconto em % da dívida atualizada (coluna da fila). */
export function discountShare(p: Pick<ProposalView, 'discountAmount' | 'updatedAmount'>): number {
  return p.updatedAmount > 0 ? Math.round((p.discountAmount / p.updatedAmount) * 10000) / 100 : 0;
}

/** Prazo da aprovação na fila: a validade da proposta ("vence hoje", "vence em 3 dias", "vencida"). */
export function deadlineText(validUntil: string | null, today: string): { text: string; tone: 'danger' | 'warning' | null } {
  if (!validUntil) {
    return { text: 'sem prazo', tone: null };
  }
  const days = Math.round((Date.parse(`${validUntil}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
  if (days < 0) {
    return { text: 'vencida', tone: 'danger' };
  }
  if (days === 0) {
    return { text: 'vence hoje', tone: 'danger' };
  }
  if (days === 1) {
    return { text: 'vence amanhã', tone: 'warning' };
  }
  return { text: `vence em ${days} dias`, tone: null };
}
