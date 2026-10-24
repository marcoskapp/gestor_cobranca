/** Rótulos em pt-BR dos códigos dos relatórios. */

export const DELAY_BUCKET_LABELS: Record<string, string> = {
  NOT_DUE: 'A vencer',
  D0_30: 'Até 30 dias',
  D31_90: '31 a 90 dias',
  D91_180: '91 a 180 dias',
  D181_360: '181 a 360 dias',
  D361_PLUS: 'Mais de 360 dias',
  UNKNOWN: 'Não informado',
};

export const ORIGIN_LABELS: Record<string, string> = {
  HUMAN: 'Negociador',
  ASSISTANT: 'Assistente de IA',
  PORTAL: 'Portal do devedor',
  CAMPAIGN: 'Campanha',
  NO_AGREEMENT: 'Sem acordo (título avulso)',
  OTHER: 'Outra origem',
};

export const CHANNEL_LABELS: Record<string, string> = {
  PHONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  SMS: 'SMS',
  PORTAL: 'Portal',
  IN_PERSON: 'Presencial',
  CHAT: 'Chat',
  LETTER: 'Carta',
  UNKNOWN: 'Não informado',
};

export const CASE_STATUS_LABELS: Record<string, string> = {
  OPEN: 'Em aberto',
  IN_NEGOTIATION: 'Em negociação',
  PROMISE: 'Promessa',
  AGREEMENT: 'Em acordo',
  DISPUTED: 'Contestado',
  SUSPENDED: 'Suspenso',
  LEGAL: 'No jurídico',
};

export const GROUP_BY_LABELS: Record<string, string> = {
  CREDITOR: 'Credor',
  PORTFOLIO: 'Carteira',
  OPERATOR: 'Operador',
  TEAM: 'Equipe',
  CHANNEL: 'Canal',
  ORIGIN: 'Origem',
  DELAY_BUCKET: 'Faixa de atraso',
  DAY: 'Dia',
  WEEK: 'Semana',
  MONTH: 'Mês',
};

/** Motivos de indicador indisponível (texto curto para o cartão e a tabela). */
export const UNAVAILABLE_LABELS: Record<string, string> = {
  no_denominator: 'sem base no período',
  no_source: 'sem dados de origem',
  not_segmentable: 'não se aplica a este recorte',
  privacy_minimum: 'menos de 3 extratos (privacidade)',
  out_of_scope: 'fora do seu escopo',
  no_full_month: 'requer mês inteiro',
};

export const FAILURE_LABELS: Record<string, string> = {
  PROVIDER_TIMEOUT: 'Provedor sem resposta',
  UNKNOWN: 'Não informado',
};

export const DATASET_LABELS: Record<string, string> = {
  recovery_daily: 'Recuperação',
  agreement_cohort: 'Safra de acordos',
  portfolio_position: 'Posição da carteira',
  case_position: 'Posição dos casos',
};

/** Rótulo de uma chave de agrupamento: códigos conhecidos traduzidos, demais vêm do servidor. */
export function groupLabel(groupBy: string, key: string, serverLabel: string): string {
  switch (groupBy) {
    case 'ORIGIN':
      return ORIGIN_LABELS[key] ?? serverLabel;
    case 'CHANNEL':
      return CHANNEL_LABELS[key] ?? serverLabel;
    case 'DELAY_BUCKET':
      return DELAY_BUCKET_LABELS[key] ?? serverLabel;
    case 'MONTH':
      return monthLabel(key);
    case 'DAY':
    case 'WEEK':
      return dayLabel(key, groupBy === 'WEEK');
    default:
      return serverLabel;
  }
}

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];

export function monthLabel(key: string): string {
  const [y, m] = key.split('-');
  const index = Number(m) - 1;
  return index >= 0 && index < 12 ? `${MONTHS[index]}/${y.slice(2)}` : key;
}

export function dayLabel(key: string, week = false): string {
  const [, m, d] = key.split('-');
  return d && m ? `${week ? 'sem. ' : ''}${d}/${m}` : key;
}
