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

export interface PricePlanView {
  id: string;
  code: string;
  version: number;
  creditorId: string | null;
  name: string;
  currency: string;
  effectiveFrom: string;
  status: 'DRAFT' | 'PUBLISHED';
  monthlyFee: number;
  includedRequests: number;
  pricePerRequest: number;
  includedMbIn: number;
  pricePerMbIn: number;
  includedMbOut: number;
  pricePerMbOut: number;
  includedRecords: number;
  pricePerRecord: number;
  publishedAt: string | null;
  publishedBy: string | null;
}

export type PricePlanRequest = Omit<PricePlanView, 'id' | 'version' | 'currency' | 'status' | 'publishedAt' | 'publishedBy'>;

export interface StatementLine {
  metric: 'MONTHLY_FEE' | 'REQUESTS' | 'MB_IN' | 'MB_OUT' | 'RECORDS';
  quantity: number;
  included: number;
  billable: number;
  unitPrice: number;
  amount: number;
}

export interface StatementView {
  id: string | null;
  creditorId: string;
  periodStart: string;
  revision: number;
  pricePlanId: string | null;
  pricePlanCode: string | null;
  pricePlanVersion: number | null;
  requests: number;
  billableRequests: number;
  bytesIn: number;
  bytesOut: number;
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
