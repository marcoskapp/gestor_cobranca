import { ApiError, userMessage } from '../../core/http/problem';

/**
 * Mensagens das telas públicas. O servidor responde de forma genérica para não revelar se o
 * usuário existe; a interface mantém essa neutralidade.
 */
export function authErrorMessage(error: ApiError): string {
  if (error.status === 401) {
    return error.code === 'auth.invalid_token'
      ? 'A etapa de autenticação expirou. Entre novamente.'
      : 'Credenciais inválidas. Verifique a assessoria, o e-mail e a senha.';
  }
  if (error.status === 429) {
    return error.detail ?? 'Muitas tentativas. Aguarde um minuto e tente novamente.';
  }
  return userMessage(error);
}

export function isExpiredStep(error: ApiError): boolean {
  return error.status === 401 && error.code === 'auth.invalid_token';
}
