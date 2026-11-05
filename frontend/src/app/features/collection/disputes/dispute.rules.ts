/**
 * Regras de tela das contestações (funções puras, testadas em `dispute.rules.spec.ts`). As decisões são do servidor;
 * aqui só se escolhe o que mostrar e para onde levar o usuário.
 */
import { AdjustmentAction, DisputeFilter, DisputeOutcome, DisputeType, DisputeView } from '../data/collection.models';
import { daysBetween } from '../data/collection.rules';

export type DisputeAction = 'review' | 'resolve' | 'withdraw';

/** Ações do ciclo de vida disponíveis pela situação da contestação. */
export function disputeActions(status: string): DisputeAction[] {
  if (status === 'OPEN') {
    return ['review', 'resolve', 'withdraw'];
  }
  return status === 'UNDER_REVIEW' ? ['resolve', 'withdraw'] : [];
}

export function isPending(d: Pick<DisputeView, 'status'>): boolean {
  return d.status === 'OPEN' || d.status === 'UNDER_REVIEW';
}

/** Prazo que vence primeiro: o interno (SLA da equipe) ou o de resposta ao devedor. */
export function nearestDue(d: Pick<DisputeView, 'internalDueDate' | 'responseDueDate'>): string {
  return d.internalDueDate && d.internalDueDate < d.responseDueDate ? d.internalDueDate : d.responseDueDate;
}

export type DueTone = 'late' | 'soon' | 'ok';

export function dueTone(d: Pick<DisputeView, 'internalDueDate' | 'responseDueDate' | 'overdue' | 'status'>, today: string): DueTone {
  if (!isPending(d)) {
    return 'ok';
  }
  const days = daysBetween(today, nearestDue(d));
  if (d.overdue || days < 0) {
    return 'late';
  }
  return days <= 2 ? 'soon' : 'ok';
}

/** O usuário pode trabalhar a contestação: gestor, o responsável, ou quem trabalha contestações quando está sem responsável. */
export function canWorkDispute(d: Pick<DisputeView, 'assigneeId'>, userId: string | null, canManage: boolean, canWork: boolean): boolean {
  return canManage || (canWork && (d.assigneeId === null || d.assigneeId === userId));
}

export interface DisputeListForm {
  creditor: string[];
  status: string;
  type: string;
  assignee: string;
  due: string;
  awaiting: boolean;
  noticePending: boolean;
  adjustmentPending: boolean;
}

/**
 * Filtros da tela para a API. `assignee`: '' (todos), 'me', 'none' (sem responsável) ou 'supervision' (sem
 * responsável ou vencidas); `due`: '' (qualquer), 'overdue' (resposta vencida) ou 'week' (vence em até 7 dias).
 */
export function toDisputeFilter(v: Partial<DisputeListForm> & { creditor: string[]; status: string; overdue?: boolean }, today = ''): DisputeFilter {
  const filter: DisputeFilter = {
    creditorId: v.creditor[0] ?? null,
    status: v.status === 'PENDING' ? 'OPEN,UNDER_REVIEW' : v.status || null,
    overdue: v.overdue || v.due === 'overdue' ? true : null,
  };
  if (v.type) {
    filter.type = v.type;
  }
  if (v.assignee === 'me') {
    filter.assignee = 'me';
  } else if (v.assignee === 'none') {
    filter.unassigned = true;
  } else if (v.assignee === 'supervision') {
    filter.supervision = true;
  }
  if (v.due === 'week' && today) {
    filter.dueUntil = addDaysIso(today, 7);
  }
  if (v.awaiting) {
    filter.awaitingCreditor = true;
  }
  if (v.noticePending) {
    filter.noticePending = true;
  }
  if (v.adjustmentPending) {
    filter.adjustmentPending = true;
  }
  return filter;
}

/**
 * Filtro inicial a partir da URL ("Minhas pendências"): `responsavel=me`, `fila=supervisao`, `resposta=pendente`,
 * `ajuste=pendente`, `vencidas=true`.
 */
export function formFromQuery(params: { get(name: string): string | null }): Partial<DisputeListForm> & { overdue: boolean } {
  const notice = params.get('resposta') === 'pendente';
  const adjustment = params.get('ajuste') === 'pendente';
  const assignee = params.get('fila') === 'supervisao' ? 'supervision' : params.get('responsavel') === 'me' ? 'me' : '';
  return {
    status: notice || adjustment ? 'RESOLVED' : 'PENDING',
    assignee,
    noticePending: notice,
    adjustmentPending: adjustment,
    overdue: params.get('vencidas') === 'true',
  };
}

/** Ajuste sugerido pelo tipo e pelo resultado (mesma regra do servidor); nulo quando o resultado não pede ajuste. */
export function suggestedAdjustment(type: DisputeType, outcome: DisputeOutcome | '' | null): AdjustmentAction | null {
  if (!outcome || outcome === 'REJECTED') {
    return null;
  }
  if (type === 'ALREADY_PAID') {
    return 'SETTLE_BY_PAYMENT';
  }
  if (type === 'WRONG_AMOUNT') {
    return 'ADJUST_AMOUNT';
  }
  return outcome === 'PARTIALLY_UPHELD' ? 'ADJUST_AMOUNT' : 'CANCEL_TITLES';
}

/** Destino do atalho do ajuste sugerido no fluxo existente do módulo de dívidas/pagamentos. */
export type AdjustmentShortcut =
  | { kind: 'title'; titleId: string; queryParams: Record<string, string> }
  | { kind: 'payment'; titleIds: string[] };

export function adjustmentShortcut(action: AdjustmentAction, titleIds: string[], protocol: string): AdjustmentShortcut | null {
  if (!titleIds.length) {
    return null;
  }
  if (action === 'SETTLE_BY_PAYMENT') {
    return { kind: 'payment', titleIds };
  }
  return {
    kind: 'title',
    titleId: titleIds[0],
    queryParams: action === 'CANCEL_TITLES' ? { acao: 'cancelar', motivo: `Contestação ${protocol} procedente` } : { contestacao: protocol },
  };
}

/** Protocolo exibido ao credor e ao devedor (mesma regra do servidor). */
export function protocolOf(id: string): string {
  return id.slice(0, 8).toUpperCase();
}

/** O texto do pedido ainda tem variáveis do modelo além das que o sistema preenche no envio. */
export function unresolvedPlaceholders(text: string, filledBySystem: readonly string[] = ['link_evidencias', 'validade_link']): string[] {
  return [...text.matchAll(/\{\{\s*([a-z_]+)\s*}}/g)].map((m) => m[1]).filter((v) => !filledBySystem.includes(v));
}

function addDaysIso(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
