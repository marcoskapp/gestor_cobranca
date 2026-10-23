import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MfaEnrollment, SessionView } from '../../core/auth/auth.models';
import { API_V1 } from '../../core/config/api';
import { silentErrorContext } from '../../core/http/http-context';

/** Operações do usuário autenticado sobre a própria conta (`MeController`). */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/me`;

  /** Erros exibidos na própria tela (senha atual incorreta, política de senha). */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/password`,
      { currentPassword, newPassword },
      { context: silentErrorContext() },
    );
  }

  startMfaEnrollment(): Observable<MfaEnrollment> {
    return this.http.post<MfaEnrollment>(`${this.base}/mfa/enrollment`, null);
  }

  confirmMfaEnrollment(code: string): Observable<void> {
    return this.http.post<void>(`${this.base}/mfa/enrollment/confirm`, { code }, { context: silentErrorContext() });
  }

  sessions(): Observable<SessionView[]> {
    return this.http.get<SessionView[]>(`${this.base}/sessions`);
  }

  revokeSession(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/sessions/${id}`);
  }
}
