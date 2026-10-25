import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  CaseScoreView,
  DecisionView,
  ExpectedRecoveryFilter,
  ExpectedRecoveryReport,
  FeatureCatalog,
  LegalBasisRequest,
  ModelView,
  MonitoringView,
  ReviewRequest,
  RunKindFilter,
  RunView,
  ScoreHistoryEntry,
  ScoreReviewView,
  ScorecardRequest,
  ScoringSettingsRequest,
  ScoringSettingsView,
  TrainingRequestView,
} from './scoring.models';

const BASE = `${API_V1}/scoring`;

/**
 * Parâmetros e base legal (`ScoringAdminController`). Leitura com `SCORING_VIEW`; alteração com
 * `SCORING_MANAGE` e todos os credores. Erros de regra ficam com a tela (mensagem inline).
 */
@Injectable({ providedIn: 'root' })
export class ScoringSettingsApi {
  private readonly http = inject(HttpClient);

  get(): Observable<ScoringSettingsView> {
    return this.http.get<ScoringSettingsView>(`${BASE}/settings`);
  }

  update(request: ScoringSettingsRequest): Observable<ScoringSettingsView> {
    return this.http.put<ScoringSettingsView>(`${BASE}/settings`, request, { context: silentErrorContext() });
  }

  registerLegalBasis(request: LegalBasisRequest): Observable<ScoringSettingsView> {
    return this.http.put<ScoringSettingsView>(`${BASE}/settings/legal-basis`, request, { context: silentErrorContext() });
  }
}

/** Execuções de escoragem em lote e de treino. Todas exigem acesso a todos os credores. */
@Injectable({ providedIn: 'root' })
export class ScoringRunsApi {
  private readonly http = inject(HttpClient);

  list(kind: RunKindFilter, page: PageRequest): Observable<PageResponse<RunView>> {
    return this.http.get<PageResponse<RunView>>(`${BASE}/runs`, { params: toHttpParams({ kind, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<RunView> {
    return this.http.get<RunView>(`${BASE}/runs/${id}`);
  }

  /** Escoragem de hoje: devolve a execução do dia, se já existir (202). */
  startBatch(): Observable<RunView> {
    return this.http.post<RunView>(`${BASE}/runs`, null);
  }
}

/** Versões de modelo, treino, scorecards e a governança campeão × desafiante. */
@Injectable({ providedIn: 'root' })
export class ScoringModelsApi {
  private readonly http = inject(HttpClient);

  list(): Observable<ModelView[]> {
    return this.http.get<ModelView[]>(`${BASE}/models`);
  }

  get(id: string): Observable<ModelView> {
    return this.http.get<ModelView>(`${BASE}/models/${id}`);
  }

  /** Assíncrono: devolve a execução de treino em andamento. */
  train(): Observable<TrainingRequestView> {
    return this.http.post<TrainingRequestView>(`${BASE}/models/training`, null);
  }

  createScorecard(request: ScorecardRequest): Observable<ModelView> {
    return this.http.post<ModelView>(`${BASE}/models/scorecards`, request, { context: silentErrorContext() });
  }

  promote(id: string, justification: string): Observable<ModelView> {
    return this.http.post<ModelView>(`${BASE}/models/${id}/promote`, { justification }, { context: silentErrorContext() });
  }

  reject(id: string, justification: string): Observable<ModelView> {
    return this.http.post<ModelView>(`${BASE}/models/${id}/reject`, { justification }, { context: silentErrorContext() });
  }

  decisions(): Observable<DecisionView[]> {
    return this.http.get<DecisionView[]>(`${BASE}/decisions`);
  }

  /** Catálogo fechado de variáveis (sem exigência de escopo). */
  features(): Observable<FeatureCatalog> {
    return this.http.get<FeatureCatalog>(`${BASE}/features`);
  }
}

/** Relatórios de estabilidade e desempenho realizado; recuperado × esperado (filtrado pelo escopo). */
@Injectable({ providedIn: 'root' })
export class ScoringMonitoringApi {
  private readonly http = inject(HttpClient);

  list(page: PageRequest): Observable<PageResponse<MonitoringView>> {
    return this.http.get<PageResponse<MonitoringView>>(`${BASE}/monitoring`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  expectedRecovery(filter: ExpectedRecoveryFilter): Observable<ExpectedRecoveryReport> {
    return this.http.get<ExpectedRecoveryReport>(`${BASE}/expected-recovery`, {
      params: toHttpParams({ ...filter }),
      context: silentErrorContext(),
    });
  }
}

/**
 * Score do caso (`CaseScoreController`): o caso precisa estar no escopo do usuário. Sem score
 * calculado o servidor responde 404, que aqui vira `null` (o cartão mostra "sem score").
 */
@Injectable({ providedIn: 'root' })
export class CaseScoreApi {
  private readonly http = inject(HttpClient);

  private base(caseId: string): string {
    return `${BASE}/cases/${caseId}`;
  }

  get(caseId: string): Observable<CaseScoreView | null> {
    return this.http.get<CaseScoreView>(this.base(caseId), { context: silentErrorContext() }).pipe(
      catchError((err: unknown) => (err instanceof HttpErrorResponse && err.status === 404 ? of(null) : throwError(() => err))),
    );
  }

  history(caseId: string): Observable<ScoreHistoryEntry[]> {
    return this.http.get<ScoreHistoryEntry[]>(`${this.base(caseId)}/history`);
  }

  reviews(caseId: string): Observable<ScoreReviewView[]> {
    return this.http.get<ScoreReviewView[]>(`${this.base(caseId)}/reviews`);
  }

  recalculate(caseId: string): Observable<CaseScoreView> {
    return this.http.post<CaseScoreView>(`${this.base(caseId)}/recalculate`, null, { context: silentErrorContext() });
  }

  review(caseId: string, request: ReviewRequest): Observable<ScoreReviewView> {
    return this.http.post<ScoreReviewView>(`${this.base(caseId)}/reviews`, request, { context: silentErrorContext() });
  }
}
