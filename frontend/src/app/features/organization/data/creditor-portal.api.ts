import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';

/** Perfil da pessoa do credor no portal do credor (fixo no servidor — decisão 1396). */
export interface CreditorPortalRoleOption {
  code: string;
  label: string;
}

export type CreditorPortalUserStatus = 'INVITED' | 'ACTIVE' | 'REVOKED';

export interface CreditorPortalUserView {
  id: string;
  name: string;
  email: string;
  role: string;
  roleLabel: string;
  status: CreditorPortalUserStatus;
  invitedAt: string;
  lastLoginAt: string | null;
  revokedAt: string | null;
  locked: boolean;
}

export interface CreditorPortalView {
  creditorId: string;
  enabled: boolean;
  enabledAt: string | null;
  users: CreditorPortalUserView[];
  roles: CreditorPortalRoleOption[];
}

/** Portal do credor na ficha do credor (`CREDITOR_PORTAL_MANAGE` — ADR 0011). */
@Injectable({ providedIn: 'root' })
export class CreditorPortalApi {
  private readonly http = inject(HttpClient);

  private base(creditorId: string): string {
    return `${API_V1}/creditors/${encodeURIComponent(creditorId)}/portal`;
  }

  status(creditorId: string): Observable<CreditorPortalView> {
    return this.http.get<CreditorPortalView>(this.base(creditorId));
  }

  enable(creditorId: string): Observable<CreditorPortalView> {
    return this.http.post<CreditorPortalView>(`${this.base(creditorId)}/enable`, {});
  }

  disable(creditorId: string): Observable<CreditorPortalView> {
    return this.http.post<CreditorPortalView>(`${this.base(creditorId)}/disable`, {});
  }

  invite(creditorId: string, request: { name: string; email: string; role: string }): Observable<CreditorPortalUserView> {
    return this.http.post<CreditorPortalUserView>(`${this.base(creditorId)}/users`, request);
  }

  resendInvitation(creditorId: string, userId: string): Observable<CreditorPortalUserView> {
    return this.http.post<CreditorPortalUserView>(`${this.base(creditorId)}/users/${encodeURIComponent(userId)}/resend-invitation`, {});
  }

  resetAccess(creditorId: string, userId: string): Observable<CreditorPortalUserView> {
    return this.http.post<CreditorPortalUserView>(`${this.base(creditorId)}/users/${encodeURIComponent(userId)}/reset-access`, {});
  }

  changeRole(creditorId: string, userId: string, role: string): Observable<CreditorPortalUserView> {
    return this.http.put<CreditorPortalUserView>(`${this.base(creditorId)}/users/${encodeURIComponent(userId)}/role`, { role });
  }

  revoke(creditorId: string, userId: string, reason: string): Observable<CreditorPortalUserView> {
    return this.http.post<CreditorPortalUserView>(`${this.base(creditorId)}/users/${encodeURIComponent(userId)}/revoke`, { reason });
  }
}
