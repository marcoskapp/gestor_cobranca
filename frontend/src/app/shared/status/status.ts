/** Rótulos e tons visuais dos estados de cada conceito do backend. */

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

export interface StatusDescriptor {
  label: string;
  tone: StatusTone;
}

export type StatusDomain =
  | 'user'
  | 'creditor'
  | 'tenant'
  | 'contract'
  | 'portfolio'
  | 'session'
  | 'active'
  | 'debtor'
  | 'title'
  | 'contact'
  | 'restriction'
  | 'dataSubjectRequest'
  | 'retentionRun'
  | 'retentionItem'
  | 'legalBasis'
  | 'collateral'
  | 'case'
  | 'task'
  | 'promise'
  | 'dispute'
  | 'collectionPolicy'
  | 'negotiationOutcome';

const STATUS: Record<StatusDomain, Record<string, StatusDescriptor>> = {
  user: {
    PENDING_ACTIVATION: { label: 'Aguardando ativação', tone: 'info' },
    ACTIVE: { label: 'Ativo', tone: 'success' },
    DISABLED: { label: 'Desativado', tone: 'neutral' },
  },
  creditor: {
    ACTIVE: { label: 'Ativo', tone: 'success' },
    INACTIVE: { label: 'Inativo', tone: 'neutral' },
  },
  tenant: {
    ACTIVE: { label: 'Ativa', tone: 'success' },
    SUSPENDED: { label: 'Suspensa', tone: 'danger' },
  },
  contract: {
    ACTIVE: { label: 'Vigente', tone: 'success' },
    TERMINATED: { label: 'Encerrado', tone: 'neutral' },
  },
  portfolio: {
    DRAFT: { label: 'Em preparação', tone: 'info' },
    ACTIVE: { label: 'Ativa', tone: 'success' },
    SUSPENDED: { label: 'Suspensa', tone: 'warning' },
    RETURNED: { label: 'Devolvida', tone: 'neutral' },
    CLOSED: { label: 'Encerrada', tone: 'neutral' },
  },
  session: {
    ACTIVE: { label: 'Ativa', tone: 'success' },
    REVOKED: { label: 'Revogada', tone: 'neutral' },
    EXPIRED: { label: 'Expirada', tone: 'neutral' },
  },
  active: {
    true: { label: 'Ativo', tone: 'success' },
    false: { label: 'Inativo', tone: 'neutral' },
  },
  debtor: {
    ACTIVE: { label: 'Ativo', tone: 'success' },
    ANONYMIZED: { label: 'Anonimizado', tone: 'neutral' },
  },
  title: {
    OPEN: { label: 'Em cobrança', tone: 'info' },
    SUSPENDED: { label: 'Suspenso', tone: 'warning' },
    SETTLED: { label: 'Quitado', tone: 'success' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
    RETURNED: { label: 'Devolvido', tone: 'neutral' },
  },
  contact: {
    VALID: { label: 'Válido', tone: 'success' },
    UNVERIFIED: { label: 'Não verificado', tone: 'info' },
    INVALID: { label: 'Inválido', tone: 'danger' },
  },
  restriction: {
    ACTIVE: { label: 'Ativa', tone: 'danger' },
    REVOKED: { label: 'Revogada', tone: 'neutral' },
  },
  dataSubjectRequest: {
    RECEIVED: { label: 'Recebida', tone: 'info' },
    IDENTITY_VERIFIED: { label: 'Identidade confirmada', tone: 'info' },
    IN_PROGRESS: { label: 'Em atendimento', tone: 'warning' },
    COMPLETED: { label: 'Concluída', tone: 'success' },
    REJECTED: { label: 'Recusada', tone: 'neutral' },
  },
  retentionRun: {
    RUNNING: { label: 'Em execução', tone: 'info' },
    COMPLETED: { label: 'Concluída', tone: 'success' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
  retentionItem: {
    ELIGIBLE: { label: 'Elegível', tone: 'info' },
    ANONYMIZED: { label: 'Anonimizado', tone: 'success' },
    RETAINED: { label: 'Mantido (base legal)', tone: 'warning' },
    FAILED: { label: 'Falhou', tone: 'danger' },
  },
  /** Vigência calculada na tela a partir de `validFrom`/`validUntil` (`legalBasisPeriod`). */
  /** `CollateralStatus` do módulo debt. */
  collateral: {
    ACTIVE: { label: 'Vigente', tone: 'success' },
    UNDER_ENFORCEMENT: { label: 'Em execução', tone: 'warning' },
    SEIZED: { label: 'Apreendida', tone: 'warning' },
    CONSOLIDATED: { label: 'Propriedade consolidada', tone: 'info' },
    AUCTIONED: { label: 'Leiloada', tone: 'info' },
    RELEASED: { label: 'Liberada', tone: 'neutral' },
    WRITTEN_OFF: { label: 'Baixada', tone: 'neutral' },
  },
  /** `CaseStatus` do módulo collection. */
  case: {
    OPEN: { label: 'Em aberto', tone: 'info' },
    IN_NEGOTIATION: { label: 'Em negociação', tone: 'info' },
    PROMISE: { label: 'Promessa', tone: 'warning' },
    AGREEMENT: { label: 'Acordo', tone: 'success' },
    DISPUTED: { label: 'Contestado', tone: 'danger' },
    SUSPENDED: { label: 'Suspenso', tone: 'warning' },
    LEGAL: { label: 'Jurídico', tone: 'neutral' },
    CLOSED: { label: 'Encerrado', tone: 'neutral' },
  },
  /** Tarefas e retornos (`TaskStatus`). */
  task: {
    PENDING: { label: 'Agendado', tone: 'info' },
    MISSED: { label: 'Atrasado', tone: 'danger' },
    DONE: { label: 'Concluído', tone: 'success' },
    CANCELLED: { label: 'Cancelado', tone: 'neutral' },
  },
  promise: {
    PENDING: { label: 'Aguardando pagamento', tone: 'warning' },
    KEPT: { label: 'Cumprida', tone: 'success' },
    BROKEN: { label: 'Quebrada', tone: 'danger' },
    CANCELLED: { label: 'Cancelada', tone: 'neutral' },
  },
  dispute: {
    OPEN: { label: 'Aberta', tone: 'warning' },
    UNDER_REVIEW: { label: 'Em análise', tone: 'info' },
    RESOLVED: { label: 'Resolvida', tone: 'success' },
    WITHDRAWN: { label: 'Retirada', tone: 'neutral' },
  },
  /** Versões da política de cobrança e negociação (`PolicyStatus`). */
  collectionPolicy: {
    DRAFT: { label: 'Rascunho', tone: 'info' },
    PUBLISHED: { label: 'Publicada', tone: 'success' },
    RETIRED: { label: 'Aposentada', tone: 'neutral' },
  },
  /** Resultado do `NegotiationGuard` (`/validations`). */
  negotiationOutcome: {
    WITHIN_AUTHORITY: { label: 'Dentro da alçada', tone: 'success' },
    REQUIRES_APPROVAL: { label: 'Requer aprovação', tone: 'warning' },
    REJECTED: { label: 'Rejeitado', tone: 'danger' },
  },
  legalBasis: {
    CURRENT: { label: 'Vigente', tone: 'success' },
    FUTURE: { label: 'Futura', tone: 'info' },
    ENDED: { label: 'Encerrada', tone: 'neutral' },
  },
};

export function describeStatus(domain: StatusDomain, status: string | boolean | null | undefined): StatusDescriptor {
  const key = String(status);
  return STATUS[domain][key] ?? { label: key, tone: 'neutral' };
}

export function statusOptions(domain: StatusDomain): { value: string; label: string }[] {
  return Object.entries(STATUS[domain]).map(([value, d]) => ({ value, label: d.label }));
}

/** Transições permitidas de carteira (espelho de `PortfolioStatus.allowedTransitions`). */
export const PORTFOLIO_TRANSITIONS: Record<string, readonly string[]> = {
  DRAFT: ['ACTIVE', 'CLOSED'],
  ACTIVE: ['SUSPENDED', 'RETURNED', 'CLOSED'],
  SUSPENDED: ['ACTIVE', 'RETURNED', 'CLOSED'],
  RETURNED: ['CLOSED'],
  CLOSED: [],
};

/** Transições que exigem motivo (`Portfolio.transitionTo`). */
export const PORTFOLIO_REASON_REQUIRED: readonly string[] = ['SUSPENDED', 'RETURNED'];

/** Transições manuais de título (espelho de `TitleStatus.allowedTransitions`, sem as automáticas). */
export const TITLE_MANUAL_ACTIONS: Record<string, readonly ('suspend' | 'reactivate' | 'cancel' | 'return')[]> = {
  OPEN: ['suspend', 'cancel', 'return'],
  SUSPENDED: ['reactivate', 'cancel', 'return'],
  SETTLED: [],
  CANCELLED: [],
  RETURNED: [],
};

/** Situações finais de solicitação de titular (`RequestStatus.isFinal`). */
export const DSR_FINAL_STATUSES: readonly string[] = ['COMPLETED', 'REJECTED'];
