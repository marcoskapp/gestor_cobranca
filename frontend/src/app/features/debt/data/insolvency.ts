import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';

/**
 * Recuperação judicial, recuperação extrajudicial e falência da empresa devedora (cobrança B2B §7,
 * `InsolvencyUseCases`). Enquanto houver processo ativo, os casos do devedor ficam inelegíveis (sem contato nem régua).
 */
export type InsolvencyKind = 'JUDICIAL_RECOVERY' | 'EXTRAJUDICIAL_RECOVERY' | 'BANKRUPTCY';

/** `InsolvencyUseCases.ProceedingView` */
export interface ProceedingView {
  id: string;
  /** Nulo quando a unidade da empresa está fora do seu escopo (o processo aparece, sem o id nem o texto livre). */
  debtorId: string | null;
  kind: InsolvencyKind;
  /** Número CNJ formatado (dado público do processo). */
  caseNumber: string;
  court: string | null;
  filedOn: string;
  /** Início do efeito atual: decretação da falência, quando convolada; senão, o pedido. */
  since: string;
  convertedFrom: InsolvencyKind | null;
  convertedOn: string | null;
  status: 'ACTIVE' | 'ENDED';
  endedOn: string | null;
  endReason: string | null;
  note: string | null;
}

/** `InsolvencyUseCases.DebtorInsolvency` */
export interface DebtorInsolvency {
  debtorId: string;
  company: boolean;
  active: ProceedingView | null;
  history: ProceedingView[];
  /** Pode registrar, convolar e encerrar (DEBTOR_MANAGE e escopo sobre todos os credores da empresa). */
  canManage: boolean;
}

export interface RegisterProceedingRequest {
  kind: InsolvencyKind;
  caseNumber: string;
  court: string | null;
  filedOn: string;
  note: string;
}

export const INSOLVENCY_KIND_LABELS: Record<InsolvencyKind, string> = {
  JUDICIAL_RECOVERY: 'Recuperação judicial',
  EXTRAJUDICIAL_RECOVERY: 'Recuperação extrajudicial',
  BANKRUPTCY: 'Falência',
};

/** Frase do aviso: "Empresa em recuperação judicial desde 02/03/2026". */
export const INSOLVENCY_KIND_PHRASES: Record<InsolvencyKind, string> = {
  JUDICIAL_RECOVERY: 'Empresa em recuperação judicial',
  EXTRAJUDICIAL_RECOVERY: 'Empresa em recuperação extrajudicial',
  BANKRUPTCY: 'Empresa em falência',
};

@Injectable({ providedIn: 'root' })
export class InsolvencyApi {
  private readonly http = inject(HttpClient);

  /** Silenciosa: sem permissão ou fora do escopo, o aviso simplesmente não aparece. */
  ofDebtor(debtorId: string): Observable<DebtorInsolvency> {
    return this.http.get<DebtorInsolvency>(`${API_V1}/debtors/${debtorId}/insolvency`, { context: silentErrorContext() });
  }

  register(debtorId: string, request: RegisterProceedingRequest): Observable<ProceedingView> {
    return this.http.post<ProceedingView>(`${API_V1}/debtors/${debtorId}/insolvency`, request);
  }

  convertToBankruptcy(id: string, convertedOn: string, reason: string): Observable<ProceedingView> {
    return this.http.post<ProceedingView>(`${API_V1}/insolvency-proceedings/${id}/bankruptcy`, { convertedOn, reason });
  }

  end(id: string, endedOn: string, reason: string): Observable<ProceedingView> {
    return this.http.post<ProceedingView>(`${API_V1}/insolvency-proceedings/${id}/end`, { endedOn, reason });
  }
}
