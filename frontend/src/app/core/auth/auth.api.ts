import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../config/api';
import { publicRequestContext, silentErrorContext } from '../http/http-context';
import { AuthenticatedUser, LoginRequest, LoginResponse, MfaEnrollment } from './auth.models';

/** Chamadas HTTP do fluxo de autenticação (`/api/v1/auth`) e de logout (`/api/v1/me/logout`). */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/auth`;

  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/login`, request, {
      context: publicRequestContext(),
      withCredentials: true,
    });
  }

  verifyMfa(stepToken: string, code: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(
      `${this.base}/mfa/verify`,
      { stepToken, code },
      { context: publicRequestContext(), withCredentials: true },
    );
  }

  startMfaEnrollment(stepToken: string): Observable<MfaEnrollment> {
    return this.http.post<MfaEnrollment>(
      `${this.base}/mfa/enrollment`,
      { stepToken },
      { context: publicRequestContext() },
    );
  }

  confirmMfaEnrollment(stepToken: string, code: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(
      `${this.base}/mfa/enrollment/confirm`,
      { stepToken, code },
      { context: publicRequestContext(), withCredentials: true },
    );
  }

  changeExpiredPassword(stepToken: string, newPassword: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/password/expired`,
      { stepToken, newPassword },
      { context: publicRequestContext() },
    );
  }

  /** Renovação com o cookie HttpOnly `gc_refresh` (path `/api/v1/auth`). */
  refresh(): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/refresh`, null, {
      context: publicRequestContext(),
      withCredentials: true,
    });
  }

  forgotPassword(tenant: string, email: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/password/forgot`,
      { tenant, email },
      { context: publicRequestContext() },
    );
  }

  /** Conclui ativação de conta ou redefinição de senha (token recebido por e-mail). */
  completeCredentialAction(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/password/reset`,
      { token, newPassword },
      { context: publicRequestContext() },
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${API_V1}/me/logout`, null, {
      context: silentErrorContext(),
      withCredentials: true,
    });
  }

  profile(): Observable<AuthenticatedUser> {
    return this.http.get<AuthenticatedUser>(`${API_V1}/me`);
  }
}
