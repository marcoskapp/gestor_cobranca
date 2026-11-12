/**
 * Inteligência de contato (fase 1): perfil de contato do devedor e retrato agregado da operação.
 * Contrato de `ContactIntelligenceController` (docs/tecnico/desenho/inteligencia-de-contato.md).
 * Taxas são frações 0–1 (a do perfil é suavizada); instantes e datas em ISO.
 */

export type ContactChannel = 'PHONE' | 'WHATSAPP' | 'SMS' | 'EMAIL' | 'OTHER';
export type ContactConfidence = 'LOW' | 'MEDIUM' | 'HIGH';
export type InsightPersonType = 'INDIVIDUAL' | 'COMPANY';
/** Faixas de 3 horas no fuso da operação (`startHour` 6, 9, 12, 15, 18 ou 21). */
export type SlotStartHour = 6 | 9 | 12 | 15 | 18 | 21;
export type AttemptsBucket = '0-1' | '2-3' | '4-6' | '7-10' | '11-20' | '21+';

export interface ContactSlot {
  startHour: SlotStartHour;
  endHour: number;
  attempts: number;
  successes: number;
  rate: number;
}

export interface ChannelProfile {
  channel: ContactChannel;
  attempts: number;
  successes: number;
  debtorInitiated: number;
  reads: number;
  rate: number;
  explanation: string;
}

export interface ContactPointProfile {
  contactId: string | null;
  channel: ContactChannel;
  attempts: number;
  successes: number;
  debtorInitiated: number;
  reads: number;
  rate: number;
  confidence: ContactConfidence;
  bestSlot: ContactSlot | null;
  attemptsToFirstSuccess: number | null;
  lastSuccessAt: string | null;
  explanation: string;
  bestSlotExplanation: string | null;
}

/** `GET /scoring/debtors/{debtorId}/contact-profile`. */
export interface ContactProfileView {
  debtorId: string;
  caseId: string | null;
  totalAttempts: number;
  minAttempts: number;
  sufficient: boolean;
  emptyReason: string | null;
  preferredChannel: ContactChannel | null;
  preferredChannelExplanation: string;
  channels: ChannelProfile[];
  contacts: ContactPointProfile[];
  portfolioMedianAttemptsToFirstSuccess: number | null;
  notice: string;
  zone: string;
}

/**
 * Contato como as telas já o exibem (valor mascarado): `SheetContact` da ficha do caso (`contactId`) ou
 * `ContactView` da ficha do devedor (`id`). O cartão só junta pelo id; nenhum dado novo é pedido ao servidor.
 */
export interface KnownContact {
  contactId?: string;
  id?: string;
  type: string;
  maskedValue: string;
  label: string;
  whatsapp: boolean;
}

export interface InsightTotals {
  attempts: number;
  successes: number;
  rate: number | null;
  debtorInitiated: number;
  agreements: number;
  completed: number;
  broken: number;
  fulfillmentRate: number | null;
}

export interface HeatmapCell {
  /** ISO: 1 = segunda … 7 = domingo. */
  weekday: number;
  startHour: SlotStartHour;
  attempts: number;
  successes: number;
  rate: number | null;
}

export interface FirstSuccessPoint {
  /** 1 a 11; 11 = "11 ou mais". */
  attempts: number;
  contacts: number;
  cumulativeShare: number;
}

export interface ChannelInsight {
  channel: ContactChannel;
  attempts: number;
  successes: number;
  rate: number | null;
  debtorInitiated: number;
  reads: number;
}

export interface AgreementsByAttempts {
  bucket: AttemptsBucket;
  agreements: number;
  completed: number;
  broken: number;
  active: number;
  cancelled: number;
  fulfillmentRate: number | null;
}

export interface PersonTypeInsight {
  personType: InsightPersonType;
  attempts: number;
  successes: number;
  rate: number | null;
  agreements: number;
  completed: number;
  broken: number;
  fulfillmentRate: number | null;
}

/** `GET /scoring/contact-insights`. */
export interface ContactInsightsView {
  from: string;
  to: string;
  creditorId: string | null;
  portfolioId: string | null;
  personType: InsightPersonType | null;
  minAttempts: number;
  totals: InsightTotals;
  heatmap: HeatmapCell[];
  firstSuccessCurve: FirstSuccessPoint[];
  neverAnswered: number;
  channels: ChannelInsight[];
  agreementsByAttempts: AgreementsByAttempts[];
  medianAttemptsToAgreement: number | null;
  medianAttemptsToCompletedAgreement: number | null;
  medianDaysFirstContactToAgreement: number | null;
  byPersonType: PersonTypeInsight[];
  /** Contato novo (recebido em carga depois do cadastro) × antigo; opcional para respostas anteriores. */
  byContactOrigin?: ContactOriginInsight[];
  notice: string;
}

/** `NEW`: contato recebido numa carga depois do cadastro do devedor; `EXISTING`: os demais. */
export type ContactOrigin = 'NEW' | 'EXISTING';

export interface ContactOriginInsight {
  origin: ContactOrigin;
  attempts: number;
  successes: number;
  /** Nula abaixo do mínimo de tentativas. */
  rate: number | null;
}

export interface ContactInsightsFilter {
  from: string;
  to: string;
  creditorId: string | null;
  portfolioId: string | null;
  personType: InsightPersonType | null;
}
