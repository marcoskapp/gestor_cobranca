import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, from, map, switchMap, throwError } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService, fileNameFrom } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import { sha256Hex } from '../../settlement/data/settlement.rules';
import {
  AdjustmentView,
  AttributionView,
  DisputeRequest,
  DisputeView,
  EvaluationRequest,
  EvaluationView,
  ExportFormat,
  ExportView,
  GoalRequest,
  GoalSubjectType,
  GoalUnitOptions,
  GoalView,
  IndicatorReport,
  LedgerEntryView,
  OccurrenceRequest,
  OccurrenceView,
  PerformanceQuery,
  PeriodView,
  PersonRangeFilter,
  PlanRequest,
  PlanView,
  QualityForm,
  QualityFormRequest,
  ResolveDisputeRequest,
  SimulationView,
  StatementDetail,
  StatementFilter,
  StatementPreview,
  StatementSummary,
  YearMonth,
} from './incentive.models';

const BASE = `${API_V1}/incentive`;

/** Planos de comissão versionados, metas por período e simulação (`COMMISSION_PLAN_MANAGE`). */
@Injectable({ providedIn: 'root' })
export class CommissionPlansApi {
  private readonly http = inject(HttpClient);

  list(): Observable<PlanView[]> {
    return this.http.get<PlanView[]>(`${BASE}/plans`);
  }

  get(id: string): Observable<PlanView> {
    return this.http.get<PlanView>(`${BASE}/plans/${id}`);
  }

  /** Recusas de validação (`plan.*`, `plan.rules.*`) ficam com a tela. */
  create(request: PlanRequest): Observable<PlanView> {
    return this.http.post<PlanView>(`${BASE}/plans`, request, { context: silentErrorContext() });
  }

  update(id: string, request: PlanRequest): Observable<PlanView> {
    return this.http.put<PlanView>(`${BASE}/plans/${id}`, request, { context: silentErrorContext() });
  }

  discard(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/plans/${id}`, { context: silentErrorContext() });
  }

  newVersion(id: string): Observable<PlanView> {
    return this.http.post<PlanView>(`${BASE}/plans/${id}/versions`, null, { context: silentErrorContext() });
  }

  publish(id: string, effectiveFrom: string): Observable<PlanView> {
    return this.http.post<PlanView>(`${BASE}/plans/${id}/publish`, { effectiveFrom }, { context: silentErrorContext() });
  }

  retire(id: string, effectiveTo: string): Observable<PlanView> {
    return this.http.post<PlanView>(`${BASE}/plans/${id}/retire`, { effectiveTo }, { context: silentErrorContext() });
  }

  /** Não grava nada; compara com os extratos efetivos do período. */
  simulate(id: string, period: YearMonth): Observable<SimulationView> {
    return this.http.post<SimulationView>(`${BASE}/plans/${id}/simulations`, null, { params: toHttpParams({ period }), context: silentErrorContext() });
  }

  goals(period: YearMonth): Observable<GoalView[]> {
    return this.http.get<GoalView[]>(`${BASE}/goals`, { params: toHttpParams({ period }) });
  }

  /** Unidades aceitas para a meta do negociador/equipe no período, pela métrica do plano vigente. */
  goalUnitOptions(period: YearMonth, subjectType: GoalSubjectType, subjectId: string): Observable<GoalUnitOptions> {
    return this.http.get<GoalUnitOptions>(`${BASE}/goals/unit-options`, { params: toHttpParams({ period, subjectType, subjectId }), context: silentErrorContext() });
  }

  setGoal(request: GoalRequest): Observable<GoalView> {
    return this.http.put<GoalView>(`${BASE}/goals`, request, { context: silentErrorContext() });
  }

  removeGoal(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/goals/${id}`, { context: silentErrorContext() });
  }
}

/** Períodos de apuração e fechamento (`COMMISSION_STATEMENT_MANAGE`). */
@Injectable({ providedIn: 'root' })
export class CommissionPeriodsApi {
  private readonly http = inject(HttpClient);

  list(): Observable<PeriodView[]> {
    return this.http.get<PeriodView[]>(`${BASE}/periods`);
  }

  close(period: YearMonth): Observable<PeriodView> {
    return this.http.post<PeriodView>(`${BASE}/periods/${period}/close`, null, { context: silentErrorContext() });
  }
}

/** Extratos, razão, prévia, contestação, ajuste e aprovação (escopo próprio, da equipe ou de todos). */
@Injectable({ providedIn: 'root' })
export class CommissionStatementsApi {
  private readonly http = inject(HttpClient);

  search(filter: StatementFilter, page: PageRequest): Observable<PageResponse<StatementSummary>> {
    return this.http.get<PageResponse<StatementSummary>>(`${BASE}/statements`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<StatementDetail> {
    return this.http.get<StatementDetail>(`${BASE}/statements/${id}`);
  }

  /** `userId` nulo = o próprio negociador. */
  preview(userId: string | null): Observable<StatementPreview> {
    return this.http.get<StatementPreview>(`${BASE}/statements/preview`, { params: toHttpParams({ userId }) });
  }

  ledger(userId: string | null, period: YearMonth | null, page: PageRequest): Observable<PageResponse<LedgerEntryView>> {
    return this.http.get<PageResponse<LedgerEntryView>>(`${BASE}/ledger`, { params: toHttpParams({ userId, period, page: page.page, size: page.size }) });
  }

  recalculate(id: string): Observable<StatementDetail> {
    return this.http.post<StatementDetail>(`${BASE}/statements/${id}/recalculate`, null, { context: silentErrorContext() });
  }

  dispute(id: string, request: DisputeRequest): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/statements/${id}/disputes`, request, { context: silentErrorContext() });
  }

  resolve(id: string, disputeId: string, request: ResolveDisputeRequest): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/statements/${id}/disputes/${disputeId}/resolve`, request, { context: silentErrorContext() });
  }

  adjust(id: string, amount: number, reason: string): Observable<AdjustmentView> {
    return this.http.post<AdjustmentView>(`${BASE}/statements/${id}/adjustments`, { amount, reason }, { context: silentErrorContext() });
  }

  decide(id: string, adjustmentId: string, approve: boolean, note: string | null): Observable<AdjustmentView> {
    return this.http.post<AdjustmentView>(`${BASE}/statements/${id}/adjustments/${adjustmentId}/decision`, { approve, note }, { context: silentErrorContext() });
  }

  approve(id: string): Observable<StatementDetail> {
    return this.http.post<StatementDetail>(`${BASE}/statements/${id}/approve`, null, { context: silentErrorContext() });
  }
}

/** Resultado de um download conferido com o SHA-256 registrado na exportação. */
export interface PayrollDownload {
  fileName: string;
  verified: boolean;
}

/** Erro de resposta `blob`: o Problem Details chega como Blob; converte para JSON (código da recusa). */
export async function problemFromBlob(err: unknown): Promise<unknown> {
  if (!(err instanceof HttpErrorResponse) || !(err.error instanceof Blob)) {
    return err;
  }
  try {
    const body: unknown = JSON.parse(await err.error.text());
    return new HttpErrorResponse({ error: body, headers: err.headers, status: err.status, statusText: err.statusText, url: err.url ?? undefined });
  } catch {
    return err;
  }
}

/** Exportação para a folha (`COMMISSION_STATEMENT_APPROVE`). */
@Injectable({ providedIn: 'root' })
export class PayrollExportsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  list(period: YearMonth): Observable<ExportView[]> {
    return this.http.get<ExportView[]>(`${BASE}/exports`, { params: toHttpParams({ period }) });
  }

  /** Gera o arquivo (o servidor o guarda com SHA-256 e marca os extratos como exportados) e o salva. */
  export(period: YearMonth, format: ExportFormat): Observable<string> {
    return this.http
      .post(`${BASE}/exports`, { period, format }, { observe: 'response', responseType: 'blob', context: silentErrorContext() })
      .pipe(
        map((response) => {
          const name = fileNameFrom(response) ?? `folha-comissao-${period}.${format.toLowerCase()}`;
          this.files.saveBlob(response.body ?? new Blob(), name);
          return name;
        }),
        catchError((err: unknown) => from(problemFromBlob(err)).pipe(switchMap((e) => throwError(() => e)))),
      );
  }

  /** Baixa de novo um arquivo gerado e confere o conteúdo com o SHA-256 registrado. */
  download(view: ExportView): Observable<PayrollDownload> {
    return this.http.get(`${BASE}/exports/${view.id}/file`, { observe: 'response', responseType: 'blob' }).pipe(
      switchMap((response) => {
        const blob = response.body ?? new Blob();
        const name = fileNameFrom(response) ?? view.fileName;
        return from(blob.arrayBuffer().then((buffer) => sha256Hex(buffer))).pipe(
          map((actual) => {
            this.files.saveBlob(blob, name);
            return { fileName: name, verified: actual === view.sha256.toLowerCase() };
          }),
        );
      }),
    );
  }
}

/** Monitoria de qualidade e ocorrências de conduta. */
@Injectable({ providedIn: 'root' })
export class QualityApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/quality`;

  forms(): Observable<QualityForm[]> {
    return this.http.get<QualityForm[]>(`${this.base}/forms`);
  }

  createForm(request: QualityFormRequest): Observable<QualityForm> {
    return this.http.post<QualityForm>(`${this.base}/forms`, request, { context: silentErrorContext() });
  }

  deactivateForm(id: string): Observable<QualityForm> {
    return this.http.post<QualityForm>(`${this.base}/forms/${id}/deactivate`, null, { context: silentErrorContext() });
  }

  evaluations(filter: PersonRangeFilter, page: PageRequest): Observable<PageResponse<EvaluationView>> {
    return this.http.get<PageResponse<EvaluationView>>(`${this.base}/evaluations`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  evaluate(request: EvaluationRequest): Observable<EvaluationView> {
    return this.http.post<EvaluationView>(`${this.base}/evaluations`, request, { context: silentErrorContext() });
  }

  occurrences(filter: PersonRangeFilter, page: PageRequest): Observable<PageResponse<OccurrenceView>> {
    return this.http.get<PageResponse<OccurrenceView>>(`${this.base}/occurrences`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  register(request: OccurrenceRequest): Observable<OccurrenceView> {
    return this.http.post<OccurrenceView>(`${this.base}/occurrences`, request, { context: silentErrorContext() });
  }

  dismiss(id: string, reason: string): Observable<OccurrenceView> {
    return this.http.post<OccurrenceView>(`${this.base}/occurrences/${id}/dismiss`, { reason }, { context: silentErrorContext() });
  }
}

/** Indicadores dos negociadores e atribuição de crédito dos acordos. */
@Injectable({ providedIn: 'root' })
export class PerformanceApi {
  private readonly http = inject(HttpClient);

  indicators(query: PerformanceQuery): Observable<IndicatorReport> {
    return this.http.get<IndicatorReport>(`${BASE}/performance`, { params: toHttpParams({ ...query }) });
  }

  attribution(agreementId: string): Observable<AttributionView> {
    return this.http.get<AttributionView>(`${BASE}/attributions/${agreementId}`, { context: silentErrorContext() });
  }
}
