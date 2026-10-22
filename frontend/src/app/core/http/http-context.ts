import { HttpContext, HttpContextToken } from '@angular/common/http';

/** Não anexa o access token nem tenta renovar a sessão (endpoints públicos de autenticação). */
export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);

/** O chamador apresenta o erro por conta própria (ex.: mensagens inline nas telas de login). */
export const SKIP_ERROR_NOTIFICATION = new HttpContextToken<boolean>(() => false);

export function publicRequestContext(): HttpContext {
  return new HttpContext().set(SKIP_AUTH, true).set(SKIP_ERROR_NOTIFICATION, true);
}

export function silentErrorContext(): HttpContext {
  return new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
}
