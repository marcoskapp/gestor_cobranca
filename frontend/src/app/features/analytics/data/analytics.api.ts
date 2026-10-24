import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { FileDownloadService } from '../../../shared/data/file-download';
import {
  AnalyticsFilter,
  ConversionReport,
  DatasetFreshness,
  PortfolioReport,
  ProductivityReport,
  RecoveryReport,
  ReliabilityReport,
  ReportName,
} from './analytics.models';

const BASE = `${API_V1}/analytics`;

/** Parâmetros da API (listas repetidas: `?creditorId=a&creditorId=b`). */
export function analyticsParams(filter: AnalyticsFilter, withGroupBy = true): HttpParams {
  let params = new HttpParams().set('from', filter.from).set('to', filter.to);
  const lists: [string, string[]][] = [
    ['creditorId', filter.creditorIds],
    ['portfolioId', filter.portfolioIds],
    ['operatorId', filter.operatorIds],
    ['teamId', filter.teamIds],
    ['channel', filter.channels],
  ];
  for (const [name, values] of lists) {
    for (const value of values) {
      params = params.append(name, value);
    }
  }
  if (withGroupBy && filter.groupBy) {
    params = params.set('groupBy', filter.groupBy);
  }
  return params;
}

/** Painéis e relatórios (F11, F18). */
@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  portfolio(filter: AnalyticsFilter): Observable<PortfolioReport> {
    return this.http.get<PortfolioReport>(`${BASE}/portfolio`, { params: analyticsParams(filter, false) });
  }

  recovery(filter: AnalyticsFilter): Observable<RecoveryReport> {
    return this.http.get<RecoveryReport>(`${BASE}/recovery`, { params: analyticsParams(filter) });
  }

  conversion(filter: AnalyticsFilter): Observable<ConversionReport> {
    return this.http.get<ConversionReport>(`${BASE}/conversion`, { params: analyticsParams(filter) });
  }

  productivity(filter: AnalyticsFilter): Observable<ProductivityReport> {
    return this.http.get<ProductivityReport>(`${BASE}/productivity`, { params: analyticsParams(filter) });
  }

  reliability(filter: AnalyticsFilter): Observable<ReliabilityReport> {
    return this.http.get<ReliabilityReport>(`${BASE}/reliability`, { params: analyticsParams(filter, false) });
  }

  freshness(): Observable<DatasetFreshness[]> {
    return this.http.get<DatasetFreshness[]>(`${BASE}/freshness`);
  }

  refresh(): Observable<DatasetFreshness[]> {
    return this.http.post<DatasetFreshness[]>(`${BASE}/refresh`, null);
  }

  /** Baixa o relatório com os filtros da tela; o servidor registra a exportação no log de acesso. */
  export(report: ReportName, filter: AnalyticsFilter, format: 'CSV' | 'XLSX'): Observable<string> {
    const params = analyticsParams(filter).set('format', format);
    return this.files.download(`${BASE}/${report}/export?${params.toString()}`, `relatorio-${report}.${format.toLowerCase()}`);
  }
}
