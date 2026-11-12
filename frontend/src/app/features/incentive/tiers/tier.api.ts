import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { MyTier, TierLevel, TierPerson, TierRoundDetail, TierRoundSummary, TierSettings, TierSettingsView } from './tier.models';

const BASE = `${API_V1}/incentive/tiers`;

/**
 * Classificação de desempenho: parâmetros (`TIER_MANAGE`), rodadas e ajuste (`TIER_REVIEW`, pessoas no escopo),
 * publicação (`TIER_REVIEW` + `PERFORMANCE_VIEW_ALL`) e o próprio nível (`PERFORMANCE_VIEW_OWN`). As recusas de
 * negócio ficam com a tela.
 */
@Injectable({ providedIn: 'root' })
export class TiersApi {
  private readonly http = inject(HttpClient);

  settings(): Observable<TierSettingsView> {
    return this.http.get<TierSettingsView>(`${BASE}/settings`);
  }

  updateSettings(settings: TierSettings): Observable<TierSettingsView> {
    return this.http.put<TierSettingsView>(`${BASE}/settings`, settings, { context: silentErrorContext() });
  }

  rounds(): Observable<TierRoundSummary[]> {
    return this.http.get<TierRoundSummary[]>(`${BASE}/rounds`);
  }

  round(id: string): Observable<TierRoundDetail> {
    return this.http.get<TierRoundDetail>(`${BASE}/rounds/${id}`);
  }

  /** Apurar agora (janela até ontem). */
  assess(): Observable<TierRoundSummary> {
    return this.http.post<TierRoundSummary>(`${BASE}/rounds`, null, { context: silentErrorContext() });
  }

  adjust(roundId: string, userId: string, tier: TierLevel, reason: string): Observable<TierPerson> {
    return this.http.put<TierPerson>(`${BASE}/rounds/${roundId}/people/${userId}`, { tier, reason }, { context: silentErrorContext() });
  }

  publish(roundId: string): Observable<TierRoundSummary> {
    return this.http.post<TierRoundSummary>(`${BASE}/rounds/${roundId}/publish`, null, { context: silentErrorContext() });
  }

  /** O próprio nível; falha silenciosa (o cartão do "Meu desempenho" mostra o próprio estado de erro). */
  mine(): Observable<MyTier> {
    return this.http.get<MyTier>(`${BASE}/me`, { context: silentErrorContext() });
  }
}
