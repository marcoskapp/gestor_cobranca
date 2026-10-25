/**
 * Regras de tela de propostas e acordos (funções puras). Orientam quais ações aparecem; o servidor
 * revalida tudo (guard, alçada, escopo e situação) a cada comando.
 */
import type { IconName } from '../../../shared/components/icon/icon-names';
import { ACCEPTANCE_CHANNEL_LABELS, ACTOR_TYPE_LABELS, AUTHORITY_VIOLATION_LABELS } from './agreement.labels';
import { AcceptanceChannel, AgreementStatus, AgreementView, InstallmentView, ProposalStatus, ProposalView } from './agreement.models';
import { ValidationOutcome } from './negotiation.models';

/** Botão de envio conforme o resultado da validação (acima da alçada o botão muda, não some). */
export type SubmitMode = 'propose' | 'request-approval';

export function submitMode(outcome: ValidationOutcome | null | undefined): SubmitMode | null {
  if (outcome === 'WITHIN_AUTHORITY') {
    return 'propose';
  }
  if (outcome === 'REQUIRES_APPROVAL') {
    return 'request-approval';
  }
  return null;
}

/** Propostas que ainda podem avançar (o job diário as expira). */
export const OPEN_PROPOSAL_STATUSES: readonly ProposalStatus[] = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACCEPTED'];

/** Acordos em vigor (podem ser cancelados); renegociação também aceita o quebrado. */
export const ACTIVE_AGREEMENT_STATUSES: readonly AgreementStatus[] = ['FORMALIZED', 'IN_PAYMENT'];
export const RENEGOTIABLE_AGREEMENT_STATUSES: readonly AgreementStatus[] = ['FORMALIZED', 'IN_PAYMENT', 'BROKEN'];

/** Validade vencida antes de o job diário marcar `EXPIRED` (comparação de `LocalDate`). */
export function validityLapsed(p: Pick<ProposalView, 'status' | 'validUntil'>, today: string): boolean {
  return OPEN_PROPOSAL_STATUSES.includes(p.status) && !!p.validUntil && p.validUntil < today;
}

export interface ProposalPermissions {
  userId: string | null;
  canPropose: boolean;
  canApprove: boolean;
  canFormalize: boolean;
}

export interface ProposalActions {
  approve: boolean;
  reject: boolean;
  accept: boolean;
  formalize: boolean;
  adopt: boolean;
  term: boolean;
}

/** Ações disponíveis na proposta para o usuário (ninguém decide a própria proposta). */
export function proposalActions(p: ProposalView, perms: ProposalPermissions, today: string): ProposalActions {
  const lapsed = validityLapsed(p, today);
  const own = p.actor.type === 'USER' && p.actor.id === perms.userId;
  const pending = p.status === 'PENDING_APPROVAL' && perms.canApprove && !own && !lapsed;
  return {
    approve: pending,
    reject: pending,
    accept: (p.status === 'DRAFT' || p.status === 'APPROVED') && perms.canFormalize && !lapsed,
    formalize: p.status === 'ACCEPTED' && perms.canFormalize && !lapsed,
    adopt: p.status === 'HANDED_OFF' && perms.canPropose,
    term: !!p.acceptance,
  };
}

export interface AgreementPermissions {
  canCancel: boolean;
  canRenegotiate: boolean;
}

export function agreementActions(a: Pick<AgreementView, 'status'>, perms: AgreementPermissions): { cancel: boolean; renegotiate: boolean } {
  return {
    cancel: perms.canCancel && ACTIVE_AGREEMENT_STATUSES.includes(a.status),
    renegotiate: perms.canRenegotiate && RENEGOTIABLE_AGREEMENT_STATUSES.includes(a.status),
  };
}

export interface ScheduleSummary {
  total: number;
  paid: number;
  overdue: number;
  /** Próxima parcela a pagar (aberta, parcial ou vencida), em ordem. */
  next: InstallmentView | null;
  /** Percentual pago do total do acordo (0–100). */
  paidShare: number;
}

export function scheduleSummary(a: Pick<AgreementView, 'installments' | 'totalAmount' | 'paidAmount'>): ScheduleSummary {
  const sorted = [...a.installments].sort((x, y) => x.number - y.number);
  const next = sorted.find((i) => i.status === 'OPEN' || i.status === 'PARTIALLY_PAID' || i.status === 'OVERDUE') ?? null;
  return {
    total: sorted.length,
    paid: sorted.filter((i) => i.status === 'PAID').length,
    overdue: sorted.filter((i) => i.status === 'OVERDUE').length,
    next,
    paidShare: a.totalAmount > 0 ? Math.min(100, Math.round((a.paidAmount / a.totalAmount) * 1000) / 10) : 0,
  };
}

/** Saldo da parcela (valor − pago, nunca negativo). */
export function installmentBalance(i: Pick<InstallmentView, 'amount' | 'paidAmount' | 'status'>): number {
  return i.status === 'CANCELLED' ? 0 : Math.max(0, Math.round((i.amount - i.paidAmount) * 100) / 100);
}

export interface TimelineEntry {
  at: string;
  icon: IconName;
  title: string;
  detail: string | null;
}

/** Histórico da proposta a partir dos registros da própria view (criação, decisão, aceite, acordo). */
export function proposalTimeline(p: ProposalView, name: (actorType: string, id: string | null) => string): TimelineEntry[] {
  const entries: TimelineEntry[] = [
    {
      at: p.createdAt,
      icon: p.actor.type === 'ASSISTANT' ? 'robot' : 'pencil-square',
      title: p.handoffProposalId ? 'Proposta criada a partir da sugestão do assistente' : 'Proposta criada',
      detail: `${name(p.actor.type, p.actor.id)}${p.actor.profileCode ? ` · alçada ${p.actor.profileCode}` : ''}`,
    },
  ];
  if (p.decision) {
    entries.push({
      at: p.decision.decidedAt,
      icon: p.status === 'REJECTED' ? 'slash-circle' : 'patch-check',
      title: p.status === 'REJECTED' ? 'Reprovada' : 'Aprovada',
      detail: [name('USER', p.decision.decidedBy), p.decision.profileCode ? `alçada ${p.decision.profileCode}` : null, p.decision.note].filter(Boolean).join(' · '),
    });
  }
  if (p.acceptance) {
    const channel = ACCEPTANCE_CHANNEL_LABELS[p.acceptance.channel as AcceptanceChannel] ?? p.acceptance.channel;
    entries.push({
      at: p.acceptance.acceptedAt,
      icon: 'person-check',
      title: 'Aceite do devedor registrado',
      detail: [`por ${channel}`, p.acceptance.reference ? `referência ${p.acceptance.reference}` : null, `registrado por ${name(p.acceptance.actorType, p.acceptance.actorId)}`].filter(Boolean).join(' · '),
    });
  }
  if (p.formalizedAt) {
    entries.push({ at: p.formalizedAt, icon: 'person-hearts', title: 'Acordo formalizado', detail: null });
  }
  return entries.sort((a, b) => a.at.localeCompare(b.at));
}

/** Nome do ator para listas: assistente sempre identificado por texto. */
export function actorText(actorType: string, id: string | null, userId: string | null, userName: (id: string) => string): string {
  if (actorType === 'USER') {
    return id && id === userId ? 'Você' : id ? userName(id) : 'Operador';
  }
  return ACTOR_TYPE_LABELS[actorType] ?? actorType;
}

export function violationText(code: string): string {
  return AUTHORITY_VIOLATION_LABELS[code] ?? code;
}

/** SHA-256 abreviado para conferência visual (o completo fica no título). */
export function shortHash(sha: string | null | undefined): string {
  return sha ? `${sha.slice(0, 12)}…${sha.slice(-6)}` : '—';
}
