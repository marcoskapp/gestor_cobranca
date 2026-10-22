import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import { AccessLogEntry, AccessLogFilter, AuditFilter, AuditLogEntry, IntegrityReport } from './governance.models';

@Injectable({ providedIn: 'root' })
export class GovernanceApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/governance`;

  accessLog(filter: AccessLogFilter, page: PageRequest): Observable<PageResponse<AccessLogEntry>> {
    return this.http.get<PageResponse<AccessLogEntry>>(`${this.base}/access-log`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  audit(filter: AuditFilter, page: PageRequest): Observable<PageResponse<AuditLogEntry>> {
    return this.http.get<PageResponse<AuditLogEntry>>(`${this.base}/audit`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  verifyIntegrity(): Observable<IntegrityReport> {
    return this.http.post<IntegrityReport>(`${this.base}/audit/integrity-check`, null);
  }
}
