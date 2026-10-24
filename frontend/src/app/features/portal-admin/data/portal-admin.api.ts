import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';

export type KnowledgeChallenge = 'NONE' | 'BIRTH_DATE' | 'CONTRACT_SUFFIX';

export interface PortalConfigurationView {
  id: string;
  creditorId: string;
  slug: string;
  enabled: boolean;
  displayName: string;
  primaryColor: string;
  accentColor: string;
  hasLogo: boolean;
  logoSha256: string | null;
  portfolioIds: string[];
  otpChannels: string[];
  knowledgeChallenge: KnowledgeChallenge;
  linkValidityHours: number;
  privacyNoticeVersion: number;
  privacyNoticeText: string;
  supportText: string | null;
  publicUrl: string;
  updatedAt: string;
  version: number;
}

export interface PortalConfigurationRequest {
  creditorId: string | null;
  slug: string;
  enabled: boolean;
  displayName: string;
  primaryColor: string;
  accentColor: string;
  portfolioIds: string[];
  otpChannels: string[];
  knowledgeChallenge: KnowledgeChallenge;
  linkValidityHours: number;
  privacyNoticeText: string;
  supportText: string | null;
}

export interface FunnelView {
  configurationId: string;
  from: string;
  to: string;
  accesses: number;
  simulations: number;
  acceptances: number;
  pixIssued: number;
  paid: number;
  serviceRequests: number;
}

export interface PortalSessionSummary {
  id: string;
  debtorId: string;
  method: string;
  status: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  endedAt: string | null;
  endReason: string | null;
}

export interface AccessLinkView {
  linkId: string;
  debtorId: string;
  creditorId: string;
  channel: string;
  expiresAt: string;
  messageId: string;
  messageStatus: string;
  messageFailure: string | null;
}

export interface ServiceRequestSummary {
  id: string;
  protocol: string;
  creditorId: string;
  portfolioId: string | null;
  debtorId: string;
  reason: string;
  preferredChannel: string;
  status: string;
  note: string | null;
  conversationId: string | null;
  failureCode: string | null;
  createdAt: string;
  forwardedAt: string | null;
}

const BASE = `${API_V1}/portal`;

/** Gestão interna do portal (`PORTAL_MANAGE`), envio de link (`MESSAGE_SEND`) e pedidos (`INBOX_VIEW`). */
@Injectable({ providedIn: 'root' })
export class PortalAdminApi {
  private readonly http = inject(HttpClient);

  list(): Observable<PortalConfigurationView[]> {
    return this.http.get<PortalConfigurationView[]>(`${BASE}/configurations`);
  }

  get(id: string): Observable<PortalConfigurationView> {
    return this.http.get<PortalConfigurationView>(`${BASE}/configurations/${id}`);
  }

  create(request: PortalConfigurationRequest): Observable<PortalConfigurationView> {
    return this.http.post<PortalConfigurationView>(`${BASE}/configurations`, request);
  }

  update(id: string, request: PortalConfigurationRequest): Observable<PortalConfigurationView> {
    return this.http.put<PortalConfigurationView>(`${BASE}/configurations/${id}`, request);
  }

  uploadLogo(id: string, file: File): Observable<PortalConfigurationView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<PortalConfigurationView>(`${BASE}/configurations/${id}/logo`, body);
  }

  removeLogo(id: string): Observable<PortalConfigurationView> {
    return this.http.delete<PortalConfigurationView>(`${BASE}/configurations/${id}/logo`);
  }

  logo(id: string): Observable<Blob> {
    return this.http.get(`${BASE}/configurations/${id}/logo`, { responseType: 'blob' });
  }

  funnel(id: string, from: string | null, to: string | null): Observable<FunnelView> {
    return this.http.get<FunnelView>(`${BASE}/configurations/${id}/funnel`, { params: toHttpParams({ from, to }) });
  }

  sessions(id: string, page: PageRequest): Observable<PageResponse<PortalSessionSummary>> {
    return this.http.get<PageResponse<PortalSessionSummary>>(`${BASE}/configurations/${id}/sessions`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }

  revokeSession(sessionId: string, reason: string): Observable<PortalSessionSummary> {
    return this.http.post<PortalSessionSummary>(`${BASE}/sessions/${sessionId}/revoke`, { reason });
  }

  sendLink(debtorId: string, creditorId: string, channel: string): Observable<AccessLinkView> {
    return this.http.post<AccessLinkView>(`${BASE}/access-links`, { debtorId, creditorId, channel });
  }

  serviceRequests(creditorId: string | null, status: string | null, page: PageRequest): Observable<PageResponse<ServiceRequestSummary>> {
    return this.http.get<PageResponse<ServiceRequestSummary>>(`${BASE}/service-requests`, {
      params: toHttpParams({ creditorId, status, page: page.page, size: page.size }),
    });
  }
}

/** Contraste WCAG com o branco (o servidor recusa abaixo de 4,5:1 na primária e 3:1 no destaque). */
export function contrastWithWhite(hex: string): number | null {
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) {
    return null;
  }
  const channel = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  const n = parseInt(hex.slice(1), 16);
  const l = 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
  return 1.05 / (l + 0.05);
}
