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
