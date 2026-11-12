import { Allocation, ChannelItem, CostCategory, ReconciliationStatus, TariffChannel } from './costing.models';

/** Categorias de custo na linguagem da gestão (sem códigos internos). */
export const CATEGORY_LABELS: Readonly<Record<CostCategory, string>> = {
  HUMAN_DIRECT: 'Pessoas (tempo direto)',
  AI: 'Inteligência artificial',
  TELEPHONY: 'Telefonia',
  MESSAGING: 'Mensagens',
  VARIABLE_COMMISSION: 'Comissão dos negociadores',
  LEGAL: 'Jurídico',
  HUMAN_IDLE: 'Ociosidade',
  OVERHEAD: 'Custos fixos rateados',
};

/** Nome curto para cabeçalho de coluna. */
export const CATEGORY_SHORT_LABELS: Readonly<Record<CostCategory, string>> = {
  HUMAN_DIRECT: 'Pessoas',
  AI: 'IA',
  TELEPHONY: 'Telefonia',
  MESSAGING: 'Mensagens',
  VARIABLE_COMMISSION: 'Comissão',
  LEGAL: 'Jurídico',
  HUMAN_IDLE: 'Ociosidade',
  OVERHEAD: 'Fixos',
};

export const CHANNEL_LABELS: Readonly<Record<TariffChannel, string>> = {
  WHATSAPP_UTILITY: 'WhatsApp — utilidade',
  WHATSAPP_MARKETING: 'WhatsApp — marketing',
  WHATSAPP_AUTHENTICATION: 'WhatsApp — autenticação',
  SMS: 'SMS',
  EMAIL: 'E-mail',
};

/** Unidade cobrada em cada canal (como o provedor cobra). */
export const CHANNEL_UNITS: Readonly<Record<TariffChannel, string>> = {
  WHATSAPP_UTILITY: 'por conversa iniciada pela assessoria',
  WHATSAPP_MARKETING: 'por conversa iniciada pela assessoria',
  WHATSAPP_AUTHENTICATION: 'por conversa iniciada pela assessoria',
  SMS: 'por mensagem',
  EMAIL: 'por mensagem',
};

export const ALLOCATION_LABELS: Readonly<Record<Allocation, string>> = {
  HOURS: 'Por horas trabalhadas (padrão)',
  RECOVERED: 'Por valor recuperado',
  CASES: 'Por casos trabalhados',
  CREDITOR: 'Direto a um credor',
};

export const ALLOCATION_HINTS: Readonly<Record<Allocation, string>> = {
  HOURS: 'Proporcional às horas de trabalho (direto e ociosidade) de cada credor no mês.',
  RECOVERED: 'Proporcional ao valor recuperado de cada credor no mês.',
  CASES: 'Proporcional aos casos trabalhados de cada credor no mês.',
  CREDITOR: 'O valor inteiro vai para o credor escolhido (ex.: equipe dedicada).',
};

/** Abas da configuração de custos (`?aba=`). */
export type CostTab = 'faixas' | 'tarifas' | 'cambio' | 'fixos' | 'faturas';

/** Itens de custo de canal faturados (fase 2, §13). */
export const CHANNEL_ITEM_LABELS: Readonly<Record<ChannelItem, string>> = {
  TELEPHONY: 'Telefonia',
  WHATSAPP: 'WhatsApp',
  SMS: 'SMS',
  EMAIL: 'E-mail',
  AI: 'Inteligência artificial',
};

/** Como a fatura de cada item é informada. */
export const CHANNEL_ITEM_HINTS: Readonly<Record<ChannelItem, string>> = {
  TELEPHONY: 'Fatura da operadora ou da plataforma de telefonia.',
  WHATSAPP: 'Fatura do provedor do WhatsApp (todas as categorias de conversa).',
  SMS: 'Fatura do provedor de SMS.',
  EMAIL: 'Fatura do provedor de e-mail.',
  AI: 'Fatura do provedor de inteligência artificial convertida para reais pela assessoria (ex.: valor pago no cartão).',
};

/** Selo da base dos custos no painel. */
export const RECONCILIATION_LABELS: Readonly<Record<ReconciliationStatus, string>> = {
  ESTIMATED: 'Estimado',
  PARTIAL: 'Parcialmente conciliado',
  RECONCILED: 'Conciliado',
};

export const COST_SETTINGS_ROUTE = '/organizacao/custos';

/** Painel "Margem por carteira" (`COST_VIEW`). */
export const MARGIN_ROUTE = '/paineis/margem';
