import { AgentView, CallView, CampaignSettings, CampaignStats, PresenceStatus } from './telephony.models';

/** Sinal do cliente para manter o operador na telefonia (`telefonia.md` §7: a cada 30 s; sai após 90 s). */
export const HEARTBEAT_MS = 30_000;
/** Consulta da presença em campanha de discagem (chamada distribuída pelo discador). */
export const PRESENCE_POLL_MS = 5_000;
/** Consulta da chamada em andamento (eventos do provedor chegam ao servidor). */
export const CALL_POLL_MS = 2_000;
/** Atualização do painel de supervisão (tempo quase real). */
export const SUPERVISION_POLL_MS = 5_000;
/** `app.telephony.tabulation-deadline` (padrão 15 min): depois disso a tabulação é automática. */
export const TABULATION_DEADLINE_MS = 15 * 60_000;
/** Abandono máximo padrão de mercado (Ofcom/FTC). */
export const DEFAULT_MAX_ABANDON = 3;

const ACTIVE: readonly string[] = ['QUEUED', 'DIALING', 'RINGING', 'ANSWERED'];

/** A chamada ainda está em andamento (pode ser encerrada pelo operador). */
export function isCallActive(call: Pick<CallView, 'status'>): boolean {
  return ACTIVE.includes(call.status);
}

/** A chamada do operador terminou e aguarda a tabulação dele (pós-atendimento). */
export function awaitsTabulation(call: Pick<CallView, 'status' | 'interactionState'>): boolean {
  return call.interactionState === 'AWAITING_TABULATION' && !isCallActive(call);
}

/** Negada pelo porteiro de contato na verificação prévia (não discou, não reservou o operador). */
export function isDenied(call: Pick<CallView, 'status' | 'failureCode'>): boolean {
  return call.status === 'FAILED' && call.failureCode === 'CONTACT_DENIED';
}

/** Instante da tabulação automática (fim da chamada + prazo), ou nulo se a chamada não terminou. */
export function tabulationDeadline(call: Pick<CallView, 'endedAt'>, deadlineMs = TABULATION_DEADLINE_MS): Date | null {
  return call.endedAt ? new Date(new Date(call.endedAt).getTime() + deadlineMs) : null;
}

/** "mm:ss" (ou "h:mm:ss"); valores negativos viram "00:00". */
export function clockText(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Duração em texto curto ("2 min 05 s", "45 s"); nulo vira "—". */
export function durationText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return '—';
  }
  const s = Math.round(seconds);
  if (s < 60) {
    return `${s} s`;
  }
  return `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
}

export interface PresenceActions {
  login: boolean;
  available: boolean;
  pause: boolean;
  logout: boolean;
  /** Motivo por que pausa/saída não estão disponíveis (pós-atendimento ou em chamada). */
  blocked: string | null;
}

/** O que o operador pode fazer na barra de presença (as transições `ON_CALL`/`WRAP_UP` vêm das chamadas). */
export function presenceActions(agent: Pick<AgentView, 'status' | 'active'> | null): PresenceActions {
  const status: PresenceStatus = agent?.status ?? 'OFFLINE';
  const active = agent?.active ?? false;
  return {
    login: active && status === 'OFFLINE',
    available: status === 'PAUSED',
    pause: status === 'AVAILABLE',
    logout: status === 'AVAILABLE' || status === 'PAUSED',
    blocked: status === 'WRAP_UP' ? 'Tabule a chamada para ficar disponível, pausar ou sair.' : status === 'ON_CALL' ? 'Em chamada: pausa e saída depois de encerrar e tabular.' : null,
  };
}

/** Problemas dos parâmetros da campanha (espelho de `DialerCampaign`; o servidor repete tudo). */
export function campaignProblems(s: CampaignSettings, code: string | null = null): string[] {
  const out: string[] = [];
  if (code !== null && !/^[A-Z0-9_-]{2,40}$/.test(code.trim().toUpperCase())) {
    out.push('Código: 2 a 40 letras, dígitos, _ ou -.');
  }
  if (!s.name?.trim() || s.name.length > 120) {
    out.push('Nome obrigatório (até 120 caracteres).');
  }
  const ratio = s.maxDialRatio ?? 1;
  if (s.mode === 'PREDICTIVE' && (ratio < 1 || ratio > 5)) {
    out.push('Razão máxima de discagem entre 1 e 5 chamadas por operador livre.');
  }
  const abandon = s.maxAbandonRate ?? DEFAULT_MAX_ABANDON;
  if (abandon <= 0 || abandon > 10) {
    out.push('Abandono máximo acima de 0 e até 10% (padrão de mercado: 3%).');
  }
  const attempts = s.maxAttempts ?? 3;
  if (!Number.isInteger(attempts) || attempts < 1 || attempts > 20) {
    out.push('Tentativas por caso entre 1 e 20.');
  }
  const retry = s.retryIntervalMinutes ?? 120;
  if (!Number.isInteger(retry) || retry < 5 || retry > 10_080) {
    out.push('Intervalo entre tentativas de 5 minutos a 7 dias.');
  }
  const ring = s.ringTimeoutSeconds ?? 25;
  if (!Number.isInteger(ring) || ring < 10 || ring > 60) {
    out.push('Tempo de toque entre 10 e 60 segundos.');
  }
  if (!!s.windowStart !== !!s.windowEnd) {
    out.push('Janela própria: informe início e fim (ou deixe as duas em branco).');
  } else if (s.windowStart && s.windowEnd && s.windowStart >= s.windowEnd) {
    out.push('Janela própria: o início deve ser antes do fim (no mesmo dia).');
  }
  return out;
}

export type AbandonLevel = 'ok' | 'near' | 'limit';

/** Nível do abandono em relação ao máximo da campanha (80% do máximo = perto do limite). */
export function abandonLevel(stats: Pick<CampaignStats, 'abandonRate' | 'maxAbandonRate'>): AbandonLevel {
  const rate = stats.abandonRate ?? 0;
  if (rate >= stats.maxAbandonRate) {
    return 'limit';
  }
  return rate >= stats.maxAbandonRate * 0.8 ? 'near' : 'ok';
}

/** Percentual "2,4%" (nulo = "—"). */
export function percentText(value: number | null | undefined, digits = 1): string {
  return value === null || value === undefined ? '—' : `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(value)}%`;
}

/** "HH:mm" → "HH:mm:00" (LocalTime) e vice-versa. */
export function toLocalTime(value: string | null | undefined): string | null {
  return value && /^\d{2}:\d{2}$/.test(value) ? `${value}:00` : value || null;
}

export function fromLocalTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '';
}
