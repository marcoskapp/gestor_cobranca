import { AdjustmentView, CommissionRules, RateRule, StatementView } from './settlement.models';

/**
 * Regras de apresentação do settlement. A validação espelha `CommissionRules.validated()` e
 * `RateRule.validate()` do backend para orientar o usuário durante a edição; o servidor repete
 * todas as regras em toda escrita e leitura.
 */

export const DEFAULT_RULE_CODE = 'PADRAO';
export const RULE_CODE_PATTERN = /^[A-Z0-9_]{1,40}$/;
export const MAX_RATE_RULES = 100;
/** `app.settlement.adjustment-dual-approval-threshold` (padrão R$ 1.000,00). */
export const ADJUSTMENT_DUAL_APPROVAL_THRESHOLD = 1000;
export const ADJUSTMENT_REASON_MIN = 10;

const decimals = (value: number) => {
  const text = String(value);
  const i = text.indexOf('.');
  return i < 0 ? 0 : text.length - i - 1;
};

function rateProblem(rate: number | null | undefined, label: string): string | null {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return `${label}: informe o percentual.`;
  }
  if (rate < 0 || rate > 100 || decimals(rate) > 4) {
    return `${label}: percentual entre 0 e 100, com até 4 casas decimais.`;
  }
  return null;
}

/** Quantos critérios a regra usa (carteira, faixa de atraso, tipo, quem recebeu, renegociação). */
export function specificity(rule: Pick<RateRule, 'portfolioId' | 'minDaysPastDue' | 'maxDaysPastDue' | 'paymentType' | 'receivedBy' | 'renegotiation'>): number {
  return [
    rule.portfolioId,
    rule.minDaysPastDue !== null || rule.maxDaysPastDue !== null ? true : null,
    rule.paymentType,
    rule.receivedBy,
    rule.renegotiation,
  ].filter((c) => c !== null && c !== undefined).length;
}

/** Problemas de uma regra específica (vazio = válida). */
export function rateRuleProblems(rule: RateRule): string[] {
  const out: string[] = [];
  const name = rule.code || 'Regra sem código';
  if (!RULE_CODE_PATTERN.test(rule.code ?? '') || rule.code === DEFAULT_RULE_CODE) {
    out.push(`${name}: código com letras maiúsculas, dígitos e _ (até 40), diferente de ${DEFAULT_RULE_CODE}.`);
  }
  if ((rule.description ?? '').length > 200) {
    out.push(`${name}: descrição com até 200 caracteres.`);
  }
  if (!Number.isInteger(rule.priority) || rule.priority < 0 || rule.priority > 1000) {
    out.push(`${name}: prioridade entre 0 e 1000.`);
  }
  const min = rule.minDaysPastDue;
  const max = rule.maxDaysPastDue;
  if ((min !== null && min < 0) || (max !== null && max < 0) || (min !== null && max !== null && max < min)) {
    out.push(`${name}: faixa de atraso inválida (o fim não pode ser anterior ao início).`);
  }
  const rate = rateProblem(rule.rate, name);
  if (rate) {
    out.push(rate);
  }
  if (specificity(rule) === 0) {
    out.push(`${name}: sem critério. Use o percentual padrão em vez de uma regra sem critério.`);
  }
  return out;
}

/** Problemas do conjunto de regras (vazio = pode salvar e publicar). */
export function rulesProblems(rules: CommissionRules): string[] {
  const out: string[] = [];
  const base = rateProblem(rules.defaultRate, 'Percentual padrão');
  if (base) {
    out.push(base);
  }
  const fee = rules.fixedFeePerAgreement;
  if (fee !== null && fee !== undefined && (fee < 0 || decimals(fee) > 2)) {
    out.push('Valor fixo por acordo: positivo, com até 2 casas decimais.');
  }
  if (!rules.honorarium?.ownership) {
    out.push('Informe a quem pertencem os honorários cobrados do devedor.');
  } else if (rules.honorarium.ownership === 'AGENCY') {
    const share = rateProblem(rules.honorarium.agencySharePercent, 'Fração dos honorários da assessoria');
    if (share) {
      out.push(share);
    }
  }
  if (rules.rateRules.length > MAX_RATE_RULES) {
    out.push(`No máximo ${MAX_RATE_RULES} regras específicas.`);
  }
  const codes = new Set<string>();
  for (const rule of rules.rateRules) {
    out.push(...rateRuleProblems(rule));
    if (rule.code && codes.has(rule.code)) {
      out.push(`Código de regra repetido: ${rule.code}.`);
    }
    codes.add(rule.code);
  }
  return out;
}

/** Regras do modelo inicial (padrão de mercado; o financeiro ajusta à transcrição do contrato). */
export function emptyRules(): CommissionRules {
  return { defaultRate: 10, rateRules: [], fixedFeePerAgreement: null, honorarium: { ownership: 'AGENCY', agencySharePercent: 100 }, reconciliationRequired: true };
}

export function cloneRules(rules: CommissionRules): CommissionRules {
  return {
    ...rules,
    rateRules: rules.rateRules.map((r) => ({ ...r })),
    honorarium: { ...(rules.honorarium ?? { ownership: 'AGENCY', agencySharePercent: 100 }) },
  };
}

/** Critérios da regra em texto ("Carteira X · 91 a 180 dias · parcelado"). */
export function criteriaText(rule: RateRule, portfolioName: (id: string) => string = () => 'carteira'): string {
  const parts: string[] = [];
  if (rule.portfolioId) {
    parts.push(portfolioName(rule.portfolioId));
  }
  if (rule.minDaysPastDue !== null || rule.maxDaysPastDue !== null) {
    if (rule.minDaysPastDue !== null && rule.maxDaysPastDue !== null) {
      parts.push(`${rule.minDaysPastDue} a ${rule.maxDaysPastDue} dias de atraso`);
    } else if (rule.minDaysPastDue !== null) {
      parts.push(`a partir de ${rule.minDaysPastDue} dias de atraso`);
    } else {
      parts.push(`até ${rule.maxDaysPastDue} dias de atraso`);
    }
  }
  if (rule.paymentType) {
    parts.push(rule.paymentType === 'LUMP_SUM' ? 'à vista' : 'parcelado');
  }
  if (rule.receivedBy) {
    parts.push(rule.receivedBy === 'AGENCY' ? 'recebido pela assessoria' : 'pago direto ao credor');
  }
  if (rule.renegotiation !== null) {
    parts.push(rule.renegotiation ? 'renegociação' : 'primeiro acordo');
  }
  return parts.join(' · ') || 'sem critério';
}

// --------------------------------------------------------------------------- demonstrativo

export interface StatementContext {
  userId: string | null;
  canClose: boolean;
  canApprove: boolean;
  canTransfer: boolean;
  /** Data de hoje no fuso de operação ("yyyy-MM-dd"). */
  today: string;
  adjustments: readonly AdjustmentView[];
  exportsCount: number;
}

export interface StatementActions {
  calculate: boolean;
  /** Período ainda não encerrado: cálculo bloqueado (explicação na tela). */
  periodOpen: boolean;
  review: boolean;
  approve: boolean;
  /** Motivo da segregação quando o usuário tem a permissão de aprovar mas não pode aprovar este. */
  approveBlocked: string | null;
  adjust: boolean;
  export: boolean;
  send: boolean;
  respond: boolean;
  contest: boolean;
  cancel: boolean;
  transfer: boolean;
}

const OPEN: readonly string[] = ['DRAFT', 'CALCULATED', 'REVIEWED'];
const APPROVED: readonly string[] = ['APPROVED', 'SENT', 'ACKNOWLEDGED', 'CONTESTED'];

export function isOpen(s: Pick<StatementView, 'status'>): boolean {
  return OPEN.includes(s.status);
}

export function isApproved(s: Pick<StatementView, 'status'>): boolean {
  return APPROVED.includes(s.status);
}

/** Ações disponíveis no demonstrativo, com a segregação de funções explicada (o servidor decide). */
export function statementActions(s: StatementView, ctx: StatementContext): StatementActions {
  const open = isOpen(s);
  const periodOpen = s.periodEnd >= ctx.today;
  const pending = ctx.adjustments.some((a) => a.status === 'PENDING_SECOND_APPROVAL');
  const requesters = new Set(ctx.adjustments.filter((a) => a.status === 'APPLIED').map((a) => a.requestedBy));
  let approveBlocked: string | null = null;
  if (s.status === 'REVIEWED' && ctx.canApprove) {
    if (ctx.userId && s.calculatedBy === ctx.userId) {
      approveBlocked = 'Você calculou este demonstrativo: a aprovação precisa ser de outra pessoa (quatro olhos).';
    } else if (ctx.userId && requesters.has(ctx.userId)) {
      approveBlocked = 'Você pediu um ajuste aplicado neste demonstrativo: a aprovação precisa ser de outra pessoa.';
    } else if (pending) {
      approveBlocked = 'Há ajuste aguardando segunda aprovação: decida-o antes de aprovar.';
    }
  }
  const outstanding = s.totals?.outstanding ?? 0;
  return {
    calculate: ctx.canClose && open && !periodOpen,
    periodOpen: open && periodOpen,
    review: ctx.canClose && s.status === 'CALCULATED',
    approve: ctx.canApprove && s.status === 'REVIEWED' && approveBlocked === null,
    approveBlocked,
    adjust: ctx.canClose && (s.status === 'CALCULATED' || s.status === 'REVIEWED'),
    export: ctx.canClose && isApproved(s),
    send: ctx.canClose && s.status === 'APPROVED' && ctx.exportsCount > 0,
    respond: ctx.canClose && (s.status === 'SENT' || s.status === 'CONTESTED'),
    contest: ctx.canClose && s.status === 'SENT',
    cancel: ctx.canClose && open,
    transfer: ctx.canTransfer && isApproved(s) && Math.abs(outstanding) > 0.004,
  };
}

/** Ajuste acima do limite fica aguardando a segunda aprovação de outra pessoa. */
export function needsSecondApproval(amount: number | null | undefined, threshold = ADJUSTMENT_DUAL_APPROVAL_THRESHOLD): boolean {
  return Math.abs(amount ?? 0) > threshold;
}

/** O usuário pode decidir (segunda aprovação) o ajuste pendente? Quem pediu não decide. */
export function canDecideAdjustment(a: AdjustmentView, userId: string | null, canApprove: boolean): boolean {
  return canApprove && a.status === 'PENDING_SECOND_APPROVAL' && a.requestedBy !== userId;
}

/** Índice da etapa atual na linha do ciclo (finais alternativos na posição da resposta do credor). */
export function cycleIndex(status: StatementView['status']): number {
  const order = ['DRAFT', 'CALCULATED', 'REVIEWED', 'APPROVED', 'SENT', 'ACKNOWLEDGED'];
  if (status === 'CONTESTED') {
    return 5;
  }
  return order.indexOf(status);
}

/** Conferência do líquido: Σ efeito no repasse − despesas + ajustes = líquido (§ layout do demonstrativo). */
export function receiptsEffect(t: NonNullable<StatementView['totals']>): number {
  return round2(t.netTransfer + t.expenses - t.adjustments);
}

export function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

/** SHA-256 em hexadecimal minúsculo (Web Crypto). */
export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Impressão digital em grupos de 8 (leitura e conferência em voz alta). */
export function groupedHash(hash: string | null | undefined): string {
  return (hash ?? '').replace(/(.{8})(?=.)/g, '$1 ');
}

/** Período "01/09/2026 a 30/09/2026". */
export function periodText(start: string, end: string): string {
  const br = (d: string) => d.split('-').reverse().join('/');
  return `${br(start)} a ${br(end)}`;
}
