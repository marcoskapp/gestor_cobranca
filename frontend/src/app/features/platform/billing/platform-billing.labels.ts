import { PlatformCycleKind, PlatformMetric, TierChargeView, TierView } from '../data/platform-billing.models';

export const PLATFORM_METRIC_LABELS: Record<PlatformMetric, string> = {
  MONTHLY_FEE: 'Mensalidade',
  REQUESTS: 'Chamadas cobráveis (2xx/3xx)',
  MB_IN: 'Dados recebidos (MB)',
  MB_OUT: 'Dados enviados (MB)',
  RECORDS: 'Registros processados',
};

export const PLATFORM_CYCLE_KIND_LABELS: Record<PlatformCycleKind, string> = {
  REGULAR: 'ciclo regular',
  POLICY_START: 'primeiro ciclo da vigência',
  POLICY_END: 'último ciclo da vigência',
  TRANSITION: 'ciclo de transição (mudança do dia de fechamento)',
  NO_POLICY: 'sem política da plataforma (valor zero)',
};

/** Mensagens dos códigos de erro do faturamento da plataforma (o restante cai na mensagem do servidor). */
export const PLATFORM_BILLING_ERRORS: Record<string, string> = {
  'platform_policy.already_exists': 'A assessoria já tem política: registre uma nova versão.',
  'platform_policy.not_found': 'A assessoria ainda não tem política de cobrança da plataforma.',
  'platform_policy.closing_day_invalid': 'O dia de fechamento deve estar entre 1 e 31.',
  'platform_policy.scale_invalid': 'Use até 2 casas na mensalidade e até 6 nos preços unitários.',
  'platform_policy.value_invalid': 'Valores devem ser zero ou positivos.',
  'platform_policy.tier_order_invalid': 'Os limites das faixas devem ser crescentes e positivos.',
  'platform_policy.tier_last_unbounded': 'A última faixa de volume não tem limite.',
  'platform_policy.request_price_ambiguous': 'Com faixas de volume, o preço por chamada vem das faixas.',
  'platform_policy.start_locked': 'O início da vigência não muda depois do primeiro extrato.',
  'platform_policy.end_in_past': 'O fim da vigência não pode ficar no passado.',
  'platform_policy.end_before_closed': 'O fim da vigência não pode ser anterior a um ciclo já fechado.',
  'platform_policy.term_invalid': 'O fim da vigência não pode ser anterior ao início.',
  'platform_statement.already_closed': 'O extrato deste ciclo já foi fechado: informe o motivo para gerar nova revisão.',
  'platform_statement.period_open': 'O ciclo ainda está em curso: só ciclos encerrados podem ser fechados.',
  'platform_statement.nothing_to_close': 'Não há ciclo a fechar para esta assessoria.',
  'platform_statement.period_invalid': 'Este não é o próximo ciclo a fechar da assessoria.',
};

const NUMBER = new Intl.NumberFormat('pt-BR');

/** Faixa de volume legível: "1 a 100.000", "100.001 em diante". */
export function tierRange(t: Pick<TierChargeView, 'from' | 'to'>): string {
  return t.to === null ? `${NUMBER.format(t.from)} em diante` : `${NUMBER.format(t.from)} a ${NUMBER.format(t.to)}`;
}

/** Faixas cadastradas → texto curto para tabelas ("até 100.000: R$ 0,01 · acima: R$ 0,008"). */
export function tiersSummary(tiers: readonly TierView[]): string {
  const money = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 6 });
  return tiers.map((t, i) => {
    const previous = i === 0 ? null : tiers[i - 1].upTo;
    const range = t.upTo === null ? (previous === null ? 'todas' : `acima de ${NUMBER.format(previous)}`) : `até ${NUMBER.format(t.upTo)}`;
    return `${range}: ${money.format(t.unitPrice)}`;
  }).join(' · ');
}

/**
 * Valida as faixas digitadas antes de enviar (o servidor confere de novo): limites crescentes e positivos, só a
 * última sem limite. Devolve a mensagem do primeiro problema, ou `null`.
 */
export function tierProblem(tiers: readonly TierView[]): string | null {
  if (tiers.length > 10) {
    return 'No máximo 10 faixas de volume.';
  }
  let previous = 0;
  for (let i = 0; i < tiers.length; i++) {
    const last = i === tiers.length - 1;
    const upTo = tiers[i].upTo;
    if (last && upTo !== null) {
      return 'A última faixa não tem limite (deixe o campo "até" vazio).';
    }
    if (!last && (upTo === null || upTo <= previous)) {
      return `Faixa ${i + 1}: o limite deve ser maior que o da faixa anterior.`;
    }
    previous = upTo ?? previous;
  }
  return null;
}
