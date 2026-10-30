import { StatementLine, TokenState } from './public-api.models';

export const TOKEN_STATE_LABELS: Record<TokenState, string> = {
  ACTIVE: 'Ativo',
  REVOKED: 'Revogado',
  EXPIRED: 'Expirado',
};

export const METRIC_LABELS: Record<StatementLine['metric'], string> = {
  MONTHLY_FEE: 'Mensalidade',
  REQUESTS: 'Chamadas cobráveis (2xx/3xx)',
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
  'usage_statement.already_closed': 'O extrato deste ciclo já foi fechado: informe o motivo para gerar nova revisão.',
  'usage_statement.period_open': 'O ciclo ainda está em curso: só ciclos encerrados podem ser fechados.',
  'usage_statement.nothing_to_close': 'Não há ciclo com consumo a fechar para este credor.',
  'usage_statement.period_invalid': 'Este não é o próximo ciclo a fechar do credor.',
};

/** Bytes por MB no faturamento e nas telas (unidade binária: 1 MB = 1.048.576 bytes). */
export const BYTES_PER_MB = 1_048_576;

/** 1.572.864 bytes → "1,5 MB" (unidades binárias, como no faturamento: 1 KB = 1.024 bytes, 1 MB = 1.048.576). */
export function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value)} ${units[unit]}`;
}

/** Linhas de IP digitadas (uma por linha, vírgula ou ponto e vírgula) → lista sem vazios nem repetidos. */
export function parseIpList(text: string): string[] {
  return [...new Set(text.split(/[\n,;]+/).map((v) => v.trim()).filter(Boolean))];
}

/** MB faturáveis de um total de bytes: inteiro, arredondado para cima (1 byte → 1 MB; 1.048.577 → 2 MB). */
export function billableMegabytes(bytes: number): number {
  return bytes <= 0 ? 0 : Math.ceil(bytes / BYTES_PER_MB);
}

/** "2026-03-11" e "2026-04-10" → "11/03/2026 a 10/04/2026". */
export function formatCycle(start: string, end: string): string {
  const br = (d: string) => d.split('-').reverse().join('/');
  return `${br(start)} a ${br(end)}`;
}

/**
 * Proporcionalidade do ciclo (decisão 621): `null` no ciclo regular; no de transição, "10 de 28 dias (fator 0,3571)".
 */
export function prorationLabel(cycleDays: number, referenceCycleDays: number): string | null {
  if (cycleDays === referenceCycleDays) {
    return null;
  }
  const factor = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 }).format(
    cycleDays / referenceCycleDays,
  );
  return `${cycleDays} de ${referenceCycleDays} dias (fator ${factor})`;
}
