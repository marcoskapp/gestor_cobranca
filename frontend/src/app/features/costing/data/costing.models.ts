/**
 * Contratos de `/api/v1/costing` (desenho: docs/tecnico/desenho/margem-por-carteira.md §10). Valores monetários como
 * número ou texto (`BigDecimal`), como no analytics.
 */

export type Money = number | string;

/** Categorias de custo da apuração diária (`costing.cost_daily.category`). */
export const COST_CATEGORIES = [
  'HUMAN_DIRECT',
  'AI',
  'TELEPHONY',
  'MESSAGING',
  'VARIABLE_COMMISSION',
  'LEGAL',
  'HUMAN_IDLE',
  'OVERHEAD',
] as const;
export type CostCategory = (typeof COST_CATEGORIES)[number];

/** Custo direto (entra na margem de contribuição); ociosidade e fixos só na margem líquida (decisão do responsável, Q2). */
export const DIRECT_CATEGORIES: readonly CostCategory[] = ['HUMAN_DIRECT', 'AI', 'TELEPHONY', 'MESSAGING', 'VARIABLE_COMMISSION', 'LEGAL'];
export const INDIRECT_CATEGORIES: readonly CostCategory[] = ['HUMAN_IDLE', 'OVERHEAD'];

/** Linha da margem (totais, credor, carteira ou mês). Taxas nulas quando não há base (receita, recuperado ou acordos zero). */
export interface MarginLine {
  recovered: Money;
  revenue: Money;
  costs: Record<CostCategory, Money>;
  directCost: Money;
  totalCost: Money;
  contributionMargin: Money;
  netMargin: Money;
  contributionMarginRate: Money | null;
  netMarginRate: Money | null;
  roi: Money | null;
  costPerRecovered: Money | null;
  settledAgreements: number;
  costPerSettledAgreement: Money | null;
  directHours: Money;
  idleHours: Money;
}

export interface PortfolioMargin extends MarginLine {
  /** Nulo: custo do credor sem carteira (ex.: custo fixo rateado direto ao credor). */
  portfolioId: string | null;
}

export interface CreditorMargin extends MarginLine {
  creditorId: string;
  /** Nome do credor (o servidor manda; a tela usa o lookup quando falta). */
  name?: string | null;
  portfolios: PortfolioMargin[];
}

export interface MonthMargin extends MarginLine {
  /** Mês (`AAAA-MM` ou primeiro dia, `AAAA-MM-01`). */
  month: string;
}

export type MarginWarningCode =
  | 'HOURS_WITHOUT_BAND'
  | 'MISSING_FX_RATE'
  | 'NO_FX_RATE'
  | 'MISSING_TARIFF'
  | 'OVERHEAD_WITHOUT_BASIS'
  | 'NOT_REFRESHED';

export interface MarginWarning {
  code: MarginWarningCode | string;
  count: number;
}

export interface MarginReport {
  period: { from: string; to: string };
  /** Fase 1: sempre `ESTIMATED` (uso × tarifa); a conciliação com a fatura é da fase 2. */
  basis: 'ESTIMATED' | 'RECONCILED' | string;
  totals: MarginLine;
  creditors: CreditorMargin[];
  /**
   * Custos sem credor (ociosidade de quem não atendeu ninguém no dia, chamadas sem credor, fixo sem base de rateio). Só
   * para quem vê todos os credores e sem filtro de credor ou carteira; já incluído em `totals`.
   */
  unallocated?: MarginLine | null;
  months: MonthMargin[];
  warnings: MarginWarning[];
}

/** Filtro do painel (URL e API). */
export interface MarginFilter {
  from: string;
  to: string;
  creditorIds: string[];
  portfolioIds: string[];
}

// ------------------------------------------------------------------ cadastros

export interface CostBand {
  id: string;
  name: string;
  /** Valor-hora da faixa; nulo para quem não tem `COST_RATE_MANAGE` (só o nome — LGPD, §3.1). */
  hourlyCost: Money | null;
  validFrom: string;
  validTo: string | null;
}

export interface CostBandInput {
  name: string;
  hourlyCost: number;
  validFrom: string;
}

/** Faixa de custo vigente de uma pessoa: só o nome da faixa, nunca o valor (LGPD — §3.1). */
export interface UserCostBand {
  userId: string;
  /** Nome de exibição, se o servidor mandar; senão a tela resolve pelo id. */
  userName?: string | null;
  /** Nulo: pessoa sem faixa vigente (as horas entram com custo zero). */
  bandName: string | null;
  validFrom: string | null;
  validTo: string | null;
}

export interface UserCostBandInput {
  bandName: string;
  validFrom: string;
}

export const TARIFF_CHANNELS = ['WHATSAPP_UTILITY', 'WHATSAPP_MARKETING', 'WHATSAPP_AUTHENTICATION', 'SMS', 'EMAIL'] as const;
export type TariffChannel = (typeof TARIFF_CHANNELS)[number];

export interface ChannelTariff {
  id: string;
  channel: TariffChannel;
  unitCost: Money;
  validFrom: string;
  validTo: string | null;
}

export interface ChannelTariffInput {
  channel: TariffChannel;
  unitCost: number;
  validFrom: string;
}

export interface FxRate {
  id?: string;
  /** Primeiro dia do mês (`AAAA-MM-01`). */
  month: string;
  usdBrl: Money;
}

export interface FxRateInput {
  month: string;
  usdBrl: number;
}

export const ALLOCATIONS = ['HOURS', 'RECOVERED', 'CASES', 'CREDITOR'] as const;
export type Allocation = (typeof ALLOCATIONS)[number];

export interface Overhead {
  id: string;
  month: string;
  description: string;
  amount: Money;
  allocation: Allocation;
  creditorId: string | null;
}

export interface OverheadInput {
  month: string;
  description: string;
  amount: number;
  allocation: Allocation;
  creditorId: string | null;
}
