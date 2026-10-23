/** Antecedência da renovação proativa em relação à expiração do access token. */
export const RENEW_BEFORE_EXPIRY_MS = 60_000;

/** Intervalo mínimo até a renovação, evitando laços quando o token tem vida muito curta. */
export const MIN_RENEWAL_DELAY_MS = 10_000;

/**
 * Tempo de vida do access token, em milissegundos, a partir do recebimento.
 *
 * Usa a diferença `exp - iat` das claims do JWT, que não depende do relógio do computador do
 * usuário (relógios adiantados ou atrasados anulariam a renovação proativa). Na ausência dessas
 * claims, usa `accessTokenExpiresAt` informado pelo servidor. O token não é validado aqui: a
 * assinatura é verificada apenas pelo servidor.
 */
export function tokenLifetimeMs(accessToken: string, accessTokenExpiresAt: string | null, now = Date.now()): number {
  const claims = decodeClaims(accessToken);
  if (claims && typeof claims.exp === 'number' && typeof claims.iat === 'number' && claims.exp > claims.iat) {
    return (claims.exp - claims.iat) * 1000;
  }
  const expiresAt = accessTokenExpiresAt ? Date.parse(accessTokenExpiresAt) : Number.NaN;
  return Number.isFinite(expiresAt) ? Math.max(expiresAt - now, 0) : 0;
}

/** Atraso até a renovação proativa (~60 s antes da expiração). */
export function renewalDelayMs(lifetimeMs: number): number {
  return Math.max(lifetimeMs - RENEW_BEFORE_EXPIRY_MS, MIN_RENEWAL_DELAY_MS);
}

interface TimeClaims {
  exp?: unknown;
  iat?: unknown;
}

function decodeClaims(token: string): TimeClaims | null {
  const payload = token.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const parsed: unknown = JSON.parse(atob(padded));
    return typeof parsed === 'object' && parsed !== null ? (parsed as TimeClaims) : null;
  } catch {
    return null;
  }
}
