import { Allocation, CostCategory, TariffChannel } from './costing.models';

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
export type CostTab = 'faixas' | 'tarifas' | 'cambio' | 'fixos';

export const COST_SETTINGS_ROUTE = '/organizacao/custos';
