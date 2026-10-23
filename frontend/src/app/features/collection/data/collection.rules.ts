/**
 * Regras de tela da operação de cobrança (funções puras, testadas em `collection.rules.spec.ts`).
 * Datas e horas de retorno são do **fuso de operação** (`America/Sao_Paulo`, `TimeConfig.BUSINESS_ZONE`).
 */
import { CaseView, TabulationView, TaskView } from './collection.models';

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

/** Janela de contato aplicável a um canal (padrão da assessoria ou regra efetiva da política). */
export interface ContactWindow {
  /** "HH:mm" ou nulo (sem janela definida) */
  start: string | null;
  end: string | null;
  /** Dias permitidos (0 = domingo); nulo = todos */
  days: readonly number[] | null;
  source: 'POLICY' | 'TENANT';
}

export interface WindowState {
  open: boolean;
  /** Situação completa ("Abre às 08:00 · 08:00 às 20:00"). */
  text: string;
  /** Faixa permitida ("08:00–20:00", "sáb. e dom. sem contato"). */
  range: string;
}

const WEEK_DAY_NAMES = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/** `WeekDay` do backend → índice JS (0 = domingo). */
export function weekDayIndex(day: string): number {
  const i = WEEK_DAY_NAMES.indexOf(day);
  return i < 0 ? -1 : (i + 1) % 7;
}

/** Dias permitidos pelos parâmetros da assessoria (segunda a sexta, mais sábado/domingo se marcados). */
export function tenantDays(saturday: boolean, sunday: boolean): number[] {
  return [...(sunday ? [0] : []), 1, 2, 3, 4, 5, ...(saturday ? [6] : [])];
}

/**
 * Situação da janela agora. A tela só orienta: feriados e limites de frequência são verificados
 * pelo porteiro de contato do servidor a cada contato.
 */
export function windowState(window: ContactWindow, now: ZonedNow): WindowState {
  const start = window.start?.slice(0, 5) ?? null;
  const end = window.end?.slice(0, 5) ?? null;
  const range = start && end ? `${start} às ${end}` : 'sem horário definido';
  const short = start && end ? `${start}–${end}` : 'qualquer horário';
  if (window.days && !window.days.includes(now.weekDay)) {
    return { open: false, text: `Fora dos dias permitidos (${range})`, range: 'hoje não' };
  }
  if (!start || !end) {
    return { open: true, text: 'Dia permitido, sem restrição de horário', range: short };
  }
  const open = now.time >= start && now.time < end;
  if (open) {
    return { open: true, text: `Permitido agora · ${range}`, range: short };
  }
  return { open: false, text: now.time < start ? `Abre às ${start} · ${range}` : `Encerrado hoje · ${range}`, range: short };
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
