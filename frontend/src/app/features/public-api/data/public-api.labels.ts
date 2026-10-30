import { StatementLine, TokenState } from './public-api.models';

export const TOKEN_STATE_LABELS: Record<TokenState, string> = {
  ACTIVE: 'Ativo',
  REVOKED: 'Revogado',
  EXPIRED: 'Expirado',
};

export const METRIC_LABELS: Record<StatementLine['metric'], string> = {
  MONTHLY_FEE: 'Mensalidade',
  REQUESTS: 'Requisições cobráveis',
  MB_IN: 'Dados recebidos (MB)',
  MB_OUT: 'Dados enviados (MB)',
  RECORDS: 'Registros processados',
};

/** Mensagens dos códigos de erro do módulo (o restante cai na mensagem do servidor). */
export const PUBLIC_API_ERROR_LABELS: Record<string, string> = {
  'integration_token.scope_not_grantable': 'Seu perfil não pode conceder um dos escopos escolhidos.',
  'integration_token.allowed_ip_invalid': 'Uma das faixas de IP é inválida. Use IP ou CIDR (ex.: 200.10.0.0/24).',
  'integration_token.allowed_ip_too_wide': 'Faixa de IP ampla demais: use no mínimo /8 (IPv4) ou /32 (IPv6).',
  'integration_token.expiry_in_past': 'A expiração precisa ser no futuro.',
  'integration_token.already_revoked': 'Este token já está revogado.',
  'integration_token.not_active': 'Só tokens ativos podem ser rotacionados.',
  'creditor.inactive': 'O credor está inativo.',
  'price_plan.retroactive': 'A vigência não pode começar antes do mês corrente.',
  'price_plan.effective_from_invalid': 'A vigência começa no primeiro dia de um mês.',
  'usage_statement.already_closed': 'O extrato deste mês já foi fechado: informe o motivo para gerar nova revisão.',
  'usage_statement.period_open': 'Só meses encerrados podem ser fechados.',
};

/** 1.234.567 bytes → "1,2 MB" (unidades decimais, como no faturamento). */
export function formatBytes(bytes: number): string {
  const units = ['B', 'kB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value)} ${units[unit]}`;
}

/** Linhas de IP digitadas (uma por linha, vírgula ou ponto e vírgula) → lista sem vazios nem repetidos. */
export function parseIpList(text: string): string[] {
  return [...new Set(text.split(/[\n,;]+/).map((v) => v.trim()).filter(Boolean))];
}
