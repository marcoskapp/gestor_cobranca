/**
 * Regras de tela das contestações (funções puras, testadas em `dispute.rules.spec.ts`). As decisões são do servidor;
 * aqui só se escolhe o que mostrar e para onde levar o usuário.
 */
import { AdjustmentAction, DisputeFilter, DisputeOutcome, DisputeType, DisputeView } from '../data/collection.models';
import { daysBetween } from '../data/collection.rules';
import { DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { UrlCodec, UrlCodecs, urlParam } from '../../../shared/data/url-state';
import { statusOptions } from '../../../shared/status/status';

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
  /** Com resposta do credor por e-mail a confirmar. */
  mailReplyPending?: boolean;
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
  if (v.mailReplyPending) {
    filter.mailReplyPending = true;
  }
  return filter;
}

export type DisputeListFormValue = Required<DisputeListForm>;

/** Filtros padrão da fila (ficam fora da URL). */
export const DISPUTE_FORM_DEFAULTS: DisputeListFormValue = {
  creditor: [],
  status: 'PENDING',
  type: '',
  assignee: '',
  due: '',
  awaiting: false,
  noticePending: false,
  adjustmentPending: false,
  mailReplyPending: false,
};

/**
 * Filtros aplicados (diferentes do padrão da fila), para o botão "Filtros (n)" do `app-filter-bar` no celular
 * (docs/tecnico/desenho/ux.md §12.3).
 */
/** Opção do formulário ↔ valor curto em português na URL. */
function mappedParam(param: string, values: Record<string, string>): UrlCodec<string> {
  return {
    param,
    parse: (raw) => Object.entries(values).find(([, v]) => v === raw)?.[0],
    format: (value) => values[value] ?? null,
  };
}

const ALL_STATUSES = 'todas';

/**
 * Filtros da fila na URL (`?credor=&situacao=&motivo=&responsavel=&prazo=&aguardando=1&resposta-pendente=1&
 * ajuste-pendente=1&email-pendente=1`). A situação padrão é "Pendentes"; "Todas" vira `situacao=todas`.
 */
export const DISPUTE_URL_CODECS: UrlCodecs<DisputeListFormValue> = {
  creditor: urlParam.ids('credor'),
  status: {
    param: 'situacao',
    parse: (raw) => (raw === ALL_STATUSES ? '' : raw === 'PENDING' || statusOptions('dispute').some((s) => s.value === raw) ? raw : undefined),
    format: (value) => value || ALL_STATUSES,
  },
  type: urlParam.choice('motivo', Object.keys(DISPUTE_TYPE_LABELS)),
  assignee: mappedParam('responsavel', { me: 'me', none: 'nenhum', supervision: 'supervisao' }),
  due: mappedParam('prazo', { overdue: 'vencido', week: 'semana' }),
  awaiting: urlParam.flag('aguardando'),
  noticePending: urlParam.flag('resposta-pendente'),
  adjustmentPending: urlParam.flag('ajuste-pendente'),
  mailReplyPending: urlParam.flag('email-pendente'),
};

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

export function adjustmentShortcut(action: AdjustmentAction, titleIds: string[], protocol: string, disputeId: string): AdjustmentShortcut | null {
  if (!titleIds.length) {
    return null;
  }
  if (action === 'SETTLE_BY_PAYMENT') {
    return { kind: 'payment', titleIds };
  }
  const motivo = `Contestação ${protocol} procedente`;
  return {
    kind: 'title',
    titleId: titleIds[0],
    // corrigir o valor: abre a correção manual vinculada à contestação (decisão 811)
    queryParams: action === 'CANCEL_TITLES' ? { acao: 'cancelar', motivo } : { acao: 'corrigir-valor', contestacao: disputeId, motivo },
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
