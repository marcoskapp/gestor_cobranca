import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { FileDownloadService } from '../../../shared/data/file-download';
import {
  ChannelTariff,
  ChannelTariffInput,
  CostBand,
  CostBandInput,
  FxRate,
  FxRateInput,
  MarginFilter,
  MarginReport,
  Overhead,
  OverheadInput,
  UserCostBand,
  UserCostBandInput,
} from './costing.models';

const BASE = `${API_V1}/costing`;

/** Parâmetros da margem: período e listas repetidas (`?creditorId=a&creditorId=b`), como os painéis do analytics. */
export function marginParams(filter: MarginFilter): HttpParams {
  let params = new HttpParams().set('from', filter.from).set('to', filter.to);
  for (const id of filter.creditorIds) {
    params = params.append('creditorId', id);
  }
  for (const id of filter.portfolioIds) {
    params = params.append('portfolioId', id);
  }
  return params;
}

/** Margem por credor e carteira (`COST_VIEW` + escopo por credor) e exportação (`REPORT_EXPORT`). */
@Injectable({ providedIn: 'root' })
export class MarginApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  margin(filter: MarginFilter): Observable<MarginReport> {
    return this.http.get<MarginReport>(`${BASE}/margin`, { params: marginParams(filter) });
  }

  /** Baixa a planilha ou o CSV com os filtros da tela; o servidor registra a exportação (`DATA_EXPORTED`). */
  export(filter: MarginFilter, format: 'xlsx' | 'csv'): Observable<string> {
    const params = marginParams(filter).set('format', format);
    return this.files.download(`${BASE}/margin/export?${params.toString()}`, `margem-por-carteira.${format}`);
  }

  /** Reapura o período (`COST_MANAGE`), depois de corrigir faixas, tarifas ou câmbio. */
  refresh(filter: Pick<MarginFilter, 'from' | 'to'>): Observable<unknown> {
    return this.http.post(`${BASE}/refresh`, null, { params: new HttpParams().set('from', filter.from).set('to', filter.to) });
  }
}

/** Cadastros de custo (faixas de pessoas, tarifas de canal, câmbio da IA e custos fixos). */
@Injectable({ providedIn: 'root' })
export class CostSettingsApi {
  private readonly http = inject(HttpClient);

  bands(): Observable<CostBand[]> {
    return this.http.get<CostBand[]>(`${BASE}/bands`);
  }

  /** Nova faixa ou nova vigência de uma faixa existente (mesmo nome). */
  createBand(input: CostBandInput): Observable<CostBand> {
    return this.http.post<CostBand>(`${BASE}/bands`, input);
  }

  endBand(id: string, validTo: string): Observable<CostBand> {
    return this.http.post<CostBand>(`${BASE}/bands/${id}/end`, { validTo });
  }

  userBands(): Observable<UserCostBand[]> {
    return this.http.get<UserCostBand[]>(`${BASE}/user-bands`);
  }

  assignBand(userId: string, input: UserCostBandInput): Observable<UserCostBand> {
    return this.http.put<UserCostBand>(`${BASE}/user-bands/${userId}`, input);
  }

  tariffs(): Observable<ChannelTariff[]> {
    return this.http.get<ChannelTariff[]>(`${BASE}/tariffs`);
  }

  createTariff(input: ChannelTariffInput): Observable<ChannelTariff> {
    return this.http.post<ChannelTariff>(`${BASE}/tariffs`, input);
  }

  endTariff(id: string, validTo: string): Observable<ChannelTariff> {
    return this.http.post<ChannelTariff>(`${BASE}/tariffs/${id}/end`, { validTo });
  }

  fxRates(): Observable<FxRate[]> {
    return this.http.get<FxRate[]>(`${BASE}/fx-rates`);
  }

  saveFxRate(input: FxRateInput): Observable<FxRate> {
    return this.http.post<FxRate>(`${BASE}/fx-rates`, input);
  }

  overheads(): Observable<Overhead[]> {
    return this.http.get<Overhead[]>(`${BASE}/overheads`);
  }

  createOverhead(input: OverheadInput): Observable<Overhead> {
    return this.http.post<Overhead>(`${BASE}/overheads`, input);
  }

  /** Exclui o custo fixo (qualquer mês; o servidor refaz o rateio do mês). Motivo opcional, registrado na auditoria. */
  deleteOverhead(id: string, reason?: string): Observable<void> {
    const params = reason?.trim() ? new HttpParams().set('reason', reason.trim()) : undefined;
    return this.http.delete<void>(`${BASE}/overheads/${id}`, { params });
  }
}
