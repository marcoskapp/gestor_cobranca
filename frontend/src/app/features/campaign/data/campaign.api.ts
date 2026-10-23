import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AudienceCount,
  CadenceDefinition,
  CadenceView,
  CampaignExecutionResult,
  CampaignFilter,
  CampaignPanel,
  CampaignSettings,
  CampaignView,
  CreateCampaignRequest,
  CreateSegmentRequest,
  EnrollmentDetail,
  EnrollmentFilter,
  EnrollmentView,
  ExperimentInput,
  ExperimentResults,
  ExperimentView,
  PreviewRequest,
  SegmentFilter,
  SegmentView,
  UpdateSegmentRequest,
} from './campaign.models';

const BASE = `${API_V1}/campaign`;

/** Segmentos salvos, contagem prévia e reconstrução da audiência. */
@Injectable({ providedIn: 'root' })
export class SegmentsApi {
  private readonly http = inject(HttpClient);

  search(filter: SegmentFilter, page: PageRequest): Observable<PageResponse<SegmentView>> {
    return this.http.get<PageResponse<SegmentView>>(`${BASE}/segments`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<SegmentView> {
    return this.http.get<SegmentView>(`${BASE}/segments/${id}`);
  }

  create(request: CreateSegmentRequest): Observable<SegmentView> {
    return this.http.post<SegmentView>(`${BASE}/segments`, request);
  }

  update(id: string, request: UpdateSegmentRequest): Observable<SegmentView> {
    return this.http.put<SegmentView>(`${BASE}/segments/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<SegmentView> {
    return this.http.post<SegmentView>(`${BASE}/segments/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }

  /** Contagem de um segmento salvo, opcionalmente restrita ao credor/carteira da campanha. */
  count(id: string, creditorId: string | null, portfolioId: string | null): Observable<AudienceCount> {
    return this.http.get<AudienceCount>(`${BASE}/segments/${id}/count`, {
      params: toHttpParams({ creditorId, portfolioId }),
      context: silentErrorContext(),
    });
  }

  /** Contagem de filtros ainda não salvos (a tela trata o erro sem snack-bar a cada digitação). */
  preview(request: PreviewRequest): Observable<AudienceCount> {
    return this.http.post<AudienceCount>(`${BASE}/segments/preview`, request, { context: silentErrorContext() });
  }

  /** Reconstrução assíncrona do read model de audiência (202). */
  rebuildAudience(creditorId: string | null): Observable<void> {
    return this.http.post<void>(`${BASE}/audience/rebuild`, null, { params: toHttpParams({ creditorId }) });
  }
}

/** Campanhas e réguas versionadas. */
@Injectable({ providedIn: 'root' })
export class CampaignsApi {
  private readonly http = inject(HttpClient);

  search(filter: CampaignFilter, page: PageRequest): Observable<PageResponse<CampaignView>> {
    return this.http.get<PageResponse<CampaignView>>(`${BASE}/campaigns`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<CampaignView> {
    return this.http.get<CampaignView>(`${BASE}/campaigns/${id}`);
  }

  create(request: CreateCampaignRequest): Observable<CampaignView> {
    return this.http.post<CampaignView>(`${BASE}/campaigns`, request);
  }

  update(id: string, settings: CampaignSettings): Observable<CampaignView> {
    return this.http.put<CampaignView>(`${BASE}/campaigns/${id}`, settings);
  }

  cadences(id: string): Observable<CadenceView[]> {
    return this.http.get<CadenceView[]>(`${BASE}/campaigns/${id}/cadences`);
  }

  cadence(cadenceId: string): Observable<CadenceView> {
    return this.http.get<CadenceView>(`${BASE}/cadences/${cadenceId}`);
  }

  /** Cria ou altera o rascunho da próxima versão. */
  saveDraft(id: string, definition: CadenceDefinition): Observable<CadenceView> {
    return this.http.put<CadenceView>(`${BASE}/campaigns/${id}/cadences/draft`, definition);
  }

  publishDraft(id: string): Observable<CadenceView> {
    return this.http.post<CadenceView>(`${BASE}/campaigns/${id}/cadences/draft/publish`, null);
  }
}

/** Ativação, pausa, encerramento, reprocessamento, inscrições e painel. */
@Injectable({ providedIn: 'root' })
export class CampaignExecutionApi {
  private readonly http = inject(HttpClient);

  activate(id: string): Observable<CampaignExecutionResult> {
    return this.http.post<CampaignExecutionResult>(`${BASE}/campaigns/${id}/activate`, null);
  }

  pause(id: string, note: string): Observable<CampaignExecutionResult> {
    return this.http.post<CampaignExecutionResult>(`${BASE}/campaigns/${id}/pause`, { note });
  }

  finish(id: string, note: string): Observable<CampaignExecutionResult> {
    return this.http.post<CampaignExecutionResult>(`${BASE}/campaigns/${id}/finish`, { note });
  }

  reprocess(id: string): Observable<CampaignExecutionResult> {
    return this.http.post<CampaignExecutionResult>(`${BASE}/campaigns/${id}/reprocess`, null);
  }

  dashboard(id: string): Observable<CampaignPanel> {
    return this.http.get<CampaignPanel>(`${BASE}/campaigns/${id}/dashboard`);
  }

  enrollments(id: string, filter: EnrollmentFilter, page: PageRequest): Observable<PageResponse<EnrollmentView>> {
    return this.http.get<PageResponse<EnrollmentView>>(`${BASE}/campaigns/${id}/enrollments`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  enrollment(id: string): Observable<EnrollmentDetail> {
    return this.http.get<EnrollmentDetail>(`${BASE}/enrollments/${id}`);
  }

  pauseEnrollment(id: string, note: string): Observable<EnrollmentView> {
    return this.http.post<EnrollmentView>(`${BASE}/enrollments/${id}/pause`, { note });
  }

  resumeEnrollment(id: string): Observable<EnrollmentView> {
    return this.http.post<EnrollmentView>(`${BASE}/enrollments/${id}/resume`, null);
  }

  stopEnrollment(id: string, note: string): Observable<EnrollmentView> {
    return this.http.post<EnrollmentView>(`${BASE}/enrollments/${id}/stop`, { note });
  }
}

/** Testes A/B por campanha. */
@Injectable({ providedIn: 'root' })
export class ExperimentsApi {
  private readonly http = inject(HttpClient);

  list(campaignId: string): Observable<ExperimentView[]> {
    return this.http.get<ExperimentView[]>(`${BASE}/campaigns/${campaignId}/experiments`);
  }

  get(id: string): Observable<ExperimentView> {
    return this.http.get<ExperimentView>(`${BASE}/experiments/${id}`);
  }

  create(campaignId: string, input: ExperimentInput): Observable<ExperimentView> {
    return this.http.post<ExperimentView>(`${BASE}/campaigns/${campaignId}/experiments`, input);
  }

  update(id: string, input: ExperimentInput): Observable<ExperimentView> {
    return this.http.put<ExperimentView>(`${BASE}/experiments/${id}`, input);
  }

  start(id: string): Observable<ExperimentView> {
    return this.http.post<ExperimentView>(`${BASE}/experiments/${id}/start`, null);
  }

  stop(id: string, reason: string): Observable<ExperimentView> {
    return this.http.post<ExperimentView>(`${BASE}/experiments/${id}/stop`, { reason });
  }

  results(id: string, mature: boolean): Observable<ExperimentResults> {
    return this.http.get<ExperimentResults>(`${BASE}/experiments/${id}/results`, { params: toHttpParams({ mature }) });
  }
}
