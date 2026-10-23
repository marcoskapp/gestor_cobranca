/**
 * Regras de tela da operação de cobrança (funções puras, testadas em `collection.rules.spec.ts`).
 * Datas e horas de retorno são do **fuso de operação** (`America/Sao_Paulo`, `TimeConfig.BUSINESS_ZONE`).
 */
import { CaseView, ChannelAvailability, ContactDenialReason, TabulationView, TaskView } from './collection.models';

export const BUSINESS_ZONE = 'America/Sao_Paulo';

/** Partes da data/hora atual no fuso de operação (independe do fuso do navegador). */
export interface ZonedNow {
  date: string;
  /** "HH:mm" */
  time: string;
  /** 0 = domingo … 6 = sábado */
  weekDay: number;
}

export function zonedNow(now: Date = new Date(), zone = BUSINESS_ZONE): ZonedNow {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    weekday: 'short',
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  return { date: `${get('year')}-${get('month')}-${get('day')}`, time: `${get('hour')}:${get('minute')}`, weekDay: days.indexOf(get('weekday')) };
}

// --------------------------------------------------------------------- posso contatar agora?

/** Motivos do porteiro de contato (`ContactGate.DenialReason`) em linguagem da operação. */
export const CONTACT_DENIAL_LABELS: Record<ContactDenialReason, string> = {
  RESTRICTED_DEBTOR: 'Restrição do devedor',
  RESTRICTED_CHANNEL: 'Restrição no canal',
  RESTRICTED_CONTACT_VALUE: 'Restrição neste contato',
  OUTSIDE_WINDOW: 'Fora do horário permitido',
  HOLIDAY: 'Feriado',
  DAILY_LIMIT: 'Limite diário de tentativas',
  WEEKLY_LIMIT: 'Limite semanal de tentativas',
  MIN_INTERVAL: 'Intervalo mínimo entre tentativas',
  DEBTOR_ANONYMIZED: 'Devedor anonimizado (LGPD)',
};

export type AvailabilityKind = 'allowed' | 'wait' | 'blocked' | 'suspended';

export interface AvailabilityState {
  kind: AvailabilityKind;
  /** Rótulo curto do chip ("Permitido", "Aguardar", "Restrito", "Suspenso"). */
  label: string;
  /** Detalhe curto ("a partir de 08:00", "restrição vigente"). */
  detail: string;
  /** Motivos completos, para a dica. */
  text: string;
}

/**
 * Situação de um canal a partir da decisão do servidor (`GET /cases/{id}/contact-availability`).
 * A tela não calcula janela, feriado nem limites: só apresenta o que o porteiro decidiu.
 */
export function availabilityState(channel: ChannelAvailability, caseEligible: boolean, now: Date = new Date()): AvailabilityState {
  const reasons = channel.reasons.map((r) => CONTACT_DENIAL_LABELS[r as ContactDenialReason] ?? r);
  if (channel.allowed) {
    return { kind: 'allowed', label: 'Permitido', detail: 'agora', text: 'Contato permitido agora' };
  }
  if (channel.reasons.length === 0 && !caseEligible) {
    return { kind: 'suspended', label: 'Suspenso', detail: 'caso inelegível', text: 'Contato suspenso pela situação do caso' };
  }
  if (!channel.nextAllowedAt) {
    return { kind: 'blocked', label: 'Restrito', detail: 'sem previsão', text: reasons.join(' · ') || 'Contato não permitido' };
  }
  const when = whenText(channel.nextAllowedAt, now);
  return { kind: 'wait', label: 'Aguardar', detail: `a partir de ${when}`, text: `${reasons.join(' · ')} — permitido a partir de ${when}` };
}

/** "14:00" se for hoje (fuso de operação), senão "24/10 08:00". */
function whenText(instant: string, now: Date): string {
  const at = new Date(instant);
  const time = new Intl.DateTimeFormat('pt-BR', { timeZone: BUSINESS_ZONE, hour: '2-digit', minute: '2-digit' }).format(at);
  if (zonedNow(at).date === zonedNow(now).date) {
    return time;
  }
  const day = new Intl.DateTimeFormat('pt-BR', { timeZone: BUSINESS_ZONE, day: '2-digit', month: '2-digit' }).format(at);
  return `${day} ${time}`;
}

// --------------------------------------------------------------------- datas locais

/** `LocalDateTime` ("yyyy-MM-ddTHH:mm:00") a partir da data escolhida e de "HH:mm". */
export function toLocalDateTime(date: Date | null, time: string | null): string | null {
  if (!date || !time || !/^\d{2}:\d{2}$/.test(time)) {
    return null;
  }
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}T${time}:00`;
}

/** "dd/MM/yyyy HH:mm" de um `LocalDateTime`, sem conversão de fuso. */
export function formatLocalDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value);
  return match ? `${match[3]}/${match[2]}/${match[1]} ${match[4]}:${match[5]}` : value;
}

/** O horário local ("yyyy-MM-ddTHH:mm") já passou no fuso de operação. */
export function isPastLocal(value: string, now: ZonedNow): boolean {
  return value.slice(0, 16) < `${now.date}T${now.time}`;
}

/** Retorno atrasado (status MISSED) ou ainda pendente com horário vencido. */
export function taskLate(task: TaskView, now: ZonedNow): boolean {
  return task.status === 'MISSED' || (task.status === 'PENDING' && isPastLocal(task.dueAtLocal, now));
}

// --------------------------------------------------------------------- interação

export interface InteractionDraft {
  tabulation: TabulationView | null;
  note: string;
  promiseAmount: number | null;
  promiseDate: string | null;
  callbackAt: string | null;
}

export interface InteractionLimits {
  openBalance: number;
  /** Data de hoje (fuso de operação) e prazo máximo da promessa (`promiseMaxDays`). */
  today: string;
  promiseMaxDays: number;
}

/** Problemas que impedem o envio, na ordem do formulário (o servidor repete as validações). */
export function interactionProblems(draft: InteractionDraft, limits: InteractionLimits, now: ZonedNow): string[] {
  const problems: string[] = [];
  const t = draft.tabulation;
  if (!t) {
    return ['Escolha a tabulação (resultado do contato).'];
  }
  if (t.requiresNote && !draft.note.trim()) {
    problems.push(`A tabulação "${t.name}" exige observação.`);
  }
  if (t.promise) {
    if (!draft.promiseAmount || draft.promiseAmount <= 0) {
      problems.push('Informe o valor prometido.');
    } else if (draft.promiseAmount > limits.openBalance) {
      problems.push('O valor prometido não pode passar do saldo em aberto.');
    }
    if (!draft.promiseDate) {
      problems.push('Informe a data prometida.');
    } else {
      if (draft.promiseDate < limits.today) {
        problems.push('A data prometida não pode estar no passado.');
      }
      if (daysBetween(limits.today, draft.promiseDate) > limits.promiseMaxDays) {
        problems.push(`A data prometida deve estar em até ${limits.promiseMaxDays} dias.`);
      }
    }
  }
  if (t.callback) {
    if (!draft.callbackAt) {
      problems.push('Informe data e hora do retorno.');
    } else if (isPastLocal(draft.callbackAt, now)) {
      problems.push('O retorno precisa ser em data e hora futuras.');
    }
  }
  return problems;
}

/** Dias corridos entre duas `LocalDate` (b − a). */
export function daysBetween(a: string, b: string): number {
  const toUtc = (s: string) => {
    const [y, m, d] = s.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
  };
  return Math.round((toUtc(b) - toUtc(a)) / 86_400_000);
}

/** `LocalDate` + n dias. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  const value = new Date(Date.UTC(y, m - 1, d + days));
  return value.toISOString().slice(0, 10);
}

/** Duração "mm:ss" ou "h:mm:ss" do cronômetro de atendimento. */
export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${String(m).padStart(2, '0')}:${sec}`;
}

// --------------------------------------------------------------------- caso

/** Caso pode ser trabalhado pelo operador (estágio de tratamento, não retido). */
export function isWorkable(c: Pick<CaseView, 'status'>): boolean {
  return c.status === 'OPEN' || c.status === 'IN_NEGOTIATION' || c.status === 'PROMISE';
}

/** Caso do usuário corrente ou disponível (sem operador) — os demais exigem `CASE_ASSIGN` (409 no servidor). */
export function canWorkCase(c: Pick<CaseView, 'assigneeUserId' | 'status'>, userId: string | null, canAssign: boolean): boolean {
  if (c.status === 'CLOSED') {
    return false;
  }
  return canAssign || !c.assigneeUserId || c.assigneeUserId === userId;
}

/** Texto curto da próxima ação ("Retorno vencido", "Retorno 24/10 14:00"). */
export function nextActionText(nextActionAt: string | null, now: Date = new Date()): string | null {
  if (!nextActionAt) {
    return null;
  }
  const at = new Date(nextActionAt);
  if (at.getTime() <= now.getTime()) {
    return 'Próxima ação vencida';
  }
  const fmt = new Intl.DateTimeFormat('pt-BR', { timeZone: BUSINESS_ZONE, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  return `Próxima ação ${fmt.format(at).replace(',', '')}`;
}
