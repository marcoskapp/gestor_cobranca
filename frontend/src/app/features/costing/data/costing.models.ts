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
  | 'NOT_REFRESHED'
  | 'INVOICE_WITHOUT_USAGE';

export interface MarginWarning {
  code: MarginWarningCode | string;
  count: number;
}

export interface MarginReport {
  period: { from: string; to: string };
  /** `ESTIMATED` (uso × tarifa) ou `RECONCILED` (custos de canal com o ajuste pela fatura — §13). */
  basis: MarginBasis | string;
  totals: MarginLine;
  creditors: CreditorMargin[];
  /**
   * Custos sem credor (ociosidade de quem não atendeu ninguém no dia, chamadas sem credor, fixo sem base de rateio). Só
   * para quem vê todos os credores e sem filtro de credor ou carteira; já incluído em `totals`.
   */
  unallocated?: MarginLine | null;
  months: MonthMargin[];
  warnings: MarginWarning[];
  /** Situação da conciliação com as faturas de canal e o ajuste por categoria (fase 2, §13); ausente em servidor antigo. */
  reconciliation?: MarginReconciliation | null;
}

// ------------------------------------------------------------------ conciliação com a fatura (fase 2, §13)

/** Base dos custos de canal no painel (`?basis=`): estimada (padrão) ou conciliada com a fatura. */
export type MarginBasis = 'ESTIMATED' | 'RECONCILED';

/** Itens de custo de canal faturados (um valor de fatura por mês × item). */
export const CHANNEL_ITEMS = ['TELEPHONY', 'WHATSAPP', 'SMS', 'EMAIL', 'AI'] as const;
export type ChannelItem = (typeof CHANNEL_ITEMS)[number];

/** Categoria de custo de cada item (o ajuste da fatura entra na categoria do item). */
export const CHANNEL_ITEM_CATEGORY: Readonly<Record<ChannelItem, CostCategory>> = {
  TELEPHONY: 'TELEPHONY',
  WHATSAPP: 'MESSAGING',
  SMS: 'MESSAGING',
  EMAIL: 'MESSAGING',
  AI: 'AI',
};

/** Categorias de custo que recebem o ajuste pela fatura. */
export const CHANNEL_CATEGORIES: readonly CostCategory[] = ['TELEPHONY', 'MESSAGING', 'AI'];

/** Nenhuma fatura no período, parte dos itens com uso faturada, ou todos. */
export type ReconciliationStatus = 'ESTIMATED' | 'PARTIAL' | 'RECONCILED';

export interface MonthReconciliation {
  /** `AAAA-MM`. */
  month: string;
  status: ReconciliationStatus;
  /** Itens com fatura no mês. */
  invoiced: ChannelItem[];
  /** Itens com uso estimado no mês ainda sem fatura. */
  pending: ChannelItem[];
}

/** Fatura de um item no mês ao lado da estimativa da assessoria inteira (só para quem vê todos os credores). */
export interface InvoiceLine {
  month: string;
  item: ChannelItem;
  estimated: Money;
  invoiced: Money;
  adjustment: Money;
}

export interface MarginReconciliation {
  status: ReconciliationStatus;
  months: MonthReconciliation[];
  /** Ajuste pela fatura por categoria de canal no recorte visível (já incluído nos custos na base conciliada). */
  adjustments: Partial<Record<CostCategory, Money>>;
  /** Nulo para escopo restrito ou com filtro de credor/carteira (o valor da fatura é da assessoria). */
  invoices: InvoiceLine[] | null;
}

// ------------------------------------------------------------------ tendência de 6 meses (`GET /margin/trend`, §14.1)

/** Situação do mês pela apuração: completo, apurado só até parte do mês ou ainda não apurado. */
export type TrendMonthStatus = 'COMPLETE' | 'PARTIAL' | 'NOT_REFRESHED';

export interface TrendMonth {
  /** `AAAA-MM`. */
  month: string;
  status: TrendMonthStatus | string;
}

/** Ponto do mês. Mês não apurado ou sem nenhum fato: tudo nulo (nunca zero); taxa nula sem receita. */
export interface TrendPoint {
  month: string;
  recovered: Money | null;
  revenue: Money | null;
  netMargin: Money | null;
  netMarginRate: Money | null;
}

export interface PortfolioTrend {
  portfolioId: string | null;
  points: TrendPoint[];
}

export interface CreditorTrend {
  creditorId: string;
  /** Um ponto por mês de `months`, na mesma ordem. */
  points: TrendPoint[];
  portfolios: PortfolioTrend[];
}

/** Os 6 meses que terminam no mês do fim do período filtrado (o início não muda a janela). */
export interface MarginTrend {
  period: { from: string; to: string };
  months: TrendMonth[];
  computedUntil: string | null;
  creditors: CreditorTrend[];
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

/** Fatura de canal do mês (`/costing/invoices`), com a estimativa do item na assessoria inteira. */
export interface ChannelInvoice {
  id: string;
  /** `AAAA-MM`. */
  month: string;
  item: ChannelItem;
  amount: Money;
  supplier: string | null;
  note: string | null;
  estimated: Money;
  /** Fatura − estimativa (o que o ajuste rateia). */
  adjustment: Money;
  updatedAt?: string | null;
}

/** Estimativa do item no mês (`/costing/invoices/estimates`), com a fatura quando houver. */
export interface ItemEstimate {
  month: string;
  item: ChannelItem;
  estimated: Money;
  /** Uso: minutos (telefonia), conversas ou mensagens, dólares (IA). */
  quantity: Money;
  unit: 'MINUTES' | 'UNITS' | 'USD' | string;
  invoiceId: string | null;
  invoiced: Money | null;
}

export interface ChannelInvoiceInput {
  /** `AAAA-MM`. */
  month: string;
  item: ChannelItem;
  amount: number;
  supplier: string | null;
  note: string | null;
}
