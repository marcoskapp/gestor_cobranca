import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { FileDownloadService } from '../../../shared/data/file-download';
import {
  ChannelInvoice,
  ChannelInvoiceInput,
  ChannelTariff,
  ChannelTariffInput,
  CostBand,
  CostBandInput,
  FxRate,
  FxRateInput,
  ItemEstimate,
  MarginBasis,
  MarginFilter,
  MarginReport,
  MarginTrend,
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

function withBasis(params: HttpParams, basis: MarginBasis): HttpParams {
  return basis === 'RECONCILED' ? params.set('basis', basis) : params;
}

/** Meses `AAAA-MM` de uma consulta das faturas. */
function monthRange(from: string, to: string): HttpParams {
  return new HttpParams().set('from', from.slice(0, 7)).set('to', to.slice(0, 7));
}

/** Margem por credor e carteira (`COST_VIEW` + escopo por credor) e exportação (`REPORT_EXPORT`). */
@Injectable({ providedIn: 'root' })
export class MarginApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  /** Base conciliada só vai na URL quando pedida (padrão do servidor: estimada). */
  margin(filter: MarginFilter, basis: MarginBasis = 'ESTIMATED'): Observable<MarginReport> {
    return this.http.get<MarginReport>(`${BASE}/margin`, { params: withBasis(marginParams(filter), basis) });
  }

  /** Tendência dos 6 meses até o mês do fim do período, por credor e carteira (mesmos filtros e escopo). */
  trend(filter: MarginFilter, basis: MarginBasis = 'ESTIMATED'): Observable<MarginTrend> {
    return this.http.get<MarginTrend>(`${BASE}/margin/trend`, { params: withBasis(marginParams(filter), basis) });
  }

  /** Baixa a planilha ou o CSV com os filtros da tela; o servidor registra a exportação (`DATA_EXPORTED`). */
  export(filter: MarginFilter, format: 'xlsx' | 'csv', basis: MarginBasis = 'ESTIMATED'): Observable<string> {
    const params = withBasis(marginParams(filter), basis).set('format', format);
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

  // ------------------------------------------------------------ faturas de canal (fase 2, §13)

  /** Faturas dos meses (`AAAA-MM` ou `AAAA-MM-01`), com a estimativa do item. Exige todos os credores. */
  invoices(from: string, to: string): Observable<ChannelInvoice[]> {
    return this.http.get<ChannelInvoice[]>(`${BASE}/invoices`, { params: monthRange(from, to) });
  }

  /** Estimativa de cada item por mês na assessoria inteira, com a fatura quando houver. */
  invoiceEstimates(from: string, to: string): Observable<ItemEstimate[]> {
    return this.http.get<ItemEstimate[]>(`${BASE}/invoices/estimates`, { params: monthRange(from, to) });
  }

  /** Informa ou substitui a fatura do mês × item; o servidor refaz o ajuste do mês na hora. */
  saveInvoice(input: ChannelInvoiceInput): Observable<ChannelInvoice> {
    return this.http.post<ChannelInvoice>(`${BASE}/invoices`, { ...input, month: input.month.slice(0, 7) });
  }

  /** Exclui a fatura (motivo opcional, na auditoria); o ajuste do mês é refeito. */
  deleteInvoice(id: string, reason?: string): Observable<void> {
    const params = reason?.trim() ? new HttpParams().set('reason', reason.trim()) : undefined;
    return this.http.delete<void>(`${BASE}/invoices/${id}`, { params });
  }
}
