/** Prefixo de todas as chamadas à API do próprio backend (mesma origem). */
export const API_PREFIX = '/api/';

/** Versão atual da API REST. */
export const API_V1 = '/api/v1';

/** Indica se a URL pertence à API da aplicação (e não a um recurso externo). */
export function isApiRequest(url: string): boolean {
  return url.startsWith(API_PREFIX);
}

/** API do portal de autonegociação do devedor (sessão e token próprios, nunca os do usuário interno). */
export const PORTAL_API_PREFIX = '/api/portal/';

export function isPortalRequest(url: string): boolean {
  return url.startsWith(PORTAL_API_PREFIX);
}

/** Telas públicas do portal do devedor (não restauram a sessão interna). */
export function isPortalPath(path: string): boolean {
  return path === '/portal' || path.startsWith('/portal/');
}
