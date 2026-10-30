/**
 * Contratos das telas da API pública (módulo publicapi: `IntegrationTokenUseCases`, `PublicApiUsageUseCases`,
 * `PublicApiBillingUseCases`). O segredo do token só existe na resposta da criação e da rotação.
 */

export type TokenState = 'ACTIVE' | 'REVOKED' | 'EXPIRED';

export interface TokenView {
  id: string;
  creditorId: string;
  name: string;
  description: string | null;
  /** Identificação pública: `gck_xxxxxxxxxxxx`. */
  prefix: string;
  scopes: string[];
  rateLimitPerMinute: number;
  allowedIps: string[];
  expiresAt: string | null;
  state: TokenState;
  createdBy: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  lastUsedIp: string | null;
  revokedAt: string | null;
  revokedBy: string | null;
  revokeReason: string | null;
  rotatedAt: string | null;
  rotationCount: number;
  /** Fim da carência do segredo anterior (rotação). */
  previousValidUntil: string | null;
}

/** Resposta da criação/rotação: `token` é exibido uma única vez. */
export interface IssuedToken {
  details: TokenView;
  token: string;
}

export interface ScopeView {
  code: string;
  description: string;
}

export interface CreateTokenRequest {
  creditorId: string;
  name: string;
  description: string | null;
  scopes: string[];
  rateLimitPerMinute: number | null;
  allowedIps: string[];
  expiresAt: string | null;
}

export interface UpdateTokenRequest {
  name: string;
  description: string | null;
  rateLimitPerMinute: number;
  allowedIps: string[];
}

export interface TokenFilter {
  creditorId: string | null;
  status: TokenState | null;
}

export interface UsageTotals {
  requests: number;
  billableRequests: number;
  throttled: number;
  clientErrors: number;
  serverErrors: number;
  bytesIn: number;
  bytesOut: number;
  records: number;
}

export interface UsageReport {
  from: string;
  to: string;
  totals: UsageTotals;
  daily: { day: string; totals: UsageTotals }[];
  byRoute: { method: string; route: string; totals: UsageTotals }[];
  byToken: { tokenId: string; prefix: string | null; name: string | null; creditorId: string; totals: UsageTotals }[];
}

export interface UsageFilter {
  from: string;
  to: string;
  creditorId: string | null;
  tokenId: string | null;
}

export interface StatementLine {
  metric: 'MONTHLY_FEE' | 'REQUESTS' | 'MB_IN' | 'MB_OUT' | 'RECORDS';
  /** Quantidade inteira do ciclo (chamadas, MB, registros; 1 para a mensalidade). */
  quantity: number;
  included: number;
  billable: number;
  unitPrice: number;
  amount: number;
}

/** `PublicApiBillingUseCases.StatementView`: extrato de um ciclo de consumo (prévia quando `id` é nulo). */
export interface StatementView {
  id: string | null;
  creditorId: string;
  periodStart: string;
  periodEnd: string;
  ended: boolean;
  revision: number;
  usageClosingDay: number;
  /** Dias do ciclo e do ciclo regular de referência; diferentes no primeiro e no último ciclo do contrato e no de transição (valores proporcionais). */
  cycleDays: number;
  referenceCycleDays: number;
  contractId: string | null;
  contractNumber: string | null;
  pricingId: string | null;
  pricingVersion: number | null;
  requests: number;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  totalAmount: number;
  currency: string;
  lines: StatementLine[];
  breakdown: { kind: 'TOKEN' | 'ROUTE'; key: string; label: string | null; totals: Record<string, number> }[];
  usageHash: string;
  closedAt: string | null;
  closedBy: string | null;
  reason: string | null;
}

/** `PublicApiBillingUseCases.GrowthPoint`: acumulado até o fim do dia do ciclo. */
export interface GrowthPoint {
  day: number;
  date: string;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  amount: number;
}

export type BillingCycleKind = 'REGULAR' | 'CONTRACT_START' | 'CONTRACT_END' | 'TRANSITION' | 'NO_CONTRACT';

/** `PublicApiBillingUseCases.BillingPositionView`: ciclo de consumo em andamento. */
export interface BillingPositionView {
  creditorId: string;
  periodStart: string;
  periodEnd: string;
  usageClosingDay: number;
  cycleDays: number;
  referenceCycleDays: number;
  /** Tipo do ciclo (decisão 624): primeiro e último do contrato e de transição são proporcionais. */
  cycleKind: BillingCycleKind;
  today: string;
  daysElapsed: number;
  daysRemaining: number;
  contractNumber: string | null;
  pricingVersion: number | null;
  requests: number;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  /** Mensalidade do ciclo (cheia ou proporcional no primeiro, no último e no de transição) + excedente do consumo até agora. */
  amountSoFar: number;
  currency: string;
  lines: StatementLine[];
  allowances: { metric: StatementLine['metric']; used: number; included: number; percent: number | null }[];
  breakdown: StatementView['breakdown'];
  daily: GrowthPoint[];
  previous: { periodStart: string; periodEnd: string; statementId: string | null; totalAmount: number };
  previousDaily: GrowthPoint[];
  projection: { method: 'DAILY_AVERAGE'; billableRequests: number; billableMbIn: number; billableMbOut: number; records: number; amount: number };
  updatedAt: string | null;
  flushIntervalSeconds: number;
  calculationMillis: number;
}
