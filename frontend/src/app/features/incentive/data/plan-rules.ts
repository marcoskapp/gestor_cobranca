import { ORIGINS } from './incentive.labels';
import {
  AgreementOrigin,
  EarlyBreakDeflator,
  EnvelopeDeflator,
  FixedPerAgreementComponent,
  GoalComponent,
  MonitoringGate,
  PlanRules,
  RateTable,
  RecoveryRateComponent,
  SegmentSelector,
  TeamPoolComponent,
} from './incentive.models';

/**
 * Regras de plano no cliente: modelo inicial (espelho de `StandardPlans.padraoMercado()`), padrões
 * de cada componente opcional e a mesma validação de `PlanRules.validate()` (o servidor continua
 * validando tudo; aqui é para mostrar o problema antes de salvar).
 */

export const emptySegment = (): SegmentSelector => ({ creditorIds: [], portfolioIds: [], delayBuckets: [] });

export const defaultRecoveryRate = (): RecoveryRateComponent => ({
  mode: 'PROGRESSIVE',
  tables: [defaultRateTable()],
});

export const defaultRateTable = (): RateTable => ({
  segment: emptySegment(),
  tiers: [
    { fromAmount: 0, ratePercent: 1.5 },
    { fromAmount: 50000, ratePercent: 2 },
    { fromAmount: 100000, ratePercent: 2.5 },
  ],
});

export const defaultFixed = (): FixedPerAgreementComponent => ({ amount: 10 });

export const defaultGoal = (): GoalComponent => ({
  metric: 'RECOVERED',
  scope: 'INDIVIDUAL',
  defaultTarget: null,
  triggerPercent: 80,
  belowTriggerFactor: 0.5,
  acceleratorThresholdPercent: 110,
  acceleratorFactor: 1.2,
  acceleratorMode: 'TOTAL',
  capPercent: 300,
});

export const defaultEarlyBreak = (): EarlyBreakDeflator => ({ windowDays: 60, thresholdPercent: 35, deflatorPercent: 20, minSample: 10 });
export const defaultEnvelope = (): EnvelopeDeflator => ({ thresholdPercent: 85, deflatorPercent: 10, minSample: 10 });
export const defaultGate = (): MonitoringGate => ({ minScore: 7, suspendWithoutEvaluation: false });
export const defaultPool = (): TeamPoolComponent => ({ ratePercent: 0.3, includeAssistantAutonomous: true, distribution: 'SUPERVISOR' });

export const DEFAULT_ORIGIN_WEIGHTS: Record<AgreementOrigin, number> = {
  HUMAN: 100,
  HUMAN_COPILOT: 100,
  ASSISTANT_HANDOFF_NEGOTIATED: 100,
  ASSISTANT_HANDOFF_CONFIRMED: 30,
  ASSISTANT_AUTONOMOUS: 0,
  PORTAL_SELF_SERVICE: 50,
  CAMPAIGN: 50,
};

/** Plano de referência de mercado (§4.3 do desenho), para planos novos sem versão a copiar. */
export function standardRules(): PlanRules {
  return {
    eligibility: { roleCodes: ['OPERADOR', 'SUPERVISOR'], teamIds: [], creditorIds: [], portfolioIds: [], minWorkedDays: 0 },
    recoveryRate: defaultRecoveryRate(),
    fixedPerAgreement: null,
    goal: defaultGoal(),
    difficultyMultipliers: [
      { segment: { ...emptySegment(), delayBuckets: ['D0_30', 'D31_90'] }, factor: 1 },
      { segment: { ...emptySegment(), delayBuckets: ['D91_180'] }, factor: 1.2 },
      { segment: { ...emptySegment(), delayBuckets: ['D181_360'] }, factor: 1.4 },
      { segment: { ...emptySegment(), delayBuckets: ['D361_PLUS'] }, factor: 1.8 },
    ],
    quality: { earlyBreak: defaultEarlyBreak(), envelope: defaultEnvelope(), monitoringGate: defaultGate(), zeroOnSevereViolation: true },
    attribution: {
      originWeights: { ...DEFAULT_ORIGIN_WEIGHTS },
      influenceWindowDays: 7,
      creditModel: 'ORIGINATOR',
      ownRenegotiationWeightPercent: 50,
      terminationPolicy: 'CREDIT_UNTIL_TERMINATION_PLUS_GRACE',
      terminationGraceDays: 5,
    },
    teamPool: defaultPool(),
    limits: { periodCap: null },
    clearing: { graceBusinessDays: 5, clawbackEnabled: false },
  };
}

/** Cópia profunda (o editor altera o objeto no lugar). */
export function cloneRules(rules: PlanRules): PlanRules {
  return structuredClone(rules);
}

/**
 * Normaliza o que o editor produz antes de enviar: listas ordenadas, conjuntos sem repetição,
 * campos numéricos vazios como nulos e códigos de perfil em maiúsculas.
 */
export function normalizeRules(rules: PlanRules): PlanRules {
  const r = cloneRules(rules);
  const uniq = <T>(list: T[]) => [...new Set(list)];
  const seg = (s: SegmentSelector): SegmentSelector => ({ creditorIds: uniq(s.creditorIds), portfolioIds: uniq(s.portfolioIds), delayBuckets: uniq(s.delayBuckets) });
  r.eligibility.roleCodes = uniq(r.eligibility.roleCodes.map((c) => c.trim().toUpperCase()).filter(Boolean));
  r.eligibility.teamIds = uniq(r.eligibility.teamIds);
  r.eligibility.creditorIds = uniq(r.eligibility.creditorIds);
  r.eligibility.portfolioIds = uniq(r.eligibility.portfolioIds);
  if (r.recoveryRate) {
    r.recoveryRate.tables = r.recoveryRate.tables.map((t) => ({ segment: seg(t.segment), tiers: [...t.tiers].sort((a, b) => a.fromAmount - b.fromAmount) }));
  }
  r.difficultyMultipliers = r.difficultyMultipliers.map((m) => ({ segment: seg(m.segment), factor: m.factor }));
  if (r.goal) {
    r.goal.defaultTarget = blankToNull(r.goal.defaultTarget);
    r.goal.capPercent = blankToNull(r.goal.capPercent);
  }
  r.limits.periodCap = blankToNull(r.limits.periodCap);
  return r;
}

function blankToNull(value: number | null | string | undefined): number | null {
  return value === null || value === undefined || value === '' || Number.isNaN(Number(value)) ? null : Number(value);
}

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const between = (v: unknown, min: number, max: number) => isNum(v) && v >= min && v <= max;
const isInt = (v: unknown) => isNum(v) && Number.isInteger(v);

/** Problemas das regras, em pt-BR, na mesma ordem da validação do servidor. Vazio = válidas. */
export function validateRules(r: PlanRules): string[] {
  const issues: string[] = [];
  const need = (ok: boolean, message: string) => {
    if (!ok) {
      issues.push(message);
    }
  };
  need(!!r.recoveryRate || !!r.fixedPerAgreement || !!r.teamPool, 'Inclua ao menos um componente que remunere: percentual sobre recuperado, valor fixo por acordo ou pool da equipe.');
  need(isInt(r.eligibility.minWorkedDays) && between(r.eligibility.minWorkedDays, 0, 31), 'Elegibilidade: dias trabalhados mínimos entre 0 e 31.');
  need(
    r.eligibility.roleCodes.every((c) => /^[A-Z0-9_]{2,40}$/.test(c.trim().toUpperCase())),
    'Elegibilidade: códigos de perfil com 2 a 40 letras maiúsculas, números ou _.',
  );
  if (r.recoveryRate) {
    need(r.recoveryRate.tables.length > 0, 'Percentual sobre recuperado: informe ao menos uma tabela de taxas.');
    r.recoveryRate.tables.forEach((t, i) => {
      const label = `Tabela ${i + 1}`;
      need(t.tiers.length > 0, `${label}: informe ao menos uma faixa.`);
      const sorted = [...t.tiers].sort((a, b) => a.fromAmount - b.fromAmount);
      if (sorted.length) {
        need(sorted[0].fromAmount === 0, `${label}: a primeira faixa começa em R$ 0,00.`);
      }
      need(
        t.tiers.every((tier) => isNum(tier.fromAmount) && tier.fromAmount >= 0),
        `${label}: início de faixa inválido.`,
      );
      need(
        t.tiers.every((tier) => between(tier.ratePercent, 0, 100)),
        `${label}: taxa de cada faixa entre 0% e 100%.`,
      );
      need(new Set(t.tiers.map((x) => x.fromAmount)).size === t.tiers.length, `${label}: as faixas precisam ter inícios diferentes.`);
    });
  }
  if (r.fixedPerAgreement) {
    const a = r.fixedPerAgreement.amount;
    need(isNum(a) && a > 0 && Math.round(a * 100) === a * 100, 'Valor fixo por acordo: positivo, com até 2 casas decimais.');
  }
  if (r.goal) {
    const g = r.goal;
    need(g.metric !== 'RECOVERED_VS_EXPECTED', 'Meta: recuperado × esperado ainda não é apurado pelo motor de comissão.');
    need(g.defaultTarget === null || (isNum(g.defaultTarget) && g.defaultTarget > 0), 'Meta: a meta padrão deve ser positiva (ou vazia).');
    need(between(g.triggerPercent, 0, 1000), 'Meta: gatilho entre 0% e 1000%.');
    need(between(g.belowTriggerFactor, 0, 1), 'Meta: fator abaixo do gatilho entre 0 e 1.');
    need(isNum(g.acceleratorThresholdPercent) && g.acceleratorThresholdPercent >= g.triggerPercent, 'Meta: o limiar do acelerador deve ser maior ou igual ao gatilho.');
    need(between(g.acceleratorFactor, 1, 10), 'Meta: fator do acelerador entre 1 e 10.');
    need(g.capPercent === null || (isNum(g.capPercent) && g.capPercent >= g.acceleratorThresholdPercent), 'Meta: o teto deve ser maior ou igual ao limiar do acelerador (ou vazio).');
  }
  r.difficultyMultipliers.forEach((m, i) => need(between(m.factor, 0, 10), `Multiplicador ${i + 1}: fator entre 0 e 10.`));
  const q = r.quality;
  if (q.earlyBreak) {
    need([30, 60, 90].includes(Number(q.earlyBreak.windowDays)), 'Quebra precoce: janela de 30, 60 ou 90 dias.');
    need(between(q.earlyBreak.thresholdPercent, 0, 100) && between(q.earlyBreak.deflatorPercent, 0, 100), 'Quebra precoce: limite e redução entre 0% e 100%.');
    need(isInt(q.earlyBreak.minSample) && q.earlyBreak.minSample >= 1, 'Quebra precoce: amostra mínima de pelo menos 1 acordo.');
  }
  if (q.envelope) {
    need(between(q.envelope.thresholdPercent, 0, 100) && between(q.envelope.deflatorPercent, 0, 100), 'Uso do envelope: limite e redução entre 0% e 100%.');
    need(isInt(q.envelope.minSample) && q.envelope.minSample >= 1, 'Uso do envelope: amostra mínima de pelo menos 1 acordo.');
  }
  if (q.monitoringGate) {
    need(between(q.monitoringGate.minScore, 0, 10), 'Portão de monitoria: nota mínima entre 0 e 10.');
  }
  const a = r.attribution;
  for (const origin of ORIGINS) {
    need(between(a.originWeights[origin], 0, 100), `Atribuição: informe o peso (0% a 100%) de todas as origens.`);
    if (!between(a.originWeights[origin], 0, 100)) {
      break;
    }
  }
  need(a.originWeights.ASSISTANT_AUTONOMOUS === 0, 'Atribuição: acordo fechado sozinho pelo assistente tem peso 0% (vai para o pool da equipe).');
  need(isInt(a.influenceWindowDays) && between(a.influenceWindowDays, 0, 90), 'Atribuição: janela de influência entre 0 e 90 dias.');
  need(between(a.ownRenegotiationWeightPercent, 0, 100), 'Atribuição: peso da renegociação própria entre 0% e 100%.');
  need(isInt(a.terminationGraceDays) && between(a.terminationGraceDays, 0, 60), 'Atribuição: carência de desligamento entre 0 e 60 dias úteis.');
  if (r.teamPool) {
    need(isNum(r.teamPool.ratePercent) && r.teamPool.ratePercent > 0 && r.teamPool.ratePercent <= 100, 'Pool da equipe: taxa positiva, até 100%.');
  }
  need(r.limits.periodCap === null || (isNum(r.limits.periodCap) && r.limits.periodCap > 0), 'Limites: o teto por período deve ser positivo (ou vazio).');
  need(isInt(r.clearing.graceBusinessDays) && between(r.clearing.graceBusinessDays, 0, 60), 'Carência: entre 0 e 60 dias úteis.');
  return issues;
}
