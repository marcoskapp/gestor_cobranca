/** Contratos da API do portal do credor (`/api/creditor/v1`, ADR 0011). */

export type CreditorLoginStatus = 'AUTHENTICATED' | 'MFA_REQUIRED' | 'PASSWORD_CHANGE_REQUIRED';

export interface CreditorMe {
  userId: string;
  name: string;
  email: string;
  role: string;
  roleLabel: string;
  permissions: string[];
  creditorId: string;
  creditorName: string;
  tenantSlug: string;
  tenantName: string;
  lastLoginAt: string | null;
}

export interface CreditorLoginResponse {
  status: CreditorLoginStatus;
  accessToken: string | null;
  accessTokenExpiresAt: string | null;
  stepToken: string | null;
  me: CreditorMe | null;
}

export interface CreditorActivationStart {
  name: string;
  account: string;
  creditorName: string;
  tenantName: string;
  secret: string;
  otpAuthUri: string;
}

export interface CreditorSite {
  slug: string;
  name: string;
}

/** Permissões do portal (espelho de `Permission.CREDITOR_ONLY`). */
export const CreditorPermission = {
  OVERVIEW: 'CREDITOR_PORTAL_OVERVIEW',
  STATEMENT_RESPOND: 'CREDITOR_STATEMENT_RESPOND',
  DISPUTE_RESPOND: 'CREDITOR_DISPUTE_RESPOND',
  PAYMENT_REPORT: 'CREDITOR_PAYMENT_REPORT',
  ACTIVITY_VIEW: 'CREDITOR_ACTIVITY_VIEW',
} as const;

/** Situação do demonstrativo visível ao credor (só os já enviados). */
export type CreditorStatementStatus = 'SENT' | 'ACKNOWLEDGED' | 'CONTESTED';
/** Quem paga quem: a assessoria repassa ao credor, o credor paga à assessoria, ou nada a pagar. */
export type TransferDirection = 'AGENCY_TO_CREDITOR' | 'CREDITOR_TO_AGENCY' | 'NONE';

export interface CreditorStatementItem {
  id: string;
  sequenceNumber: number;
  periodStart: string;
  periodEnd: string;
  status: CreditorStatementStatus;
  recovered: number;
  netTransfer: number;
  direction: TransferDirection;
  sentAt: string | null;
  respondedAt: string | null;
}

export interface CreditorStatementTotals {
  receivedByAgency: number;
  receivedByCreditor: number;
  reversed: number;
  recovered: number;
  agencyHonorarium: number;
  commission: number;
  fixedFees: number;
  expenses: number;
  adjustments: number;
  netTransfer: number;
}

export interface CreditorStatementFile {
  id: string;
  format: 'CSV' | 'XLSX' | 'PDF';
  fileName: string;
  size: number;
  sha256: string;
  createdAt: string;
}

export interface CreditorStatementDetail {
  statement: CreditorStatementItem;
  totals: CreditorStatementTotals;
  files: CreditorStatementFile[];
  transferred: number;
  outstanding: number;
  responseNote: string | null;
  /** A resposta foi dada pela própria pessoa do credor no portal (e não registrada pela assessoria). */
  respondedInPortal: boolean;
}

export interface CreditorOpenPeriod {
  periodStart: string;
  asOf: string;
  expectedPeriodEnd: string | null;
  recovered: number;
  netTransfer: number;
  direction: TransferDirection;
  calculatedAt: string;
}
