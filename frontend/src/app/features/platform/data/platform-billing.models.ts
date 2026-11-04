/**
 * Faturamento da plataforma às assessorias pelo consumo da integração (espelho de
 * `publicapi.application.port.in.PlatformBillingUseCases`). Valores monetários chegam como número (BigDecimal).
 */

export type PlatformMetric = 'MONTHLY_FEE' | 'REQUESTS' | 'MB_IN' | 'MB_OUT' | 'RECORDS';
export type PlatformCycleKind = 'REGULAR' | 'POLICY_START' | 'POLICY_END' | 'TRANSITION' | 'NO_POLICY';

/** Faixa de volume das chamadas; `upTo` nulo na última. */
export interface TierView {
  upTo: number | null;
  unitPrice: number;
}

/** `PolicyTermsCommand`: `pricePerRequest` nulo quando há faixas. */
export interface PolicyTermsRequest {
  closingDay: number;
  monthlyFee: number;
  includedRequests: number;
  pricePerRequest: number | null;
  requestTiers: TierView[];
  includedMbIn: number;
  pricePerMbIn: number;
  includedMbOut: number;
  pricePerMbOut: number;
  includedRecords: number;
  pricePerRecord: number;
}

export interface PolicyVersionView extends PolicyTermsRequest {
  id: string;
  version: number;
  registeredAt: string;
  registeredBy: string | null;
  reason: string | null;
  appliesToCurrentCycle: boolean;
}

export interface PolicyView {
  tenantId: string;
  policyId: string | null;
  startDate: string | null;
  endDate: string | null;
  versions: PolicyVersionView[];
  hasStatements: boolean;
}

export interface TierChargeView {
  from: number;
  to: number | null;
  units: number;
  unitPrice: number;
  amount: number;
}

export interface ChargeLineView {
  metric: PlatformMetric;
  quantity: number;
  included: number;
  billable: number;
  unitPrice: number | null;
  amount: number;
  tiers: TierChargeView[];
}

export interface BreakdownView {
  kind: 'CREDITOR' | 'TOKEN' | 'ROUTE';
  key: string;
  label: string | null;
  creditorId: string | null;
  totals: Record<string, number>;
}

export interface AllowanceUse {
  metric: PlatformMetric;
  used: number;
  included: number;
  percent: number | null;
}

export interface DailyPoint {
  day: number;
  date: string;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  amount: number;
}

export interface ProjectionView {
  method: string;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  amount: number;
}

export interface StatementRef {
  id: string;
  periodStart: string;
  periodEnd: string;
  revision: number;
  totalAmount: number;
}

export interface PlatformStatementView {
  id: string | null;
  tenantId: string;
  periodStart: string;
  periodEnd: string;
  ended: boolean;
  revision: number;
  closingDay: number;
  cycleDays: number;
  referenceCycleDays: number;
  cycleKind: PlatformCycleKind;
  policyVersion: number;
  requests: number;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  creditors: number;
  tokens: number;
  totalAmount: number;
  currency: string;
  lines: ChargeLineView[];
  breakdown: BreakdownView[];
  usageHash: string;
  closedAt: string | null;
  closedBy: string | null;
  reason: string | null;
}

export interface PlatformPositionView {
  tenantId: string;
  hasPolicy: boolean;
  periodStart: string;
  periodEnd: string;
  closingDay: number;
  cycleDays: number;
  referenceCycleDays: number;
  cycleKind: PlatformCycleKind;
  today: string;
  daysElapsed: number;
  daysRemaining: number;
  policyVersion: number | null;
  policyRegisteredAt: string | null;
  requests: number;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  creditors: number;
  tokens: number;
  amountSoFar: number;
  currency: string;
  lines: ChargeLineView[];
  allowances: AllowanceUse[];
  breakdown: BreakdownView[];
  daily: DailyPoint[];
  projection: ProjectionView;
  lastStatement: StatementRef | null;
  updatedAt: string | null;
  flushIntervalSeconds: number;
  calculationMillis: number;
}

export interface TenantSummary {
  tenantId: string;
  slug: string;
  tradeName: string;
  hasPolicy: boolean;
  periodStart: string;
  periodEnd: string;
  daysElapsed: number;
  cycleDays: number;
  policyVersion: number | null;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
  tokens: number;
  amountSoFar: number;
  projectedAmount: number;
  lastStatement: StatementRef | null;
  rank: number;
}

export interface OverviewTotals {
  tenants: number;
  withPolicy: number;
  withoutPolicy: number;
  amountSoFar: number;
  projectedAmount: number;
  lastClosedAmount: number;
  billableRequests: number;
  billableMbIn: number;
  billableMbOut: number;
  records: number;
}

export interface OverviewView {
  today: string;
  totals: OverviewTotals;
  tenants: TenantSummary[];
  calculationMillis: number;
}

export interface CreatePolicyRequest {
  startDate: string;
  endDate: string | null;
  terms: PolicyTermsRequest;
  reason: string | null;
}
