import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { CreditorActivationStart, CreditorLoginResponse, CreditorMe, CreditorSite } from './creditor.models';

export const CREDITOR_API = '/api/creditor/v1';
/** Rotas públicas do portal do credor: o interceptor nunca anexa token a elas. */
export const CREDITOR_PUBLIC = [`${CREDITOR_API}/auth/`, `${CREDITOR_API}/sites/`];

/**
 * Autenticação e conta do portal do credor. As telas mostram os erros no próprio formulário (sem aviso global); o
 * refresh token vai e volta só no cookie `HttpOnly` próprio.
 */
@Injectable({ providedIn: 'root' })
export class CreditorApi {
  private readonly http = inject(HttpClient);

  site(slug: string): Observable<CreditorSite> {
    return this.http.get<CreditorSite>(`${CREDITOR_API}/sites/${encodeURIComponent(slug)}`, { context: silentErrorContext() });
  }

  login(tenant: string, email: string, password: string): Observable<CreditorLoginResponse> {
    return this.http.post<CreditorLoginResponse>(`${CREDITOR_API}/auth/login`, { tenant, email, password },
      { context: silentErrorContext() });
  }

  verifyMfa(stepToken: string, code: string): Observable<CreditorLoginResponse> {
    return this.http.post<CreditorLoginResponse>(`${CREDITOR_API}/auth/mfa`, { stepToken, code }, { context: silentErrorContext() });
  }

  changeExpiredPassword(stepToken: string, newPassword: string): Observable<CreditorLoginResponse> {
    return this.http.post<CreditorLoginResponse>(`${CREDITOR_API}/auth/password/expired`, { stepToken, newPassword },
      { context: silentErrorContext() });
  }

  startActivation(token: string): Observable<CreditorActivationStart> {
    return this.http.post<CreditorActivationStart>(`${CREDITOR_API}/auth/activation/start`, { token },
      { context: silentErrorContext() });
  }

  completeActivation(token: string, newPassword: string, code: string): Observable<CreditorLoginResponse> {
    return this.http.post<CreditorLoginResponse>(`${CREDITOR_API}/auth/activation/complete`, { token, newPassword, code },
      { context: silentErrorContext() });
  }

  refresh(): Observable<CreditorLoginResponse> {
    return this.http.post<CreditorLoginResponse>(`${CREDITOR_API}/auth/refresh`, {}, { context: silentErrorContext() });
  }

  forgotPassword(tenant: string, email: string): Observable<void> {
    return this.http.post<void>(`${CREDITOR_API}/auth/password/forgot`, { tenant, email }, { context: silentErrorContext() });
  }

  resetPassword(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${CREDITOR_API}/auth/password/reset`, { token, newPassword }, { context: silentErrorContext() });
  }

  me(): Observable<CreditorMe> {
    return this.http.get<CreditorMe>(`${CREDITOR_API}/me`);
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${CREDITOR_API}/session/logout`, {}, { context: silentErrorContext() });
  }
}
