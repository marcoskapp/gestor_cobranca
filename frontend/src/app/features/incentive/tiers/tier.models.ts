/** Classificação de desempenho do negociador (`/api/v1/incentive/tiers`, distribuicao-por-desempenho.md §11.2). */

export type TierLevel = 'A' | 'B' | 'C' | 'D';
export type TierPeriodicity = 'WEEKLY' | 'MONTHLY';
export type TierRoundStatus = 'PROPOSED' | 'PUBLISHED' | 'EXPIRED';
export type QuartileKey = 'Q1' | 'Q2' | 'Q3' | 'Q4';

/** Qualidade ("Q4".."Q1") → eficiência ("Q4".."Q1") → nível. */
export type TierMatrix = Record<QuartileKey, Record<QuartileKey, TierLevel>>;

export interface TierSettings {
  windowDays: number;
  minEvaluations: number;
  minCases: number;
  qualityGate: number;
  matrix: TierMatrix;
  defaultTier: TierLevel;
  periodicity: TierPeriodicity;
  reviewBusinessDays: number;
}

export interface TierSettingsView extends TierSettings {
  /** A assessoria já gravou parâmetros (senão, são os padrões). */
  customized: boolean;
}

export interface TierRoundSummary {
  id: string;
  periodFrom: string;
  periodTo: string;
  status: TierRoundStatus;
  origin: 'SCHEDULED' | 'MANUAL';
  reviewDeadline: string;
  createdAt: string;
  publishedAt: string | null;
  validFrom: string | null;
  expiredAt: string | null;
  /** Pessoas no escopo de quem vê. */
  people: number;
  tierCounts: Record<TierLevel, number>;
  adjusted: number;
}

export interface TierPerson {
  userId: string;
  name: string | null;
  qualityQuartile: number | null;
  efficiencyQuartile: number | null;
  qualityGateFailed: boolean;
  evaluations: number;
  qualityAverage: number | null;
  cases: number;
  recoveryRatio: number | null;
  proposedTier: TierLevel;
  finalTier: TierLevel;
  adjusted: boolean;
  adjustedByName: string | null;
  adjustedAt: string | null;
  adjustmentReason: string | null;
  /** É a própria pessoa (ninguém ajusta o próprio nível). */
  self: boolean;
}

export interface TierRoundDetail {
  round: TierRoundSummary;
  settings: TierSettingsView;
  people: TierPerson[];
  canAdjust: boolean;
  canPublish: boolean;
}

export interface MyTier {
  status: 'NONE' | 'CLASSIFIED' | 'NOT_CLASSIFIED';
  tier: TierLevel;
  defaultTier: TierLevel;
  validFrom: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  qualityQuartile: number | null;
  efficiencyQuartile: number | null;
  qualityGateFailed: boolean;
  missingQuality: boolean;
  missingEfficiency: boolean;
  evaluations: number;
  cases: number;
  minEvaluations: number;
  minCases: number;
  qualityGate: number;
  adjusted: boolean;
  matrix: TierMatrix;
}
