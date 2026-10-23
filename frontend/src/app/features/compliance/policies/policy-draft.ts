import { toHourMinute } from '../../../shared/models/dates';
import { CHANNELS, CHANNEL_LABELS } from '../data/compliance.labels';
import { ChannelRuleInput, ChannelRuleView, ContactChannel, PolicyView, WeekDay } from '../data/compliance.models';

/** Regra de um canal no rascunho (valores do formulário). */
export interface RuleDraft {
  channel: ContactChannel;
  /** Canal incluído na nova versão (canais fora da versão seguem a assessoria ou a janela padrão). */
  included: boolean;
  /** Herda a janela da assessoria (horário e dias nulos). */
  inheritWindow: boolean;
  windowStart: string;
  windowEnd: string;
  allowedDays: WeekDay[];
  maxPerDay: number | null;
  maxPerWeek: number | null;
  minIntervalMinutes: number | null;
  blockOnHolidays: boolean;
}

const BUSINESS_DAYS: WeekDay[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

/** Rascunho a partir da versão publicada mais recente (ou um ponto de partida conservador). */
export function draftFrom(policy: PolicyView | null | undefined): RuleDraft[] {
  return CHANNELS.map((channel) => {
    const rule = policy?.rules.find((r) => r.channel === channel);
    return rule ? fromRule(rule) : emptyRule(channel, !policy && channel !== 'PORTAL');
  });
}

function fromRule(rule: ChannelRuleView): RuleDraft {
  return {
    channel: rule.channel,
    included: true,
    inheritWindow: rule.inheritsWindow,
    windowStart: toHourMinute(rule.windowStart) || '08:00',
    windowEnd: toHourMinute(rule.windowEnd) || '20:00',
    allowedDays: rule.allowedDays?.length ? [...rule.allowedDays] : [...BUSINESS_DAYS],
    maxPerDay: rule.maxPerDay,
    maxPerWeek: rule.maxPerWeek,
    minIntervalMinutes: rule.minIntervalMinutes,
    blockOnHolidays: rule.blockOnHolidays,
  };
}

function emptyRule(channel: ContactChannel, included: boolean): RuleDraft {
  return {
    channel,
    included,
    inheritWindow: true,
    windowStart: '08:00',
    windowEnd: '20:00',
    allowedDays: [...BUSINESS_DAYS],
    maxPerDay: null,
    maxPerWeek: null,
    minIntervalMinutes: null,
    blockOnHolidays: true,
  };
}

/** Problemas do rascunho antes de publicar (o servidor repete as validações). */
export function draftProblems(rules: readonly RuleDraft[]): string[] {
  const included = rules.filter((r) => r.included);
  const problems: string[] = [];
  if (included.length === 0) {
    problems.push('Inclua a regra de ao menos um canal.');
  }
  for (const r of included) {
    if (!r.inheritWindow && (!r.windowStart || !r.windowEnd || r.allowedDays.length === 0)) {
      problems.push(`${CHANNEL_LABELS[r.channel]}: informe início, fim e ao menos um dia da janela.`);
    }
    if (r.maxPerDay !== null && r.maxPerWeek !== null && r.maxPerWeek < r.maxPerDay) {
      problems.push(`${CHANNEL_LABELS[r.channel]}: o limite semanal não pode ser menor que o diário.`);
    }
  }
  return problems;
}

/** Converte o rascunho no corpo de `POST /compliance/contact-policies`. */
export function toRuleInputs(rules: readonly RuleDraft[]): ChannelRuleInput[] {
  return rules
    .filter((r) => r.included)
    .map((r) => ({
      channel: r.channel,
      windowStart: r.inheritWindow ? null : r.windowStart,
      windowEnd: r.inheritWindow ? null : r.windowEnd,
      allowedDays: r.inheritWindow ? null : [...r.allowedDays],
      maxPerDay: r.maxPerDay || null,
      maxPerWeek: r.maxPerWeek || null,
      minIntervalMinutes: r.minIntervalMinutes || null,
      blockOnHolidays: r.blockOnHolidays,
    }));
}

/** Janela em texto ("08:00–20:00", "Dia inteiro", "Atravessa a meia-noite"). */
export function windowText(start: string | null, end: string | null): string {
  const s = toHourMinute(start);
  const e = toHourMinute(end);
  if (!s || !e) {
    return '—';
  }
  if (s === e) {
    return 'Dia inteiro';
  }
  return s < e ? `${s}–${e}` : `${s}–${e} (atravessa a meia-noite)`;
}

/** Limites em texto ("3/dia · 10/semana · 60 min entre tentativas"). */
export function limitsText(r: { maxPerDay: number | null; maxPerWeek: number | null; minIntervalMinutes: number | null }): string {
  const parts = [
    r.maxPerDay ? `${r.maxPerDay}/dia` : null,
    r.maxPerWeek ? `${r.maxPerWeek}/semana` : null,
    r.minIntervalMinutes ? `${r.minIntervalMinutes} min entre tentativas` : null,
  ].filter((p): p is string => !!p);
  return parts.length ? parts.join(' · ') : 'Sem limite';
}
