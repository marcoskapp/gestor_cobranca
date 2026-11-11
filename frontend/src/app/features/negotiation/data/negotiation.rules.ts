/**
 * Regras de tela do módulo negotiation (funções puras). Espelham as validações do domínio para
 * orientar antes do envio; o servidor valida tudo de novo (422 `policy.terms.*`).
 */
import {
  ComponentRates,
  ConcessionLevel,
  DEBT_COMPONENTS,
  DiscountTier,
  OfferView,
  OverrideGroup,
  PolicyOverride,
  PolicyRules,
  PolicyTerms,
  SimulationResult,
} from './negotiation.models';
import { COMPONENT_LABELS, OVERRIDE_GROUP_LABELS } from './negotiation.labels';

export const NO_RATES: ComponentRates = { principal: 0, correction: 0, interest: 0, fine: 0, fees: 0, honorarium: 0 };

export function uniformRates(value: number): ComponentRates {
  return { principal: value, correction: value, interest: value, fine: value, fees: value, honorarium: value };
}

/** Cópia profunda (termos são objetos JSON simples). */
export function cloneTerms<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function ratesProblems(rates: ComponentRates, what: string): string[] {
  return DEBT_COMPONENTS.filter((c) => !(rates[c] >= 0 && rates[c] <= 100)).map((c) => `${what}: ${COMPONENT_LABELS[c]} deve estar entre 0 e 100%.`);
}

function notAbove(a: ComponentRates, b: ComponentRates): boolean {
  return DEBT_COMPONENTS.every((c) => (a[c] ?? 0) <= (b[c] ?? 0));
}

export function tiersProblems(tiers: readonly DiscountTier[], where = ''): string[] {
  const problems: string[] = [];
  const seen = new Set<number>();
  for (const t of tiers) {
    if (!(t.upToInstallments >= 1 && t.upToInstallments <= 360)) {
      problems.push(`${where}Faixa de desconto: de 1 a 360 parcelas.`);
    }
    if (seen.has(t.upToInstallments)) {
      problems.push(`${where}Duas faixas de desconto para ${t.upToInstallments} parcela(s).`);
    }
    seen.add(t.upToInstallments);
    problems.push(...ratesProblems(t.maxDiscount, `${where}Faixa até ${t.upToInstallments}x`));
  }
  return problems;
}

export function ladderProblems(ladder: readonly ConcessionLevel[], maxInstallments: number, where = ''): string[] {
  const problems: string[] = [];
  if (ladder.length === 0) {
    return [`${where}A escada precisa de ao menos o nível 0.`];
  }
  const sorted = [...ladder].sort((a, b) => a.level - b.level);
  sorted.forEach((l, i) => {
    if (l.level !== i) {
      problems.push(`${where}Níveis da escada devem ser contíguos a partir de 0.`);
    }
    if (!l.name?.trim()) {
      problems.push(`${where}Nível ${l.level}: informe o nome.`);
    }
    if (l.maxInstallments !== null && (l.maxInstallments < 1 || l.maxInstallments > maxInstallments)) {
      problems.push(`${where}Nível ${l.level}: parcelas entre 1 e ${maxInstallments} (limite da política).`);
    }
    problems.push(...ratesProblems(l.envelopeShare, `${where}Nível ${l.level}`));
    if (i > 0 && !notAbove(sorted[i - 1].envelopeShare, l.envelopeShare)) {
      problems.push(`${where}Nível ${l.level} concede menos que o nível ${i - 1} em algum componente.`);
    }
  });
  return [...new Set(problems)];
}

export function rulesProblems(r: PolicyRules, where = ''): string[] {
  const p: string[] = [];
  const c = r.charges;
  if (!(c.interestMonthlyRate >= 0 && c.interestMonthlyRate <= 20)) p.push(`${where}Juros de mora entre 0 e 20% a.m.`);
  if (!(c.finePercent >= 0 && c.finePercent <= 20)) p.push(`${where}Multa entre 0 e 20%.`);
  if (!(c.honorariumPercent >= 0 && c.honorariumPercent <= 30)) p.push(`${where}Honorários entre 0 e 30%.`);
  if (c.correctionIndexCode && !/^[A-Z0-9_]{2,20}$/.test(c.correctionIndexCode)) p.push(`${where}Código de índice inválido.`);
  const codes = new Set<string>();
  for (const f of c.fees) {
    if (!/^[A-Z0-9_]{2,30}$/.test(f.code)) p.push(`${where}Encargo ${f.code || '(sem código)'}: código com 2 a 30 maiúsculas, dígitos ou _.`);
    if (!f.description?.trim()) p.push(`${where}Encargo ${f.code}: descrição obrigatória.`);
    if (!(f.value > 0) || (f.type === 'PERCENT_OF_PRINCIPAL' && f.value > 100)) p.push(`${where}Encargo ${f.code}: valor inválido.`);
    if (codes.has(f.code)) p.push(`${where}Encargo duplicado: ${f.code}.`);
    codes.add(f.code);
  }
  const i = r.installments;
  if (!(i.maxInstallments >= 1 && i.maxInstallments <= 360)) p.push(`${where}Parcelas máximas entre 1 e 360.`);
  if (!(i.minDownPaymentPercent >= 0 && i.minDownPaymentPercent < 100)) p.push(`${where}Entrada mínima entre 0 e 100% (exclusive).`);
  if (i.minInstallmentAmount < 0 || i.minDownPaymentAmount < 0) p.push(`${where}Valores mínimos não podem ser negativos.`);
  if (!(i.monthlyInterestRate >= 0 && i.monthlyInterestRate <= 20)) p.push(`${where}Juros do parcelamento entre 0 e 20% a.m.`);
  if (i.frequency === 'EVERY_N_DAYS' && !(i.intervalDays !== null && i.intervalDays >= 7 && i.intervalDays <= 90)) {
    p.push(`${where}Intervalo entre vencimentos de 7 a 90 dias.`);
  }
  if (!(r.validity.proposalValidityDays >= 1 && r.validity.proposalValidityDays <= 90)) p.push(`${where}Validade da proposta entre 1 e 90 dias.`);
  if (!(r.validity.maxDaysToFirstPayment >= 0 && r.validity.maxDaysToFirstPayment <= 90)) p.push(`${where}Prazo do 1º pagamento entre 0 e 90 dias.`);
  if (!(r.breakRule.overdueInstallments >= 1 && r.breakRule.overdueInstallments <= 12)) p.push(`${where}Parcelas em atraso para quebra entre 1 e 12.`);
  if (!(r.breakRule.toleranceDays >= 0 && r.breakRule.toleranceDays <= 180)) p.push(`${where}Tolerância da quebra entre 0 e 180 dias.`);
  p.push(...tiersProblems(r.discountTiers, where));
  p.push(...ladderProblems(r.ladder, i.maxInstallments, where));
  return p;
}

/** Regras resultantes de base + sobreposição (grupos não nulos substituem). */
export function overriddenRules(base: PolicyRules, o: PolicyOverride): PolicyRules {
  return {
    charges: o.charges ?? base.charges,
    discountTiers: o.discountTiers ?? base.discountTiers,
    installments: o.installments ?? base.installments,
    validity: o.validity ?? base.validity,
    ladder: o.ladder ?? base.ladder,
    breakRule: o.breakRule ?? base.breakRule,
  };
}

/** Nome de exibição da sobreposição: o informado ou, nas antigas, o código legível (ATRASO_LONGO → "Atraso longo"). */
export function overrideLabel(o: Pick<PolicyOverride, 'code' | 'name'>): string {
  const name = o.name?.trim();
  if (name) {
    return name;
  }
  const words = o.code.toLowerCase().split('_').filter(Boolean).join(' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : o.code;
}

/** Código sugerido a partir do nome ("Atraso longo" → ATRASO_LONGO): sem acentos, maiúsculas, dígitos e _. */
export function overrideCodeFrom(name: string): string {
  return name
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
}

export function specificity(o: Pick<PolicyOverride, 'portfolioId' | 'minDaysOverdue' | 'maxDaysOverdue'>): number {
  return (o.portfolioId ? 2 : 0) + (o.minDaysOverdue !== null || o.maxDaysOverdue !== null ? 1 : 0);
}

/** Sobreposições de mesma especificidade e mesma carteira com faixas que se interceptam (§9.2). */
export function overridesConflict(a: PolicyOverride, b: PolicyOverride): boolean {
  if (specificity(a) !== specificity(b) || a.portfolioId !== b.portfolioId) {
    return false;
  }
  const hasBand = a.minDaysOverdue !== null || a.maxDaysOverdue !== null;
  if (!hasBand) {
    return true;
  }
  const start = (o: PolicyOverride) => o.minDaysOverdue ?? 0;
  const end = (o: PolicyOverride) => o.maxDaysOverdue ?? Number.MAX_SAFE_INTEGER;
  return start(a) <= end(b) && start(b) <= end(a);
}

export function overriddenGroups(o: PolicyOverride): OverrideGroup[] {
  return (['charges', 'discountTiers', 'installments', 'validity', 'ladder', 'breakRule'] as OverrideGroup[]).filter((g) => o[g] !== null);
}

/** Todos os problemas dos termos (base, sobreposições e combinações). */
export function termsProblems(terms: PolicyTerms): string[] {
  const problems = rulesProblems(terms.base);
  const codes = new Set<string>();
  terms.overrides.forEach((o, i) => {
    const where = `Sobreposição ${o.code || i + 1}: `;
    if (!/^[A-Z0-9_]{2,40}$/.test(o.code)) problems.push(`${where}código com 2 a 40 maiúsculas, dígitos ou _.`);
    if (codes.has(o.code)) problems.push(`${where}código duplicado.`);
    codes.add(o.code);
    if (specificity(o) === 0) problems.push(`${where}informe a carteira ou a faixa de atraso.`);
    if ((o.minDaysOverdue ?? 0) < 0 || (o.maxDaysOverdue !== null && o.maxDaysOverdue < (o.minDaysOverdue ?? 0))) problems.push(`${where}faixa de atraso inválida.`);
    if (overriddenGroups(o).length === 0) problems.push(`${where}substitua ao menos um grupo (${Object.values(OVERRIDE_GROUP_LABELS).join(', ').toLowerCase()}).`);
    problems.push(...rulesProblems(overriddenRules(terms.base, o), where));
    for (let j = 0; j < i; j++) {
      if (overridesConflict(o, terms.overrides[j])) {
        problems.push(`${where}intercepta a sobreposição ${terms.overrides[j].code}.`);
      }
    }
  });
  return [...new Set(problems)];
}

// --------------------------------------------------------------------- simulação

export interface SideBySide {
  level: number;
  levelName: string;
  cash: OfferView | null;
  installment: OfferView | null;
}

/** Ofertas do nível: à vista e o maior parcelamento, para a comparação lado a lado. */
export function sideBySide(result: SimulationResult, level: number): SideBySide {
  const offers = result.offers.filter((o) => o.level === level);
  const cash = offers.find((o) => o.installments === 1) ?? null;
  const installment = offers.filter((o) => o.installments > 1).sort((a, b) => b.installments - a.installments)[0] ?? null;
  return { level, levelName: (cash ?? installment)?.levelName ?? `Nível ${level}`, cash, installment };
}

/** Níveis presentes nas ofertas, em ordem. */
export function offeredLevels(result: SimulationResult): number[] {
  return [...new Set(result.offers.map((o) => o.level))].sort((a, b) => a - b);
}

/** Subir acima do nível 0 exige justificativa; "outro motivo" exige descrição. */
export function justificationProblem(level: number, reason: string | null, note: string): string | null {
  if (level <= 0) {
    return null;
  }
  if (!reason) {
    return 'Para oferecer acima do nível 0, informe o motivo da concessão.';
  }
  if (reason === 'OTHER' && !note.trim()) {
    return 'Descreva o motivo da concessão.';
  }
  return null;
}

/** Economia do devedor (desconto) em relação à dívida atualizada, em %. */
export function savingShare(offer: Pick<OfferView, 'discountTotal'>, totalAmount: number): number {
  return totalAmount > 0 ? Math.round((offer.discountTotal / totalAmount) * 10000) / 100 : 0;
}

/**
 * Forma de pagamento como o operador fala com o devedor ("à vista", "N parcelas", "entrada + N
 * parcelas"). A API conta pagamentos com a entrada (`installments`), o que levava a digitar 11 em vez
 * de 12; a tela pede só o número de parcelas e converte.
 */
export type PlanMode = 'CASH' | 'INSTALLMENTS' | 'DOWN';

export interface PlanFields {
  mode: PlanMode;
  /** Parcelas sem contar a entrada (ignorado à vista). */
  parcels: number | null;
  /** Valor da entrada (só em `DOWN`). */
  downPayment: number | null;
}

/** Campos da tela a partir do plano da API (pagamentos incluem a entrada). */
export function fieldsFromPlan(installments: number, downPayment: number | null): PlanFields {
  if (installments <= 1) {
    return { mode: 'CASH', parcels: null, downPayment: null };
  }
  return downPayment
    ? { mode: 'DOWN', parcels: installments - 1, downPayment }
    : { mode: 'INSTALLMENTS', parcels: installments, downPayment: null };
}

/** Plano da API (pagamentos com a entrada) a partir dos campos; nulo quando os campos não fecham um plano. */
export function planFromFields(f: PlanFields): { installments: number; downPayment: number | null } | null {
  if (f.mode === 'CASH') {
    return { installments: 1, downPayment: null };
  }
  const parcels = Number(f.parcels);
  if (!Number.isInteger(parcels) || parcels < 1) {
    return null;
  }
  if (f.mode === 'INSTALLMENTS') {
    return parcels < 2 ? null : { installments: parcels, downPayment: null };
  }
  const down = Number(f.downPayment);
  return down > 0 ? { installments: parcels + 1, downPayment: down } : null;
}

/** Orientação dos campos da forma de pagamento (o servidor valida de novo os limites da política e da alçada). */
export function planFieldsProblem(f: PlanFields, maxPayments: number | null = null): string | null {
  if (f.mode === 'CASH') {
    return null;
  }
  const parcels = Number(f.parcels);
  if (!Number.isInteger(parcels) || parcels < (f.mode === 'INSTALLMENTS' ? 2 : 1)) {
    return f.mode === 'INSTALLMENTS' ? 'Parcelado sem entrada: informe 2 parcelas ou mais.' : 'Informe o número de parcelas depois da entrada.';
  }
  if (f.mode === 'DOWN' && !(Number(f.downPayment) > 0)) {
    return 'Informe o valor da entrada.';
  }
  const total = f.mode === 'DOWN' ? parcels + 1 : parcels;
  if (maxPayments !== null && total > maxPayments) {
    return f.mode === 'DOWN'
      ? `Entrada + ${parcels} parcelas são ${total} pagamentos; o limite é ${maxPayments}.`
      : `O limite é de ${maxPayments} parcelas.`;
  }
  return null;
}
