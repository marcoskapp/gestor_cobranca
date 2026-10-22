/** Rótulos e tons visuais dos estados de cada conceito do backend. */

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

export interface StatusDescriptor {
  label: string;
  tone: StatusTone;
}

export type StatusDomain = 'user' | 'creditor' | 'tenant' | 'contract' | 'portfolio' | 'session' | 'active';

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
