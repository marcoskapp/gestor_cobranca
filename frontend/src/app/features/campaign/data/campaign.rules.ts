/**
 * Regras de tela do módulo campaign (funções puras). Espelham as validações do servidor para que o
 * erro apareça enquanto a pessoa configura; o servidor valida tudo de novo (escopo, templates
 * publicados, oferta, congelamento do teste A/B).
 */
import { formatAmount } from '../../../shared/components/amount/amount';
import { describeStatus } from '../../../shared/status/status';
import {
  ACTION_LABELS,
  CHANNEL_LABELS,
  CONTROL_ARM,
  CONTROL_LABEL,
  DAY_LABELS,
  DAYS,
  END_REASON_LABELS,
  FAILURE_LABELS,
  OWNER_TYPE_LABELS,
  PAUSE_REASON_LABELS,
  SOURCE_VARIABLE_TYPES,
} from './campaign.labels';
import {
  ActionType,
  CadenceDefinition,
  CadenceStep,
  ContactChannel,
  DayOfWeek,
  EnrollmentView,
  ExperimentInput,
  ExperimentMetric,
  ExperimentVariant,
  SegmentCriteria,
  SendWindow,
  StepAction,
  VariableSource,
} from './campaign.models';

// ===================================================================== números

const INTEGER = new Intl.NumberFormat('pt-BR');
const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
const DECIMAL1 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1, signDisplay: 'always' });

export function count(value: number | null | undefined): string {
  return INTEGER.format(value ?? 0);
}

/** Taxa 0–1 em porcentagem ("12,4%"). */
export function percent(rate: number | null | undefined): string {
  return rate === null || rate === undefined || !Number.isFinite(rate) ? '—' : PERCENT.format(rate);
}

/** Parte de um total com o denominador explícito ("12 de 340 (3,5%)"). */
export function share(part: number, total: number): string {
  return total > 0 ? `${count(part)} de ${count(total)} (${percent(part / total)})` : `${count(part)} de 0`;
}

// ===================================================================== segmentos

export const CASE_STATUSES = ['OPEN', 'IN_NEGOTIATION', 'PROMISE', 'AGREEMENT', 'DISPUTED', 'SUSPENDED', 'LEGAL'] as const;
export const CONTACT_CHANNELS: readonly ContactChannel[] = ['WHATSAPP', 'EMAIL', 'SMS', 'PHONE'];
export const OWNER_TYPES = ['ASSISTANT', 'QUEUE', 'USER', 'NONE'] as const;
const MAX_DAYS = 36_500;
/** Horizonte do filtro de parcela vencendo (`SegmentCriteria.MAX_INSTALLMENT_DAYS`). */
const MAX_INSTALLMENT_DAYS = 366;
const CODE = /^[A-Z][A-Z0-9_]{1,59}$/;

export function emptyCriteria(): SegmentCriteria {
  return {
    portfolioIds: null,
    minDaysOverdue: null,
    maxDaysOverdue: null,
    minBalance: null,
    maxBalance: null,
    caseStatuses: null,
    eligibleOnly: true,
    contactableChannel: null,
    minInteractions: null,
    maxInteractions: null,
    everEffectiveContact: null,
    noInteractionDays: null,
    noEffectiveContactDays: null,
    pendingPromise: null,
    activeAgreement: null,
    minBrokenAgreements: null,
    minScore: null,
    maxScore: null,
    ownerTypes: null,
    minDaysToInstallment: null,
    maxDaysToInstallment: null,
  };
}

/**
 * Forma canônica dos filtros: todos os campos presentes, listas vazias como nulo, números
 * inválidos como nulo. Usada ao carregar do servidor, ao comparar (`sameValue`) e ao enviar.
 */
export function normalizeCriteria(input: Partial<SegmentCriteria> | null | undefined): SegmentCriteria {
  const c = { ...emptyCriteria(), ...(input ?? {}) } as SegmentCriteria;
  const list = <T>(v: T[] | null | undefined) => (v && v.length ? [...v].sort() : null);
  const num = (v: number | null | undefined) => (v === null || v === undefined || typeof v !== 'number' || Number.isNaN(v) ? null : v);
  return {
    portfolioIds: list(c.portfolioIds),
    minDaysOverdue: num(c.minDaysOverdue),
    maxDaysOverdue: num(c.maxDaysOverdue),
    minBalance: num(c.minBalance),
    maxBalance: num(c.maxBalance),
    caseStatuses: list(c.caseStatuses),
    eligibleOnly: c.eligibleOnly ?? true,
    contactableChannel: c.contactableChannel || null,
    minInteractions: num(c.minInteractions),
    maxInteractions: num(c.maxInteractions),
    everEffectiveContact: c.everEffectiveContact ?? null,
    noInteractionDays: num(c.noInteractionDays),
    noEffectiveContactDays: num(c.noEffectiveContactDays),
    pendingPromise: c.pendingPromise ?? null,
    activeAgreement: c.activeAgreement ?? null,
    minBrokenAgreements: num(c.minBrokenAgreements),
    minScore: num(c.minScore),
    maxScore: num(c.maxScore),
    ownerTypes: list(c.ownerTypes),
    minDaysToInstallment: num(c.minDaysToInstallment),
    maxDaysToInstallment: num(c.maxDaysToInstallment),
  };
}

export type FilterKind = 'portfolios' | 'int-range' | 'money-range' | 'statuses' | 'owners' | 'channel' | 'yes-no' | 'int';

/** Filtro combinável do construtor de segmentos (um ou dois campos de `SegmentCriteria`). */
export interface CriteriaFilter {
  key: string;
  label: string;
  group: string;
  kind: FilterKind;
  fields: readonly (keyof SegmentCriteria)[];
  hint?: string;
  /** Rótulos de sim/não (`yes-no`). */
  yes?: string;
  no?: string;
  /** Limites do campo numérico. */
  max?: number;
}

export const CRITERIA_FILTERS: readonly CriteriaFilter[] = [
  { key: 'portfolios', label: 'Carteiras', group: 'Carteira e situação', kind: 'portfolios', fields: ['portfolioIds'] },
  { key: 'caseStatuses', label: 'Situação do caso', group: 'Carteira e situação', kind: 'statuses', fields: ['caseStatuses'], hint: 'Sem seleção: qualquer situação ativa.' },
  { key: 'ownerTypes', label: 'Dono do atendimento', group: 'Carteira e situação', kind: 'owners', fields: ['ownerTypes'] },
  { key: 'daysOverdue', label: 'Atraso (dias)', group: 'Dívida', kind: 'int-range', fields: ['minDaysOverdue', 'maxDaysOverdue'], hint: 'Desde o vencimento mais antigo em cobrança.', max: MAX_DAYS },
  { key: 'balance', label: 'Saldo em aberto (R$)', group: 'Dívida', kind: 'money-range', fields: ['minBalance', 'maxBalance'] },
  { key: 'contactableChannel', label: 'Canal contatável', group: 'Contato', kind: 'channel', fields: ['contactableChannel'], hint: 'Canal sem restrição de contato para o devedor.' },
  { key: 'interactions', label: 'Interações registradas', group: 'Contato', kind: 'int-range', fields: ['minInteractions', 'maxInteractions'], hint: 'Máximo 0 = nunca trabalhado.', max: MAX_DAYS },
  { key: 'everEffectiveContact', label: 'Contato efetivo', group: 'Contato', kind: 'yes-no', fields: ['everEffectiveContact'], yes: 'Já houve contato efetivo', no: 'Nunca houve contato efetivo' },
  { key: 'noInteractionDays', label: 'Sem interação há (dias)', group: 'Contato', kind: 'int', fields: ['noInteractionDays'], hint: 'Inclui casos nunca trabalhados.', max: MAX_DAYS },
  { key: 'noEffectiveContactDays', label: 'Sem contato efetivo há (dias)', group: 'Contato', kind: 'int', fields: ['noEffectiveContactDays'], hint: 'Inclui casos sem contato efetivo.', max: MAX_DAYS },
  { key: 'pendingPromise', label: 'Promessa de pagamento', group: 'Negociação', kind: 'yes-no', fields: ['pendingPromise'], yes: 'Com promessa pendente', no: 'Sem promessa pendente' },
  { key: 'activeAgreement', label: 'Acordo em vigor', group: 'Negociação', kind: 'yes-no', fields: ['activeAgreement'], yes: 'Com acordo em vigor', no: 'Sem acordo em vigor' },
  { key: 'minBrokenAgreements', label: 'Acordos quebrados (mínimo)', group: 'Negociação', kind: 'int', fields: ['minBrokenAgreements'], max: MAX_DAYS },
  { key: 'installmentDue', label: 'Parcela do acordo vence em (dias)', group: 'Negociação', kind: 'int-range', fields: ['minDaysToInstallment', 'maxDaysToInstallment'], hint: 'Próxima parcela em aberto do acordo em vigor; 0 = vence hoje. Casos sem acordo em vigor ficam fora.', max: MAX_INSTALLMENT_DAYS },
  { key: 'score', label: 'Score de propensão', group: 'Score', kind: 'int-range', fields: ['minScore', 'maxScore'], hint: 'De 0 a 1000. Casos sem score ficam fora.', max: 1000 },
];

/** Filtros com algum valor (exibidos no construtor ao abrir um segmento salvo). */
export function activeFilterKeys(c: SegmentCriteria): string[] {
  return CRITERIA_FILTERS.filter((f) =>
    f.fields.some((field) => {
      const v = c[field];
      return Array.isArray(v) ? v.length > 0 : v !== null && v !== undefined;
    }),
  ).map((f) => f.key);
}

/** Remove o filtro (campos voltam a nulo = sem filtro). */
export function clearFilter(c: SegmentCriteria, key: string): void {
  const filter = CRITERIA_FILTERS.find((f) => f.key === key);
  for (const field of filter?.fields ?? []) {
    (c as unknown as Record<string, unknown>)[field] = null;
  }
}

function rangeProblem(min: number | null, max: number | null, label: string, limit: number): string | null {
  const bad = (v: number | null) => v !== null && (!Number.isInteger(v) || v < 0 || v > limit);
  if (bad(min) || bad(max)) {
    return `${label}: use números inteiros entre 0 e ${count(limit)}.`;
  }
  return min !== null && max !== null && min > max ? `${label}: o mínimo é maior que o máximo.` : null;
}

/** Mesmas regras de `SegmentCriteria.validate()`. */
export function criteriaProblems(c: SegmentCriteria): string[] {
  const problems: (string | null)[] = [
    rangeProblem(c.minDaysOverdue, c.maxDaysOverdue, 'Atraso', MAX_DAYS),
    rangeProblem(c.minInteractions, c.maxInteractions, 'Interações', MAX_DAYS),
    rangeProblem(c.minScore, c.maxScore, 'Score', 1000),
    rangeProblem(c.noInteractionDays, null, 'Sem interação', MAX_DAYS),
    rangeProblem(c.noEffectiveContactDays, null, 'Sem contato efetivo', MAX_DAYS),
    rangeProblem(c.minBrokenAgreements, null, 'Acordos quebrados', MAX_DAYS),
    rangeProblem(c.minDaysToInstallment, c.maxDaysToInstallment, 'Parcela do acordo', MAX_INSTALLMENT_DAYS),
  ];
  if (c.activeAgreement === false && (c.minDaysToInstallment !== null || c.maxDaysToInstallment !== null)) {
    problems.push('Parcela do acordo: o filtro exige acordo em vigor.');
  }
  if ((c.minBalance ?? 0) < 0 || (c.maxBalance ?? 0) < 0) {
    problems.push('Saldo: valores não podem ser negativos.');
  } else if (c.minBalance !== null && c.maxBalance !== null && c.minBalance > c.maxBalance) {
    problems.push('Saldo: o mínimo é maior que o máximo.');
  }
  return problems.filter((p): p is string => !!p);
}

/** "hoje", "em até 3 dias", "entre 1 e 3 dias", "daqui a 5 dias ou mais". */
function installmentText(min: number | null, max: number | null, days: (v: number) => string): string {
  if (min !== null && max !== null) {
    if (min === max) return min === 0 ? 'hoje' : `em ${days(min)}`;
    return `entre ${count(min)} e ${days(max)}`;
  }
  if (max !== null) return max === 0 ? 'hoje' : `em até ${days(max)}`;
  return min === 0 ? 'hoje ou depois' : `daqui a ${days(min!)} ou mais`;
}

function rangeText(min: number | null, max: number | null, unit: (v: number) => string): string {
  if (min !== null && max !== null) {
    return min === max ? unit(min) : `de ${unit(min)} a ${unit(max)}`;
  }
  return min !== null ? `a partir de ${unit(min)}` : `até ${unit(max!)}`;
}

/**
 * Resumo legível dos filtros (todos combinados por E), na ordem do construtor. `portfolioName`
 * resolve os nomes das carteiras.
 */
export function criteriaSummary(c: SegmentCriteria, portfolioName: (id: string) => string = (id) => id): string[] {
  const days = (v: number) => `${count(v)} ${v === 1 ? 'dia' : 'dias'}`;
  const items: string[] = [c.eligibleOnly === false ? 'Inclui casos inelegíveis' : 'Somente casos elegíveis'];
  for (const f of CRITERIA_FILTERS) {
    switch (f.key) {
      case 'portfolios':
        if (c.portfolioIds?.length) items.push(`Carteiras: ${c.portfolioIds.map(portfolioName).join(', ')}`);
        break;
      case 'caseStatuses':
        if (c.caseStatuses?.length) items.push(`Situação: ${c.caseStatuses.map((s) => describeStatus('case', s).label).join(', ')}`);
        break;
      case 'ownerTypes':
        if (c.ownerTypes?.length) items.push(`Dono: ${c.ownerTypes.map((o) => OWNER_TYPE_LABELS[o] ?? o).join(', ')}`);
        break;
      case 'daysOverdue':
        if (c.minDaysOverdue !== null || c.maxDaysOverdue !== null) items.push(`Atraso ${rangeText(c.minDaysOverdue, c.maxDaysOverdue, days)}`);
        break;
      case 'balance':
        if (c.minBalance !== null || c.maxBalance !== null) items.push(`Saldo ${rangeText(c.minBalance, c.maxBalance, (v) => formatAmount(v))}`);
        break;
      case 'contactableChannel':
        if (c.contactableChannel) items.push(`Contatável por ${CHANNEL_LABELS[c.contactableChannel]}`);
        break;
      case 'interactions':
        if (c.minInteractions !== null || c.maxInteractions !== null) {
          items.push(c.maxInteractions === 0 && !c.minInteractions ? 'Nunca trabalhados' : `Interações ${rangeText(c.minInteractions, c.maxInteractions, count)}`);
        }
        break;
      case 'noInteractionDays':
        if (c.noInteractionDays !== null) items.push(`Sem interação há ${days(c.noInteractionDays)}`);
        break;
      case 'noEffectiveContactDays':
        if (c.noEffectiveContactDays !== null) items.push(`Sem contato efetivo há ${days(c.noEffectiveContactDays)}`);
        break;
      case 'minBrokenAgreements':
        if (c.minBrokenAgreements !== null) items.push(`Ao menos ${count(c.minBrokenAgreements)} acordo(s) quebrado(s)`);
        break;
      case 'score':
        if (c.minScore !== null || c.maxScore !== null) items.push(`Score ${rangeText(c.minScore, c.maxScore, count)}`);
        break;
      case 'installmentDue':
        if (c.minDaysToInstallment !== null || c.maxDaysToInstallment !== null) {
          items.push(`Parcela do acordo vence ${installmentText(c.minDaysToInstallment, c.maxDaysToInstallment, days)}`);
        }
        break;
      default: {
        const v = c[f.fields[0]] as boolean | null;
        if (v !== null && f.yes && f.no) items.push(v ? f.yes : f.no);
      }
    }
  }
  return items;
}

export function codeProblem(code: string, label = 'Código'): string | null {
  return CODE.test(code) ? null : `${label}: letras maiúsculas, dígitos e _ (2 a 60, começando por letra).`;
}

/** Código sugerido a partir do nome ("Atraso 30 a 90" → "ATRASO_30_A_90"). */
export function suggestCode(name: string): string {
  const base = name
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/^[0-9_]+/, '')
    .slice(0, 60);
  return base.length >= 2 ? base : '';
}

// ===================================================================== régua

export const MAX_STEPS = 30;
const STEP_CODE = /^[A-Z][A-Z0-9_]{0,39}$/;
const TEMPLATE_CODE = /^[A-Z][A-Z0-9_]{1,59}$/;
const VARIABLE = /^[a-z][a-z0-9_]{0,39}$/;
const MAX_WAIT = 60 * 24 * 60;
const MAX_TASK_DUE = 30 * 24 * 60;
export const MESSAGE_ACTIONS: readonly ActionType[] = ['SEND_MESSAGE', 'OFFER'];
export const OFFER_SOURCES: readonly VariableSource[] = ['OFFER_AMOUNT', 'OFFER_DUE_DATE', 'PAYMENT_LINK'];
/** Origens calculadas no envio a partir do acordo em vigor do caso (etapa pulada sem acordo ou sem parcela). */
export const INSTALLMENT_SOURCES: readonly VariableSource[] = ['NEXT_INSTALLMENT_AMOUNT', 'NEXT_INSTALLMENT_DUE_DATE'];
/** Link de uso único gerado pelo portal no envio (etapa pulada sem portal para o caso). */
export const PORTAL_SOURCES: readonly VariableSource[] = ['PORTAL_LINK', 'PORTAL_LINK_HOURS'];

export function sendsMessage(a: Pick<StepAction, 'type'>): boolean {
  return MESSAGE_ACTIONS.includes(a.type);
}

export function emptyAction(type: ActionType, previous?: StepAction): StepAction {
  const message = MESSAGE_ACTIONS.includes(type);
  return {
    type,
    channel: message ? (previous?.channel ?? 'WHATSAPP') : null,
    templateCode: message ? (previous?.templateCode ?? null) : null,
    variables: message ? { ...(previous?.variables ?? {}) } : {},
    taskType: type === 'CREATE_TASK' ? 'CALLBACK' : null,
    taskDueInMinutes: type === 'CREATE_TASK' ? 60 : null,
    taskNote: null,
    teamId: null,
    offer: type === 'OFFER' ? { installments: 1, concessionLevel: 0, validityDays: 3 } : null,
  };
}

/** Próximo código livre `ETAPA_<n>`. */
export function nextStepCode(steps: readonly Pick<CadenceStep, 'code'>[]): string {
  const used = new Set(steps.map((s) => s.code));
  let n = steps.length + 1;
  while (used.has(`ETAPA_${n}`)) {
    n++;
  }
  return `ETAPA_${n}`;
}

export function newStep(steps: readonly CadenceStep[]): CadenceStep {
  return {
    code: nextStepCode(steps),
    name: null,
    waitMinutes: steps.length ? 24 * 60 : 0,
    window: null,
    condition: null,
    action: emptyAction('SEND_MESSAGE', steps[steps.length - 1]?.action),
    onReply: null,
    onReplyStepCode: null,
  };
}

export function emptyDefinition(): CadenceDefinition {
  return { defaultOnReply: 'STOP', defaultOnReplyStepCode: null, replyTeamId: null, steps: [] };
}

/** `HH:mm:ss` → `HH:mm` (o editor trabalha em minutos; o servidor aceita os dois). */
export function shortTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '00:00';
}

function cloneWindow(w: SendWindow | null): SendWindow | null {
  return w ? { start: shortTime(w.start), end: shortTime(w.end), days: [...(w.days ?? [])] } : null;
}

/** Cópia profunda editável, com todos os campos presentes (o servidor omite padrões). */
export function cloneDefinition(d: CadenceDefinition | null | undefined): CadenceDefinition {
  const src = d ?? emptyDefinition();
  return {
    defaultOnReply: src.defaultOnReply ?? 'STOP',
    defaultOnReplyStepCode: src.defaultOnReplyStepCode ?? null,
    replyTeamId: src.replyTeamId ?? null,
    steps: (src.steps ?? []).map((s) => ({
      code: s.code,
      name: s.name ?? null,
      waitMinutes: s.waitMinutes ?? 0,
      window: windowIsAlways(s.window) ? null : cloneWindow(s.window),
      condition: conditionIsEmpty(s.condition)
        ? null
        : {
            criteria: s.condition!.criteria ? normalizeCriteria(s.condition!.criteria) : null,
            requireNoReply: !!s.condition!.requireNoReply,
            requireNoEffectiveContact: !!s.condition!.requireNoEffectiveContact,
            requirePreviousMessageUnread: !!s.condition!.requirePreviousMessageUnread,
          },
      action: {
        ...emptyAction(s.action.type),
        ...s.action,
        variables: { ...(s.action.variables ?? {}) },
        offer: s.action.offer ? { ...s.action.offer } : s.action.type === 'OFFER' ? { installments: 1, concessionLevel: 0, validityDays: 3 } : null,
      },
      onReply: s.onReply ?? null,
      onReplyStepCode: s.onReplyStepCode ?? null,
    })),
  };
}

export function windowIsAlways(w: SendWindow | null | undefined): boolean {
  return !w || (shortTime(w.start) === shortTime(w.end) && !(w.days ?? []).length);
}

function conditionIsEmpty(c: CadenceStep['condition']): boolean {
  return !c || (!c.criteria && !c.requireNoReply && !c.requireNoEffectiveContact && !c.requirePreviousMessageUnread);
}

/** Corpo enviado ao servidor: horários completos, campos sem uso limpos por tipo de ação. */
export function toServerDefinition(d: CadenceDefinition): CadenceDefinition {
  const full = (t: string | null) => (t ? (t.length === 5 ? `${t}:00` : t) : null);
  return {
    defaultOnReply: d.defaultOnReply ?? 'STOP',
    defaultOnReplyStepCode: d.defaultOnReply === 'GOTO_STEP' ? d.defaultOnReplyStepCode : null,
    replyTeamId: d.replyTeamId,
    steps: d.steps.map((s) => {
      const message = sendsMessage(s.action);
      return {
        code: s.code.trim(),
        name: s.name?.trim() || null,
        waitMinutes: s.waitMinutes ?? 0,
        window: windowIsAlways(s.window) ? null : { start: full(s.window!.start), end: full(s.window!.end), days: [...(s.window!.days ?? [])] },
        condition: conditionIsEmpty(s.condition) ? null : { ...s.condition!, criteria: s.condition!.criteria ? normalizeCriteria(s.condition!.criteria) : null },
        action: {
          type: s.action.type,
          channel: message ? s.action.channel : null,
          templateCode: message ? s.action.templateCode?.trim() || null : null,
          variables: message ? { ...(s.action.variables ?? {}) } : {},
          taskType: s.action.type === 'CREATE_TASK' ? s.action.taskType : null,
          taskDueInMinutes: s.action.type === 'CREATE_TASK' ? s.action.taskDueInMinutes : null,
          taskNote: s.action.type === 'CREATE_TASK' ? s.action.taskNote?.trim() || null : null,
          teamId: s.action.type === 'MOVE_TO_QUEUE' ? s.action.teamId : null,
          offer: s.action.type === 'OFFER' ? s.action.offer : null,
        },
        onReply: s.onReply,
        onReplyStepCode: s.onReply === 'GOTO_STEP' ? s.onReplyStepCode : null,
      };
    }),
  };
}

function minutesOf(time: string): number {
  const [h, m] = shortTime(time).split(':').map(Number);
  return h * 60 + m;
}

/** Variáveis `nome → origem` válidas para a ação (mesmas regras de `StepAction.validate`). */
function variableProblems(prefix: string, a: StepAction): string[] {
  const problems: string[] = [];
  for (const [name, spec] of Object.entries(a.variables ?? {})) {
    if (!VARIABLE.test(name)) {
      problems.push(`${prefix}nome de variável inválido: ${name || '(vazio)'}.`);
    }
    const source = sourceOf(spec);
    if (!source) {
      problems.push(`${prefix}escolha a origem da variável ${name}.`);
    } else if (source === 'LITERAL') {
      const text = literalOf(spec);
      if (!text.trim() || text.length > 200) {
        problems.push(`${prefix}texto fixo da variável ${name} com 1 a 200 caracteres.`);
      }
    } else if (OFFER_SOURCES.includes(source) && a.type !== 'OFFER') {
      problems.push(`${prefix}a variável ${name} usa dado de oferta, disponível só em etapa de oferta.`);
    }
  }
  const sources = Object.values(a.variables ?? {}).map(sourceOf);
  if (sources.includes('PORTAL_LINK_HOURS') && !sources.includes('PORTAL_LINK')) {
    problems.push(`${prefix}a validade do link do portal só vai junto com o link do portal na mesma mensagem.`);
  }
  return problems;
}

/** Problemas da régua inteira (mesmas regras de `CadenceDefinition.validate`), por etapa. */
export function cadenceProblems(d: CadenceDefinition): string[] {
  const problems: string[] = [];
  if (!d.steps.length) {
    problems.push('Inclua ao menos uma etapa.');
  }
  if (d.steps.length > MAX_STEPS) {
    problems.push(`A régua aceita até ${MAX_STEPS} etapas.`);
  }
  const codes = d.steps.map((s) => s.code);
  d.steps.forEach((s, i) => {
    const p = `Etapa ${i + 1}${s.code ? ` (${s.code})` : ''}: `;
    if (!STEP_CODE.test(s.code)) {
      problems.push(`${p}código com letras maiúsculas, dígitos e _ (até 40, começando por letra).`);
    } else if (codes.indexOf(s.code) !== i) {
      problems.push(`${p}código repetido.`);
    }
    if ((s.name ?? '').length > 120) {
      problems.push(`${p}nome com até 120 caracteres.`);
    }
    const wait = s.waitMinutes ?? 0;
    if (!Number.isInteger(wait) || wait < 0 || wait > MAX_WAIT) {
      problems.push(`${p}espera entre 0 e 60 dias.`);
    }
    if (s.window && !windowIsAlways(s.window) && shortTime(s.window.start) !== shortTime(s.window.end) && minutesOf(s.window.start ?? '') > minutesOf(s.window.end ?? '')) {
      problems.push(`${p}na janela de horário o início deve ser anterior ao fim (sem atravessar a meia-noite).`);
    }
    if (s.condition?.criteria) {
      problems.push(...criteriaProblems(s.condition.criteria).map((c) => `${p}condição — ${c.charAt(0).toLowerCase()}${c.slice(1)}`));
    }
    const a = s.action;
    if (sendsMessage(a)) {
      if (!a.channel) {
        problems.push(`${p}escolha o canal da mensagem.`);
      }
      if (!a.templateCode || !TEMPLATE_CODE.test(a.templateCode)) {
        problems.push(`${p}escolha o template da mensagem.`);
      }
      problems.push(...variableProblems(p, a));
      if (a.type === 'OFFER') {
        const o = a.offer;
        if (!o || !inRange(o.installments, 1, 120) || !inRange(o.concessionLevel, 0, 99) || !inRange(o.validityDays, 1, 30)) {
          problems.push(`${p}oferta com 1 a 120 pagamentos, nível 0 a 99 e validade de 1 a 30 dias.`);
        }
      }
    } else if (a.type === 'CREATE_TASK') {
      if (!a.taskType) {
        problems.push(`${p}escolha o tipo de tarefa.`);
      }
      if (!inRange(a.taskDueInMinutes, 0, MAX_TASK_DUE)) {
        problems.push(`${p}prazo da tarefa entre 0 e 30 dias.`);
      }
      if ((a.taskNote ?? '').length > 500) {
        problems.push(`${p}observação da tarefa com até 500 caracteres.`);
      }
    }
    if (s.onReply === 'GOTO_STEP' && !codes.includes(s.onReplyStepCode ?? '')) {
      problems.push(`${p}escolha a etapa de destino da resposta.`);
    }
  });
  if (d.defaultOnReply === 'GOTO_STEP' && !codes.includes(d.defaultOnReplyStepCode ?? '')) {
    problems.push('Resposta padrão: escolha a etapa de destino.');
  }
  return problems;
}

function inRange(v: number | null | undefined, min: number, max: number): boolean {
  return v !== null && v !== undefined && Number.isInteger(v) && v >= min && v <= max;
}

export function sourceOf(spec: string | null | undefined): VariableSource | null {
  if (!spec) {
    return null;
  }
  const head = spec.includes(':') ? spec.slice(0, spec.indexOf(':')) : spec;
  return head in SOURCE_VARIABLE_TYPES ? (head as VariableSource) : null;
}

export function literalOf(spec: string | null | undefined): string {
  if (!spec) {
    return '';
  }
  const i = spec.indexOf(':');
  return i < 0 ? '' : spec.slice(i + 1);
}

/** Template publicado usado pela etapa (o do credor prevalece sobre o da assessoria). */
export interface TemplateRef {
  code: string;
  channel: string;
  creditorId: string | null;
  name: string;
  templateVersion: number;
  variables: { name: string; type: string; required: boolean }[];
}

export function pickTemplate(templates: readonly TemplateRef[], code: string | null, channel: string | null, creditorId: string): TemplateRef | null {
  const usable = templates.filter((t) => t.code === code && t.channel === channel && (t.creditorId === null || t.creditorId === creditorId));
  return usable.find((t) => t.creditorId === creditorId) ?? usable[0] ?? null;
}

export interface TemplateCheck {
  /** Impedem a publicação (`cadence.template_*`). */
  errors: string[];
  /** Tipo do valor diferente do declarado no template: o envio falharia na validação do communication. */
  warnings: string[];
}

/**
 * Coerência da etapa com o template publicado (`TemplateCheck` do servidor): as variáveis da
 * etapa devem estar no template e cobrir todas as obrigatórias.
 */
export function checkTemplate(step: CadenceStep, template: TemplateRef | null): TemplateCheck {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!sendsMessage(step.action) || !step.action.templateCode) {
    return { errors, warnings };
  }
  const label = `Etapa ${step.code}: `;
  if (!template) {
    errors.push(`${label}template ${step.action.templateCode} não está publicado no canal ${CHANNEL_LABELS[step.action.channel ?? 'WHATSAPP']} para o credor.`);
    return { errors, warnings };
  }
  const mapped = step.action.variables ?? {};
  const declared = new Map(template.variables.map((v) => [v.name, v]));
  const unknown = Object.keys(mapped).filter((n) => !declared.has(n));
  if (unknown.length) {
    errors.push(`${label}variáveis fora do template: ${unknown.sort().join(', ')}.`);
  }
  const missing = template.variables.filter((v) => v.required && !(v.name in mapped)).map((v) => v.name);
  if (missing.length) {
    errors.push(`${label}variáveis obrigatórias sem origem: ${missing.sort().join(', ')}.`);
  }
  for (const [name, spec] of Object.entries(mapped)) {
    const v = declared.get(name);
    const source = sourceOf(spec);
    if (v && source && !SOURCE_VARIABLE_TYPES[source].includes(v.type)) {
      warnings.push(`${label}a variável ${name} espera ${typeLabel(v.type)}, mas a origem escolhida produz outro tipo.`);
    }
  }
  return { errors, warnings };
}

function typeLabel(type: string): string {
  return { FIRST_NAME: 'primeiro nome', MONEY: 'valor em reais', DATE: 'data', INTEGER: 'número inteiro', URL: 'link', CODE: 'código', TEXT: 'texto' }[type] ?? type;
}

export type DurationUnit = 'MINUTES' | 'HOURS' | 'DAYS';
export const UNIT_MINUTES: Record<DurationUnit, number> = { MINUTES: 1, HOURS: 60, DAYS: 1440 };

/** Minutos na maior unidade exata (1440 → 1 dia; 90 → 90 min). */
export function splitDuration(minutes: number | null | undefined): { value: number; unit: DurationUnit } {
  const m = minutes ?? 0;
  if (m > 0 && m % 1440 === 0) {
    return { value: m / 1440, unit: 'DAYS' };
  }
  if (m > 0 && m % 60 === 0) {
    return { value: m / 60, unit: 'HOURS' };
  }
  return { value: m, unit: 'MINUTES' };
}

export function durationText(minutes: number | null | undefined): string {
  const m = minutes ?? 0;
  if (m <= 0) {
    return 'imediatamente';
  }
  const d = Math.floor(m / 1440);
  const h = Math.floor((m % 1440) / 60);
  const min = m % 60;
  const parts = [d ? `${d} ${d === 1 ? 'dia' : 'dias'}` : '', h ? `${h} h` : '', min ? `${min} min` : ''].filter(Boolean);
  return parts.join(' e ');
}

function daysText(days: readonly DayOfWeek[]): string {
  if (!days.length || days.length === 7) {
    return 'todos os dias';
  }
  const sorted = DAYS.filter((d) => days.includes(d));
  if (sorted.length === 5 && sorted.every((d, i) => d === DAYS[i])) {
    return 'Seg a Sex';
  }
  return sorted.map((d) => DAY_LABELS[d]).join(', ');
}

export function windowText(w: SendWindow | null | undefined): string {
  if (windowIsAlways(w)) {
    return 'qualquer horário';
  }
  const hours = shortTime(w!.start) === shortTime(w!.end) ? 'dia todo' : `${shortTime(w!.start)} às ${shortTime(w!.end)}`;
  return `${daysText(w!.days ?? [])}, ${hours}`;
}

/** Resumo de uma linha da etapa ("Enviar mensagem · WhatsApp · COBRANCA"). */
export function stepSummary(s: CadenceStep, teamName: (id: string) => string = () => 'equipe'): string {
  const a = s.action;
  const parts = [ACTION_LABELS[a.type]];
  if (sendsMessage(a)) {
    parts.push(a.channel ? CHANNEL_LABELS[a.channel] : 'canal?', a.templateCode ?? 'template?');
  } else if (a.type === 'CREATE_TASK') {
    parts.push(`prazo ${durationText(a.taskDueInMinutes)}`);
  } else if (a.type === 'MOVE_TO_QUEUE') {
    parts.push(a.teamId ? teamName(a.teamId) : 'fila do roteamento');
  }
  return parts.join(' · ');
}

export function conditionText(s: CadenceStep): string[] {
  const c = s.condition;
  if (!c) {
    return [];
  }
  const items: string[] = [];
  if (c.requireNoReply) items.push('sem resposta');
  if (c.requireNoEffectiveContact) items.push('sem contato efetivo');
  if (c.requirePreviousMessageUnread) items.push('mensagem anterior não lida');
  if (c.criteria) items.push(...criteriaSummary(c.criteria).slice(c.criteria.eligibleOnly === false ? 0 : 1).map((t) => t.charAt(0).toLowerCase() + t.slice(1)));
  return items;
}

// ===================================================================== execução

/** Estado da inscrição com o motivo em linguagem clara. */
export function enrollmentReason(e: EnrollmentView, at: (iso: string) => string): string {
  switch (e.status) {
    case 'PAUSED':
      return `${PAUSE_REASON_LABELS[e.pauseReason ?? ''] ?? e.pauseReason ?? 'Pausada'}${e.resumeAt ? ` · retoma em ${at(e.resumeAt)}` : ''}`;
    case 'ENDED':
      return END_REASON_LABELS[e.endReason ?? ''] ?? e.endReason ?? 'Encerrada';
    case 'FAILED':
      return failureText(e.lastErrorCode);
    case 'HOLDOUT':
      return e.observeUntil ? `Sem ações; observação até ${at(e.observeUntil)}` : 'Sem ações (só observação)';
    default:
      return e.nextStepCode ? `Próxima: ${e.nextStepCode}${e.nextRunAt ? ` em ${at(e.nextRunAt)}` : ''}` : 'Em andamento';
  }
}

export function failureText(code: string | null | undefined): string {
  return code ? (FAILURE_LABELS[code] ?? `Erro ${code}`) : 'Falha na etapa';
}

export interface Breakdown {
  key: string;
  label: string;
  count: number;
  /** Parte do maior valor (0–1), para a barra proporcional. */
  ratio: number;
}

/** Contagens por motivo em ordem decrescente, com rótulo e proporção para barras com texto. */
export function breakdown(map: Record<string, number> | null | undefined, labels: Record<string, string> | ((k: string) => string)): Breakdown[] {
  const entries = Object.entries(map ?? {}).filter(([, v]) => v > 0);
  const max = Math.max(1, ...entries.map(([, v]) => v));
  const label = typeof labels === 'function' ? labels : (k: string) => labels[k] ?? k;
  return entries.sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([key, value]) => ({ key, label: label(key), count: value, ratio: value / max }));
}

/** Inscrições ainda em curso que um encerramento alcança. */
export function inProgress(byStatus: Record<string, number> | null | undefined): number {
  const s = byStatus ?? {};
  return (s['ACTIVE'] ?? 0) + (s['PAUSED'] ?? 0) + (s['FAILED'] ?? 0) + (s['HOLDOUT'] ?? 0);
}

// ===================================================================== testes A/B

export const DEFAULT_CONTROL_SHARE = 10;
export const DEFAULT_OBSERVATION_DAYS = 30;
export const DEFAULT_MIN_SAMPLE = 100;
const VARIANT_CODE = /^[A-Z][A-Z0-9_]{0,39}$/;

export function newExperiment(): ExperimentInput {
  return {
    name: '',
    hypothesis: null,
    primaryMetric: 'PAYMENT',
    controlShare: DEFAULT_CONTROL_SHARE,
    variants: [
      { code: 'A', name: 'Régua atual', weight: 1, overrides: [] },
      { code: 'B', name: 'Variante B', weight: 1, overrides: [] },
    ],
    observationDays: DEFAULT_OBSERVATION_DAYS,
    minSamplePerArm: DEFAULT_MIN_SAMPLE,
  };
}

export function cloneExperiment(input: ExperimentInput): ExperimentInput {
  return {
    ...input,
    variants: input.variants.map((v) => ({
      ...v,
      weight: v.weight ?? 1,
      overrides: (v.overrides ?? []).map((o) => ({ ...o, window: o.window ? cloneWindow(o.window) : null, offer: o.offer ? { ...o.offer } : null })),
    })),
  };
}

/** Corpo enviado ao servidor: textos aparados e horários completos (`HH:mm:ss`). */
export function toServerExperiment(input: ExperimentInput): ExperimentInput {
  const full = (t: string | null) => (t ? (t.length === 5 ? `${t}:00` : t) : null);
  return {
    name: input.name.trim(),
    hypothesis: input.hypothesis?.trim() || null,
    primaryMetric: input.primaryMetric,
    controlShare: input.controlShare,
    observationDays: input.observationDays,
    minSamplePerArm: input.minSamplePerArm,
    variants: input.variants.map((v) => ({
      code: v.code.trim(),
      name: v.name.trim(),
      weight: v.weight ?? 1,
      overrides: v.overrides.map((o) => ({
        stepCode: o.stepCode,
        channel: o.channel || null,
        templateCode: o.templateCode?.trim() || null,
        window: o.window ? { start: full(o.window.start), end: full(o.window.end), days: [...(o.window.days ?? [])] } : null,
        offer: o.offer,
      })),
    })),
  };
}

export interface ArmShare {
  arm: string;
  label: string;
  /** Porcentagem dos devedores (0–100). */
  percent: number;
}

/** Repartição dos devedores: controle pela fração; demais pelos pesos (como `VariantAllocator`). */
export function allocation(controlShare: number | null | undefined, variants: readonly ExperimentVariant[]): ArmShare[] {
  const control = Math.min(Math.max(controlShare ?? 0, 0), 100);
  const weights = variants.map((v) => Math.max(v.weight ?? 1, 0));
  const total = weights.reduce((a, b) => a + b, 0) || 1;
  const arms: ArmShare[] = control > 0 ? [{ arm: CONTROL_ARM, label: CONTROL_LABEL, percent: control }] : [];
  variants.forEach((v, i) => arms.push({ arm: v.code, label: v.name || v.code, percent: ((100 - control) * weights[i]) / total }));
  return arms;
}

/** Mesmas regras de `ExperimentService`/`ExperimentVariant`/`StepOverride` (sobre a régua publicada, quando conhecida). */
export function experimentProblems(input: ExperimentInput, publishedSteps: readonly CadenceStep[] | null): string[] {
  const problems: string[] = [];
  if (!input.name.trim() || input.name.length > 120) {
    problems.push('Informe o nome do teste (até 120 caracteres).');
  }
  if ((input.hypothesis ?? '').length > 500) {
    problems.push('Hipótese com até 500 caracteres.');
  }
  const control = input.controlShare ?? DEFAULT_CONTROL_SHARE;
  if (!(control >= 0 && control <= 50)) {
    problems.push('Grupo de controle entre 0% e 50%.');
  }
  if (!inRange(input.observationDays ?? DEFAULT_OBSERVATION_DAYS, 1, 180)) {
    problems.push('Horizonte de observação entre 1 e 180 dias.');
  }
  if (!inRange(input.minSamplePerArm ?? DEFAULT_MIN_SAMPLE, 10, 1_000_000)) {
    problems.push('Amostra mínima por braço entre 10 e 1.000.000.');
  }
  if (!input.variants.length || input.variants.length > 10) {
    problems.push('Inclua de 1 a 10 variantes.');
  }
  if (control === 0 && input.variants.length < 2) {
    problems.push('Sem grupo de controle, inclua ao menos duas variantes para comparar.');
  }
  const codes = input.variants.map((v) => v.code);
  input.variants.forEach((v, i) => {
    const p = `Variante ${i + 1}${v.code ? ` (${v.code})` : ''}: `;
    if (!VARIANT_CODE.test(v.code) || v.code === CONTROL_ARM) {
      problems.push(`${p}código com letras maiúsculas, dígitos e _ (CONTROL é reservado).`);
    } else if (codes.indexOf(v.code) !== i) {
      problems.push(`${p}código repetido.`);
    }
    if (!v.name.trim() || v.name.length > 120) {
      problems.push(`${p}informe o nome (até 120 caracteres).`);
    }
    if (!inRange(v.weight ?? 1, 1, 100)) {
      problems.push(`${p}peso entre 1 e 100.`);
    }
    const stepCodes = v.overrides.map((o) => o.stepCode);
    v.overrides.forEach((o, j) => {
      if (stepCodes.indexOf(o.stepCode) !== j) {
        problems.push(`${p}uma alteração por etapa (${o.stepCode} repetida).`);
      }
      if (!o.stepCode) {
        problems.push(`${p}escolha a etapa alterada.`);
        return;
      }
      if (publishedSteps) {
        const step = publishedSteps.find((s) => s.code === o.stepCode);
        if (!step) {
          problems.push(`${p}a etapa ${o.stepCode} não existe na régua publicada.`);
        } else {
          if ((o.channel || o.templateCode) && !sendsMessage(step.action)) {
            problems.push(`${p}template e canal só em etapas de mensagem (${o.stepCode}).`);
          }
          if (o.offer && step.action.type !== 'OFFER') {
            problems.push(`${p}oferta só em etapas de oferta (${o.stepCode}).`);
          }
        }
      }
      if (o.templateCode && !TEMPLATE_CODE.test(o.templateCode)) {
        problems.push(`${p}código de template inválido em ${o.stepCode}.`);
      }
      if (o.window && !windowIsAlways(o.window) && shortTime(o.window.start) !== shortTime(o.window.end) && minutesOf(o.window.start ?? '') > minutesOf(o.window.end ?? '')) {
        problems.push(`${p}janela de ${o.stepCode} com início depois do fim.`);
      }
      if (!o.channel && !o.templateCode && !o.window && !o.offer) {
        problems.push(`${p}a alteração de ${o.stepCode} não muda nada.`);
      }
    });
  });
  return problems;
}

export function isRateMetric(metric: ExperimentMetric): boolean {
  return metric !== 'RECOVERED_PER_DEBTOR';
}

/** Estimativa da métrica ("12,4%" ou "R$ 35,10"). */
export function metricValue(metric: ExperimentMetric, value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return isRateMetric(metric) ? percent(value) : formatAmount(value);
}

/** Diferença com sinal explícito ("+2,1 p.p." ou "−R$ 3,20"). */
export function differenceValue(metric: ExperimentMetric, value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (isRateMetric(metric)) {
    return `${DECIMAL1.format(value * 100).replace('-', '−')} p.p.`;
  }
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${formatAmount(Math.abs(value))}`;
}

export function armLabel(arm: string, variants: readonly ExperimentVariant[]): string {
  if (arm === CONTROL_ARM) {
    return CONTROL_LABEL;
  }
  const v = variants.find((x) => x.code === arm);
  return v ? `${v.name} (${v.code})` : arm;
}

export interface Scale {
  min: number;
  max: number;
}

/** Posição (0–100%) de um valor na escala. */
export function position(value: number, scale: Scale): number {
  const span = scale.max - scale.min || 1;
  return Math.min(100, Math.max(0, ((value - scale.min) / span) * 100));
}

/** Escala comum aos braços: taxas de 0 até o maior limite superior com folga; valores a partir de 0. */
export function estimateScale(metric: ExperimentMetric, uppers: readonly (number | null)[]): Scale {
  const top = Math.max(0, ...uppers.filter((u): u is number => u !== null && Number.isFinite(u)));
  if (isRateMetric(metric)) {
    return { min: 0, max: Math.min(1, niceCeil(top * 1.15 || 0.1)) };
  }
  return { min: 0, max: niceCeil(top * 1.15 || 1) };
}

/** Escala simétrica em torno do zero para as diferenças (o zero sempre no centro). */
export function differenceScale(bounds: readonly (number | null)[]): Scale {
  const top = Math.max(...bounds.filter((b): b is number => b !== null && Number.isFinite(b)).map(Math.abs), 0);
  const edge = niceCeil(top * 1.15 || 0.01);
  return { min: -edge, max: edge };
}

function niceCeil(v: number): number {
  if (v <= 0) {
    return 1;
  }
  const exp = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / exp;
  const nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
  return nice * exp;
}
