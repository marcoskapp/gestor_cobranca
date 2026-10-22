/** Contratos de `AuthController`, `MeController` e `AuthenticationUseCases` do backend. */

export type LoginStatus =
  | 'AUTHENTICATED'
  | 'MFA_REQUIRED'
  | 'MFA_ENROLLMENT_REQUIRED'
  | 'PASSWORD_CHANGE_REQUIRED';

/** Etapa intermediária do login que exige um step token. */
export type AuthStep = Exclude<LoginStatus, 'AUTHENTICATED'>;

/** `AuthenticationUseCases.AuthenticatedUserView`. */
export interface AuthenticatedUser {
  id: string;
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  name: string;
  email: string;
  roles: string[];
  permissions: string[];
  allCreditors: boolean;
  creditorIds: string[];
  mfaEnabled: boolean;
}

export interface LoginRequest {
  tenant: string;
  email: string;
  password: string;
}

/** `AuthController.LoginResponse`. O refresh token nunca vem no corpo (cookie HttpOnly). */
export interface LoginResponse {
  status: LoginStatus;
  accessToken: string | null;
  accessTokenExpiresAt: string | null;
  stepToken: string | null;
  user: AuthenticatedUser | null;
}

/** `AuthenticationUseCases.MfaEnrollment`. */
export interface MfaEnrollment {
  secret: string;
  otpauthUri: string;
}

/** `SessionUseCases.SessionView`. */
export interface SessionView {
  id: string;
  status: string;
  current: boolean;
  mfaVerified: boolean;
  startedAt: string;
  lastSeenAt: string;
  absoluteExpiresAt: string;
  clientIp: string | null;
  userAgent: string | null;
}

/** Motivo pelo qual a sessão terminou, exibido na tela de login. */
export type SessionEndReason = 'logout' | 'expired' | 'password-changed';
