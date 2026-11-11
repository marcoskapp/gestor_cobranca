import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { percent } from './negotiation.labels';

/** Faixa de valor do acordo no filtro da saúde da política. */
export type ValueBand = 'V0_1K' | 'V1K_5K' | 'V5K_20K' | 'V20K_PLUS';

export const VALUE_BAND_LABELS: Record<ValueBand, string> = {
  V0_1K: 'Até R$ 1 mil',
  V1K_5K: 'R$ 1 mil a 5 mil',
  V5K_20K: 'R$ 5 mil a 20 mil',
  V20K_PLUS: 'Acima de R$ 20 mil',
};

/** Faixa de atraso do recorte (o maior atraso dos títulos do acordo ou da consulta). */
export const DELAY_LABELS: Record<string, string> = {
  NOT_DUE: 'A vencer',
  D0_30: 'Até 30 dias',
  D31_90: '31 a 90 dias',
  D91_180: '91 a 180 dias',
  D181_360: '181 a 360 dias',
  D361_PLUS: 'Mais de 360 dias',
  UNKNOWN: 'Atraso não informado',
};

/** Recorte sem faixa: pedidos "nenhuma condição cabe" (o pedido não tem atraso). */
export const ALL_DELAYS = 'Todas as faixas';

export type SuggestionKind = 'HIGH_EARLY_BREAK' | 'DISCOUNT_AT_LIMIT' | 'DISCOUNT_UNUSED' | 'PORTAL_WITHOUT_OFFER' | 'CANNOT_AFFORD';

export interface SegmentHealth {
  portfolioId: string | null;
  delayBucket: string | null;
  agreements: number;
  matureAgreements: number;
  brokenEarly: number;
  earlyBreakRate: number | null;
  agreedAmount: number;
  paidAmount: number;
  paidOverAgreed: number | null;
  averageEnvelopeUsage: number | null;
  highEnvelopeShare: number | null;
  portalSimulations: number;
  portalWithoutOffer: number;
  portalWithoutOfferShare: number | null;
  cannotAfford: number;
}

export interface PolicyHealthView {
  creditorId: string;
  from: string;
  to: string;
  valueBand: ValueBand | null;
  asOf: string;
  total: SegmentHealth;
  segments: SegmentHealth[];
  suggestions: { kind: SuggestionKind; portfolioId: string | null; delayBucket: string | null }[];
  minAgreements: number;
  minMature: number;
  minPortal: number;
}

export interface PolicyHealthFilter {
  creditorId: string;
  from: string | null;
  to: string | null;
  valueBand: ValueBand | null;
}

/** Saúde da política (`GET /analytics/policy-health`, `POLICY_MANAGE` ou `REPORT_VIEW`; decisão 1087). */
@Injectable({ providedIn: 'root' })
export class PolicyHealthApi {
  private readonly http = inject(HttpClient);

  health(filter: PolicyHealthFilter): Observable<PolicyHealthView> {
    return this.http.get<PolicyHealthView>(`${API_V1}/analytics/policy-health`, {
      params: toHttpParams({ creditorId: filter.creditorId, from: filter.from, to: filter.to, valueBand: filter.valueBand }),
      context: silentErrorContext(),
    });
  }
}

/** Fração (0,25) em percentual legível ("25%"). */
export function share(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : percent(value * 100, 1);
}

/** Sugestão em linguagem de gestão: o sinal, a evidência e o que avaliar — nunca muda a política sozinha. */
export interface SuggestionText {
  title: string;
  evidence: string;
  action: string;
}

/** Texto da sugestão com os números do recorte (e do resto do credor, quando a regra compara). */
export function suggestionText(kind: SuggestionKind, s: SegmentHealth, total: SegmentHealth, minMature = 10): SuggestionText {
  const rest = restBreakRate(s, total, minMature);
  const restText = rest === null ? '' : ` (no resto do credor: ${share(rest)})`;
  switch (kind) {
    case 'HIGH_EARLY_BREAK':
      return {
        title: 'Quebra alta nos primeiros 60 dias',
        evidence: `${s.brokenEarly} de ${s.matureAgreements} acordos maduros quebraram em até 60 dias: ${share(s.earlyBreakRate)}${restText}.`,
        action: 'Avalie entrada mínima maior ou menos parcelas nesta faixa: acordo que quebra não recupera e desgasta o devedor.',
      };
    case 'DISCOUNT_AT_LIMIT':
      return {
        title: 'Desconto no limite com boa adimplência',
        evidence: `${share(s.highEnvelopeShare)} dos acordos usaram 90% ou mais do desconto máximo, com quebra de ${share(s.earlyBreakRate)}${restText}.`,
        action: 'Avalie ampliar o desconto máximo desta faixa — de preferência com teste controlado (experimento de campanha) antes de publicar.',
      };
    case 'DISCOUNT_UNUSED':
      return {
        title: 'Desconto máximo pouco usado',
        evidence: `Os acordos usaram em média ${percent(s.averageEnvelopeUsage, 1)} do desconto máximo; só ${share(s.highEnvelopeShare)} chegaram a 90%.`,
        action: 'Avalie reduzir a escada de concessão desta faixa: o desconto extra não está sendo necessário para fechar.',
      };
    case 'PORTAL_WITHOUT_OFFER':
      return {
        title: 'Portal sem condições para muitos devedores',
        evidence: `${s.portalWithoutOffer} de ${s.portalSimulations} consultas de condições no portal (${share(s.portalWithoutOfferShare)}) não tiveram nenhuma oferta dentro da alçada do portal.`,
        action: 'Avalie o teto da alçada "Autonegociação (portal)" (valor do acordo, parcelas, entrada) ou as condições desta faixa.',
      };
    case 'CANNOT_AFFORD':
      return {
        title: 'Muitos "nenhuma condição cabe no meu orçamento"',
        evidence: `${s.cannotAfford} pedidos de atendimento pelo portal disseram que nenhuma condição cabia no orçamento.`,
        action: 'Avalie parcela mínima menor ou mais parcelas, sem perder de vista a quebra precoce.',
      };
  }
}

/** Quebra precoce do resto do credor (sem o recorte), só com a amostra madura que a regra usa; senão nula. */
function restBreakRate(s: SegmentHealth, total: SegmentHealth, minMature: number): number | null {
  const mature = total.matureAgreements - s.matureAgreements;
  return mature >= minMature ? (total.brokenEarly - s.brokenEarly) / mature : null;
}
