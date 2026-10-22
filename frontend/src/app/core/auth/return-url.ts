const AUTH_PATHS = ['/login', '/esqueci-a-senha', '/redefinir-senha', '/ativar-conta'];

/**
 * Aceita somente caminhos internos da aplicação como destino após o login (impede redirecionamento
 * aberto para outro domínio e retorno às próprias telas de autenticação).
 */
export function safeReturnUrl(candidate: string | null | undefined): string {
  if (!candidate || !candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) {
    return '/';
  }
  const path = candidate.split(/[?#]/)[0];
  if (AUTH_PATHS.some((p) => path === p || path.startsWith(`${p}/`))) {
    return '/';
  }
  return candidate;
}
