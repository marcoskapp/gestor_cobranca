import { UrlCodec } from '../../../shared/data/url-state';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { integrationTokenText } from '../data/payment.rules';
import { BatchLabel, ImportSituation, ReportedPaymentItem, ReportingChannel } from './creditor-reported.api';

/** Período máximo da consulta (o servidor recusa acima disso). */
export const MAX_PERIOD_DAYS = 366;
/** Período padrão ao abrir a tela. */
export const DEFAULT_PERIOD_DAYS = 30;

export const CHANNEL_LABELS: Record<ReportingChannel, string> = {
  INTEGRATION: 'Integração do credor',
  CREDITOR_PORTAL: 'Portal do credor',
  OPERATOR: 'Operador da assessoria',
};

export const IMPORT_SITUATION_LABELS: Record<ImportSituation, string> = {
  SETTLED_BY_IMPORT: 'Saldo zerado pela carga do credor',
  REPORTED_PAID: 'Credor informou pagamento',
  REPORTED_CANCELLED: 'Credor informou cancelamento',
};

/** Período padrão: os últimos 30 dias até hoje (datas locais `yyyy-MM-dd`). */
export function defaultPeriod(today = new Date()): { from: string; to: string } {
  const from = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (DEFAULT_PERIOD_DAYS - 1));
  return { from: toLocalDate(from)!, to: toLocalDate(today)! };
}

/**
 * Problema do período para o usuário (nulo = válido): obrigatório, fim não antes do início e até 366 dias. A consulta
 * só vai ao servidor com o período válido.
 */
export function periodProblem(from: string | null, to: string | null): string | null {
  const start = fromLocalDate(from);
  const end = fromLocalDate(to);
  if (!start || !end) {
    return 'Informe o período (data inicial e final).';
  }
  if (end < start) {
    return 'A data final é anterior à inicial.';
  }
  const days = Math.round((end.getTime() - start.getTime()) / 86_400_000);
  if (days > MAX_PERIOD_DAYS) {
    return `O período pode ter até ${MAX_PERIOD_DAYS} dias. Reduza o intervalo para consultar.`;
  }
  return null;
}

/** Faixa de valor inválida (mínimo maior que o máximo). */
export function amountRangeProblem(min: number | null, max: number | null): string | null {
  return min !== null && max !== null && min > max ? 'O valor mínimo é maior que o máximo.' : null;
}

/** Valor em reais na URL (`valor-min=150.5`): só número com até duas casas. */
export function urlMoney(param: string): UrlCodec<number | null> {
  return {
    param,
    parse: (raw) => (/^\d{1,13}(\.\d{1,2})?$/.test(raw) ? Number(raw) : undefined),
    format: (v) => (v === null || v === undefined || Number.isNaN(v) ? null : String(v)),
  };
}

/** Canal com o token (rótulo da decisão 1520); sem o token (perfil sem a permissão) só o canal. */
export function channelText(item: Pick<ReportedPaymentItem, 'channel' | 'token'>): string {
  if (item.channel === 'INTEGRATION' && item.token) {
    const t = item.token;
    return `Integração do credor · ${integrationTokenText({
      tokenId: t.id,
      tokenName: t.found ? t.name : null,
      tokenPrefix: t.prefix,
      tokenRevoked: t.revoked,
      tokenState: t.found ? t.state : 'NOT_FOUND',
    })}`;
  }
  return CHANNEL_LABELS[item.channel];
}

/** Lote da carga: rótulo (data e layout, nunca o nome do arquivo); sem o rótulo (sem `IMPORT_VIEW`), só "Carga do credor". */
export function batchText(label: BatchLabel | undefined, formatDate: (iso: string) => string): string {
  if (!label) {
    return 'Carga do credor';
  }
  const layout = label.layoutCode ? ` · layout ${label.layoutCode}${label.layoutVersion ? ' v' + label.layoutVersion : ''}` : '';
  return `Carga de ${formatDate(label.createdAt)}${layout}`;
}

/**
 * Período dos atalhos de outras telas (ficha do token, ficha do lote): do dia do registro até hoje, limitado aos
 * últimos {@link MAX_PERIOD_DAYS} dias para a consulta não ser recusada. `since` é um instante ISO.
 */
export function periodSince(since: string, today = new Date()): { de: string; ate: string } {
  const start = new Date(since);
  const limit = new Date(today.getFullYear(), today.getMonth(), today.getDate() - MAX_PERIOD_DAYS);
  const from = Number.isNaN(start.getTime()) || start < limit ? limit : new Date(start.getFullYear(), start.getMonth(), start.getDate());
  return { de: toLocalDate(from)!, ate: toLocalDate(today)! };
}
