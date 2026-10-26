import { formatAmount } from '../../../shared/components/amount/amount';
import { DELAY_BUCKET_LABELS } from './incentive.labels';
import {
  AdjustmentView,
  DisputeView,
  PlanStatus,
  PlanView,
  SegmentSelector,
  StatementDetail,
  StatementStatus,
  YearMonth,
} from './incentive.models';

// ------------------------------------------------------------------ meses (competência)

const MONTH_NAME = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' });

/** `yyyy-MM` de uma data ISO `yyyy-MM-dd`. */
export function monthOf(isoDate: string): YearMonth {
  return isoDate.slice(0, 7);
}

export function addMonths(month: YearMonth, delta: number): YearMonth {
  const [y, m] = month.split('-').map(Number);
  const index = y * 12 + (m - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

export function monthStart(month: YearMonth): string {
  return `${month}-01`;
}

export function monthEnd(month: YearMonth): string {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${month}-${String(last).padStart(2, '0')}`;
}

/** "agosto de 2026". */
export function monthLabel(month: YearMonth | null | undefined): string {
  if (!month) {
    return '—';
  }
  const [y, m] = month.split('-').map(Number);
  return MONTH_NAME.format(new Date(Date.UTC(y, m - 1, 1)));
}

/** Meses do mais recente ao mais antigo: `ahead` futuros, o corrente e `back` anteriores. */
export function monthOptions(today: string, back: number, ahead = 0): { value: YearMonth; label: string }[] {
  const current = monthOf(today);
  const out: { value: YearMonth; label: string }[] = [];
  for (let d = ahead; d >= -back; d--) {
    const value = addMonths(current, d);
    out.push({ value, label: monthLabel(value) });
  }
  return out;
}

/** Datas de início de vigência aceitas: dia 1º do mês corrente e dos próximos `ahead` meses. */
export function publishStartOptions(today: string, ahead = 12): { value: string; label: string }[] {
  const current = monthOf(today);
  return Array.from({ length: ahead + 1 }, (_, i) => {
    const m = addMonths(current, i);
    return { value: monthStart(m), label: `1º de ${monthLabel(m)}` };
  });
}

/**
 * Fins de vigência aceitos para aposentar: último dia de um mês, a partir do mês anterior ao
 * corrente (o servidor recusa se houver período fechado depois) e nunca antes da véspera do início.
 */
export function retireEndOptions(today: string, effectiveFrom: string | null, ahead = 12): { value: string; label: string }[] {
  const current = monthOf(today);
  const out: { value: string; label: string }[] = [];
  for (let i = -1; i <= ahead; i++) {
    const m = addMonths(current, i);
    const end = monthEnd(m);
    if (effectiveFrom && end < addDays(effectiveFrom, -1)) {
      continue;
    }
    out.push({ value: end, label: `${end.slice(8)} de ${monthLabel(m)}` });
  }
  return out;
}

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ números

const PERCENT_FRACTION = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
const DECIMAL = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
const FIXED1 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Fração da API (0,25) como percentual ("25,0%"); nulo vira "—". */
export function fraction(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : PERCENT_FRACTION.format(value);
}

/** Percentual já expresso de 0 a 100 (regras do plano, pesos de crédito): "1,5%". */
export function pct(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${DECIMAL.format(value)}%`;
}

/** Fator multiplicador: "× 1,2". */
export function factorText(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `× ${DECIMAL.format(value)}`;
}

/** Nota de monitoria (0–10) com uma casa: "7,5". */
export function score(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : FIXED1.format(value);
}

/** Número digitado em pt-BR ("1.234,5" ou "1234.5"); vazio ou inválido = null. */
export function parseDecimal(text: string | number | null | undefined): number | null {
  if (text === null || text === undefined) {
    return null;
  }
  if (typeof text === 'number') {
    return Number.isFinite(text) ? text : null;
  }
  const raw = text.replace(/R\$|%|\s/g, '');
  if (!raw) {
    return null;
  }
  const normalized = raw.includes(',') ? raw.replace(/\./g, '').replace(',', '.') : raw;
  if (!/^-?\d+(\.\d+)?$/.test(normalized)) {
    return null;
  }
  return Number(normalized);
}

/** Número para campo de texto em pt-BR (vírgula decimal, sem milhar). */
export function decimalInput(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value).replace('.', ',');
}

// ------------------------------------------------------------------ planos e versões

export interface PlanGroup {
  code: string;
  name: string;
  /** Versões da mais nova para a mais antiga. */
  versions: PlanView[];
  /** Versão publicada vigente hoje. */
  current: PlanView | null;
  draft: PlanView | null;
  priority: number;
}

export function isEffectiveOn(plan: PlanView, date: string): boolean {
  return plan.status !== 'DRAFT' && !!plan.effectiveFrom && plan.effectiveFrom <= date && (!plan.effectiveTo || plan.effectiveTo >= date);
}

/** Agrupa as versões por código (uma linha por plano na lista). */
export function groupPlans(plans: readonly PlanView[], today: string): PlanGroup[] {
  const byCode = new Map<string, PlanView[]>();
  for (const p of plans) {
    byCode.set(p.code, [...(byCode.get(p.code) ?? []), p]);
  }
  return [...byCode.entries()]
    .map(([code, list]) => {
      const versions = [...list].sort((a, b) => b.version - a.version);
      const current = versions.find((v) => isEffectiveOn(v, today)) ?? null;
      const draft = versions.find((v) => v.status === 'DRAFT') ?? null;
      const head = current ?? versions[0];
      return { code, name: head.name, versions, current, draft, priority: head.priority };
    })
    .sort((a, b) => b.priority - a.priority || a.code.localeCompare(b.code));
}

/** Situação exibida da versão: publicada se divide em vigente, futura ou encerrada. */
export function versionState(plan: PlanView, today: string): 'DRAFT' | 'CURRENT' | 'SCHEDULED' | 'ENDED' | PlanStatus {
  if (plan.status === 'DRAFT') {
    return 'DRAFT';
  }
  if (isEffectiveOn(plan, today)) {
    return 'CURRENT';
  }
  if (plan.effectiveFrom && plan.effectiveFrom > today) {
    return 'SCHEDULED';
  }
  return 'ENDED';
}

export interface PlanActions {
  edit: boolean;
  discard: boolean;
  publish: boolean;
  newVersion: boolean;
  retire: boolean;
  simulate: boolean;
}

/** Ações de uma versão (espelho de `CommissionPlan`: rascunho editável, publicada imutável). */
export function planActions(plan: PlanView, versions: readonly PlanView[], canManage: boolean, today: string): PlanActions {
  const draft = plan.status === 'DRAFT';
  const hasDraft = versions.some((v) => v.status === 'DRAFT');
  return {
    edit: canManage && draft,
    discard: canManage && draft,
    publish: canManage && draft,
    newVersion: canManage && !draft && !hasDraft,
    retire: canManage && plan.status === 'PUBLISHED' && (!plan.effectiveTo || plan.effectiveTo >= today),
    simulate: canManage,
  };
}

/** Texto da seleção de segmento ("Qualquer segmento" quando nada restringe). */
export function segmentText(segment: SegmentSelector, creditorName: (id: string) => string = (id) => id, portfolioName: (id: string) => string = (id) => id): string {
  const parts: string[] = [];
  if (segment.creditorIds.length) {
    parts.push(`Credor: ${segment.creditorIds.map(creditorName).join(', ')}`);
  }
  if (segment.portfolioIds.length) {
    parts.push(`Carteira: ${segment.portfolioIds.map(portfolioName).join(', ')}`);
  }
  if (segment.delayBuckets.length) {
    parts.push(segment.delayBuckets.map((b) => DELAY_BUCKET_LABELS[b]).join(', '));
  }
  return parts.length ? parts.join(' · ') : 'Qualquer segmento';
}

// ------------------------------------------------------------------ extratos

export const FINAL_STATEMENT: readonly StatementStatus[] = ['APPROVED', 'EXPORTED'];
/** Ajuste acima deste valor (absoluto) exige segunda aprovação (`app.incentive.dual-approval-threshold`, padrão). */
export const DUAL_APPROVAL_THRESHOLD = 500;
export const REASON_MIN = 10;
export const REASON_MAX = 2000;

export function needsSecondApproval(amount: number | null): boolean {
  return amount !== null && Math.abs(amount) > DUAL_APPROVAL_THRESHOLD;
}

export interface StatementActionContext {
  userId: string | null;
  canManage: boolean;
  canApprove: boolean;
}

export interface StatementActions {
  /** O beneficiário contesta item a item enquanto o extrato não é aprovado. */
  dispute: boolean;
  recalculate: boolean;
  adjust: boolean;
  approve: boolean;
  /** Por que a aprovação está indisponível para quem tem a permissão (segregação). */
  approveBlocked: string | null;
  own: boolean;
}

/** Ações do extrato com a segregação de funções do servidor (`CommissionStatement.approve`). */
export function statementActions(d: StatementDetail, ctx: StatementActionContext): StatementActions {
  const s = d.summary;
  const final = FINAL_STATEMENT.includes(s.status);
  const own = !!ctx.userId && ctx.userId === s.userId;
  const openDisputes = d.disputes.filter((x) => x.status === 'OPEN').length;
  const pending = d.adjustments.filter((a) => a.status === 'PENDING_SECOND_APPROVAL').length;
  const touched = ctx.userId !== null && (d.adjustments.some((a) => a.requestedBy === ctx.userId || a.decidedBy === ctx.userId) || d.disputes.some((x) => x.resolvedBy === ctx.userId));
  let approveBlocked: string | null = null;
  if (ctx.canApprove && !final) {
    if (own) {
      approveBlocked = 'Você é o beneficiário deste extrato e não pode aprová-lo.';
    } else if (touched) {
      approveBlocked = 'Você pediu ou decidiu ajuste, ou resolveu contestação, neste extrato: a aprovação é de outra pessoa.';
    } else if (openDisputes) {
      approveBlocked = `Há ${openDisputes} contestação(ões) em aberto. Resolva antes de aprovar.`;
    } else if (pending) {
      approveBlocked = `Há ${pending} ajuste(s) aguardando segunda aprovação. Decida antes de aprovar.`;
    }
  }
  return {
    dispute: own && !final,
    recalculate: ctx.canManage && !final,
    adjust: ctx.canManage && !final && !own,
    approve: ctx.canApprove && !final && approveBlocked === null,
    approveBlocked,
    own,
  };
}

/** Quem pode resolver: gestão, não quem abriu, contestação em aberto e extrato não aprovado. */
export function canResolve(d: DisputeView, status: StatementStatus, userId: string | null, canManage: boolean): boolean {
  return canManage && d.status === 'OPEN' && d.openedBy !== userId && !FINAL_STATEMENT.includes(status);
}

/** Segunda aprovação: gestão, outra pessoa que não quem pediu. */
export function canDecide(a: AdjustmentView, status: StatementStatus, userId: string | null, canManage: boolean): boolean {
  return canManage && a.status === 'PENDING_SECOND_APPROVAL' && a.requestedBy !== userId && !FINAL_STATEMENT.includes(status);
}

/** Alvo legível de uma contestação. */
export function disputeTarget(d: DisputeView): string {
  if (d.lineNumber !== null) {
    return `Linha ${d.lineNumber}`;
  }
  if (d.ledgerEntryId) {
    return `Lançamento ${d.ledgerEntryId.slice(0, 8)}`;
  }
  if (d.agreementId) {
    return `Acordo ${d.agreementId.slice(0, 8)} (faltante)`;
  }
  return '—';
}

/** Soma das linhas da memória = valor calculado (conferência exibida na tela). */
export function linesTotal(lines: readonly { amount: number }[]): number {
  return Math.round(lines.reduce((sum, l) => sum + l.amount, 0) * 100) / 100;
}

export function amountText(value: number | null | undefined): string {
  return formatAmount(value ?? 0);
}

// ------------------------------------------------------------------ período

/** O período pode ser fechado manualmente a partir de `closeableFrom` (fim + carência). */
export function canClosePeriod(p: { status: string; closeableFrom: string | null }, today: string): boolean {
  return p.status === 'OPEN' && !!p.closeableFrom && p.closeableFrom <= today;
}
