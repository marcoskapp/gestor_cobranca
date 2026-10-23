import { AuthenticatedUser, LoginResponse } from '../app/core/auth/auth.models';

export function fakeUser(overrides: Partial<AuthenticatedUser> = {}): AuthenticatedUser {
  return {
    id: 'u-1',
    tenantId: 't-1',
    tenantSlug: 'assessoria-teste',
    tenantName: 'Assessoria Teste',
    name: 'Maria Operadora',
    email: 'maria@teste.com.br',
    roles: ['OPERADOR'],
    permissions: ['USER_VIEW'],
    allCreditors: true,
    creditorIds: [],
    mfaEnabled: false,
    ...overrides,
  };
}

/** JWT não assinado apenas com `iat`/`exp` (o cliente nunca valida assinatura). */
export function fakeJwt(lifetimeSeconds = 600, iat = 1_700_000_000): string {
  const encode = (value: object): string =>
    btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none' })}.${encode({ iat, exp: iat + lifetimeSeconds, sub: 'u-1' })}.sig`;
}

export function authenticated(overrides: Partial<LoginResponse> = {}, lifetimeSeconds = 600): LoginResponse {
  return {
    status: 'AUTHENTICATED',
    accessToken: fakeJwt(lifetimeSeconds),
    accessTokenExpiresAt: new Date(Date.now() + lifetimeSeconds * 1000).toISOString(),
    stepToken: null,
    user: fakeUser(),
    ...overrides,
  };
}

export function step(status: LoginResponse['status'], stepToken = 'step-123'): LoginResponse {
  return { status, accessToken: null, accessTokenExpiresAt: null, stepToken, user: null };
}
