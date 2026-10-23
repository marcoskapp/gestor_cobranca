import {
  ComponentRates,
  ConcessionReason,
  DEBT_COMPONENTS,
  DebtComponent,
  FeeType,
  HonorariumBase,
  InterestMode,
  OfferView,
  OverrideGroup,
  PolicyOverride,
  ScheduleFrequency,
  ViolationKind,
} from './negotiation.models';

export const COMPONENT_LABELS: Record<DebtComponent, string> = {
  principal: 'Principal',
  correction: 'Correção',
  interest: 'Juros',
  fine: 'Multa',
  fees: 'Encargos',
  honorarium: 'Honorários',
};

/** Componentes como o motor de cálculo os nomeia nas linhas da memória (`DebtComponent`). */
export const ENGINE_COMPONENT_LABELS: Record<string, string> = {
  PRINCIPAL: 'Principal',
  CORRECTION: 'Correção',
  INTEREST: 'Juros',
  FINE: 'Multa',
  FEES: 'Encargos',
  HONORARIUM: 'Honorários',
  PAYMENT: 'Pagamentos',
};

export const INTEREST_MODE_LABELS: Record<InterestMode, string> = {
  SIMPLE: 'Simples (pro rata die)',
  COMPOUND: 'Compostos',
};

export const FEE_TYPE_LABELS: Record<FeeType, string> = {
  FIXED: 'Valor fixo por título',
  PERCENT_OF_PRINCIPAL: '% do principal',
};

export const HONORARIUM_BASE_LABELS: Record<HonorariumBase, string> = {
  PRINCIPAL: 'Principal',
  UPDATED_PRINCIPAL: 'Principal corrigido',
  UPDATED_DEBT: 'Dívida atualizada (padrão)',
};

export const FREQUENCY_LABELS: Record<ScheduleFrequency, string> = {
  MONTHLY: 'Mensal (mesmo dia)',
  EVERY_N_DAYS: 'A cada N dias',
};

export const OVERRIDE_GROUP_LABELS: Record<OverrideGroup, string> = {
  charges: 'Encargos',
  discountTiers: 'Descontos máximos',
  installments: 'Parcelamento',
  validity: 'Validade',
  ladder: 'Escada de concessões',
  breakRule: 'Quebra de acordo',
};

export const CONCESSION_REASON_LABELS: Record<ConcessionReason, string> = {
  PAYMENT_CAPACITY: 'Capacidade de pagamento abaixo da oferta',
  INCOME_LOSS: 'Desemprego ou perda de renda',
  HEALTH_OR_FAMILY: 'Doença ou evento familiar',
  COMPETING_OFFER: 'Oferta concorrente',
  LOW_RECOVERY_PROSPECT: 'Atraso longo / baixa chance de recuperação',
  IMMEDIATE_PAYMENT: 'Pagamento à vista imediato',
  OTHER: 'Outro motivo (descrever)',
};

export const VIOLATION_KIND_LABELS: Record<ViolationKind, string> = {
  POLICY: 'Fora da política',
  AUTHORITY: 'Acima da sua alçada',
  ELIGIBILITY: 'Elegibilidade',
};

export const AUTHORITY_SOURCE_LABELS: Record<string, string> = {
  ASSIGNED_CREDITOR: 'Atribuída para este credor',
  ASSIGNED_ALL: 'Atribuída para todos os credores',
  DEFAULT: 'Perfil padrão (sem atribuição)',
};

/** Motivos de título fora da negociação (`CaseEligibilityPort`). */
export const EXCLUDED_REASON_LABELS: Record<string, string> = {
  'title.not_found': 'Título não encontrado',
  'title.not_open': 'Título fora de cobrança',
  'title.no_balance': 'Sem saldo',
  'title.dispute_blocked': 'Em contestação',
  'portfolio.not_operable': 'Carteira fora de operação',
};

/** Percentual com vírgula decimal ("12,5%"). */
export function percent(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: digits }).format(value)}%`;
}

/** Descontos por componente em texto curto, só os não nulos ("Juros 100% · Multa 100%"). */
export function ratesText(rates: ComponentRates | null | undefined): string {
  if (!rates) {
    return '—';
  }
  const parts = DEBT_COMPONENTS.filter((c) => (rates[c] ?? 0) > 0).map((c) => `${COMPONENT_LABELS[c]} ${percent(rates[c])}`);
  return parts.length ? parts.join(' · ') : 'Sem desconto';
}

/** Alcance da sobreposição ("Carteira X · 91 a 180 dias"). */
export function overrideScope(o: Pick<PolicyOverride, 'portfolioId' | 'minDaysOverdue' | 'maxDaysOverdue'>, portfolioName: (id: string) => string): string {
  const parts: string[] = [];
  if (o.portfolioId) {
    parts.push(portfolioName(o.portfolioId));
  }
  if (o.minDaysOverdue !== null || o.maxDaysOverdue !== null) {
    const min = o.minDaysOverdue ?? 0;
    parts.push(o.maxDaysOverdue === null ? `a partir de ${min} dias de atraso` : `${min} a ${o.maxDaysOverdue} dias de atraso`);
  }
  return parts.join(' · ') || 'Sem seletor';
}

/** Forma de pagamento ("À vista", "Entrada + 5 parcelas"). */
export function planText(offer: Pick<OfferView, 'installments' | 'downPayment'>): string {
  if (offer.installments <= 1) {
    return 'À vista';
  }
  return offer.downPayment ? `Entrada + ${offer.installments - 1} parcelas` : `${offer.installments} parcelas`;
}

/** Regras das linhas da memória de cálculo (`CalculationLine.Rule`). */
export const RULE_LABELS: Record<string, string> = {
  PRINCIPAL_BALANCE: 'Saldo de principal em aberto',
  INDEX_CORRECTION: 'Correção pelo índice: base × (fator − 1)',
  INTEREST_SIMPLE: 'Juros simples: base × taxa × dias ÷ 30',
  INTEREST_COMPOUND: 'Juros compostos: base × ((1 + taxa)^(dias ÷ 30) − 1)',
  FINE: 'Multa: base × percentual',
  FEE_FIXED: 'Encargo de valor fixo',
  FEE_PERCENT: 'Encargo: principal × percentual',
  RECOGNIZED_CHARGE: 'Encargo já reconhecido no razão',
  HONORARIUM: 'Honorários: base × percentual',
  DISCOUNT: 'Desconto concedido',
};
