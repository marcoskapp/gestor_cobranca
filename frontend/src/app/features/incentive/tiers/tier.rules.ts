import { MyTier, QuartileKey, TierLevel, TierMatrix, TierPerson, TierPeriodicity, TierRoundSummary, TierSettings } from './tier.models';

export const TIER_LEVELS: readonly TierLevel[] = ['A', 'B', 'C', 'D'];
/** Ordem da tela (do melhor para o pior), a mesma do desenho. */
export const QUARTILES_DESC: readonly QuartileKey[] = ['Q4', 'Q3', 'Q2', 'Q1'];

export const PERIODICITY_LABELS: Record<TierPeriodicity, string> = { MONTHLY: 'Mensal (1º dia útil do mês)', WEEKLY: 'Semanal (segunda-feira)' };
export const ORIGIN_LABELS: Record<TierRoundSummary['origin'], string> = { SCHEDULED: 'automática', MANUAL: 'apurada agora' };

export const TIER_DESCRIPTIONS: Record<TierLevel, string> = {
  A: 'Recebe primeiro os casos de maior valor esperado e mais casos.',
  B: 'Recebe casos de valor alto e médio.',
  C: 'Recebe casos de valor médio e baixo.',
  D: 'Recebe os casos de menor valor esperado e menos casos.',
};

/** Matriz padrão do desenho (§3.2): qualidade × eficiência. */
export const STANDARD_MATRIX: TierMatrix = {
  Q4: { Q4: 'A', Q3: 'A', Q2: 'B', Q1: 'C' },
  Q3: { Q4: 'A', Q3: 'B', Q2: 'B', Q1: 'C' },
  Q2: { Q4: 'B', Q3: 'C', Q2: 'C', Q1: 'D' },
  Q1: { Q4: 'C', Q3: 'C', Q2: 'D', Q1: 'D' },
};

/** Recusas do servidor traduzidas para a tela. */
export const TIER_ERROR_LABELS: Record<string, string> = {
  'tier_round.open_exists': 'Já há uma rodada em revisão. Publique-a ou espere o prazo terminar antes de apurar de novo.',
  'tier_round.not_proposed': 'Esta rodada não está mais em revisão. Atualize a página.',
  'tier_round.review_expired': 'O prazo de revisão desta rodada terminou; continua valendo a classificação anterior.',
  'tier_assignment.self_adjustment': 'Ninguém ajusta o próprio nível. Peça a outra pessoa da supervisão ou da gestão.',
  'tier_assignment.reason_invalid': 'Explique o ajuste em 10 a 2000 caracteres.',
  'tier_settings.matrix_invalid': 'Preencha as 16 células da matriz com A, B, C ou D.',
  'tier_settings.window_invalid': 'A janela de apuração vai de 30 a 365 dias.',
  'tier_settings.quality_gate_invalid': 'O portão de qualidade vai de 0 a 10, com até 2 casas.',
  'tier_settings.review_days_invalid': 'O prazo de revisão vai de 1 a 20 dias úteis.',
};

/** "Q4 · entre os 25% melhores"; nulo = sem amostra. Só a faixa, nunca a posição. */
export function quartileText(q: number | null): string {
  if (q === null) {
    return 'Sem amostra suficiente';
  }
  const band = ['entre os 25% com menor resultado', 'abaixo da mediana', 'acima da mediana', 'entre os 25% melhores'][q - 1];
  return `Q${q} · ${band}`;
}

/** Quartil curto para a tabela ("Q3" ou "—"). */
export function quartileShort(q: number | null): string {
  return q === null ? '—' : `Q${q}`;
}

/** Valida a matriz como o servidor: 16 células, só A–D. */
export function isValidMatrix(matrix: Partial<Record<string, Partial<Record<string, string>>>> | null | undefined): boolean {
  if (!matrix) {
    return false;
  }
  const keys = Object.keys(matrix);
  if (keys.length !== 4 || !QUARTILES_DESC.every((q) => keys.includes(q))) {
    return false;
  }
  return QUARTILES_DESC.every((q) => {
    const row = matrix[q];
    return !!row && Object.keys(row).length === 4 && QUARTILES_DESC.every((e) => TIER_LEVELS.includes(row[e] as TierLevel));
  });
}

/** Erros dos parâmetros (mesmos limites do servidor); vazio = válido. */
export function settingsErrors(s: TierSettings): string[] {
  const errors: string[] = [];
  const int = (v: number, min: number, max: number) => Number.isInteger(v) && v >= min && v <= max;
  if (!int(s.windowDays, 30, 365)) errors.push('A janela de apuração vai de 30 a 365 dias.');
  if (!int(s.minEvaluations, 1, 100)) errors.push('O mínimo de avaliações vai de 1 a 100.');
  if (!int(s.minCases, 1, 10000)) errors.push('O mínimo de casos vai de 1 a 10.000.');
  if (!(s.qualityGate >= 0 && s.qualityGate <= 10) || Math.round(s.qualityGate * 100) !== s.qualityGate * 100) {
    errors.push('O portão de qualidade vai de 0 a 10, com até 2 casas.');
  }
  if (!int(s.reviewBusinessDays, 1, 20)) errors.push('O prazo de revisão vai de 1 a 20 dias úteis.');
  if (!isValidMatrix(s.matrix)) errors.push('Preencha as 16 células da matriz com A, B, C ou D.');
  return errors;
}

/** Explicação do nível em linguagem simples, para a própria pessoa e para quem revisa. */
export function tierExplanation(p: Pick<TierPerson, 'qualityQuartile' | 'efficiencyQuartile' | 'qualityGateFailed' | 'proposedTier'>, defaultTier: TierLevel): string {
  if (p.qualityQuartile === null || p.efficiencyQuartile === null) {
    return `Sem amostra suficiente na janela: vale o nível padrão ${defaultTier}.`;
  }
  const gate = p.qualityGateFailed ? ' A qualidade ficou limitada a Q2 por falha crítica ou nota abaixo do portão.' : '';
  return `Qualidade Q${p.qualityQuartile} × eficiência Q${p.efficiencyQuartile} = nível ${p.proposedTier} pela matriz.${gate}`;
}

/** O que falta para entrar nos rankings (cartão "Seu nível de distribuição"). */
export function missingSampleText(t: MyTier): string[] {
  const out: string[] = [];
  if (t.missingQuality) {
    out.push(`Monitoria: ${t.evaluations} de ${t.minEvaluations} avaliações mínimas na janela.`);
  }
  if (t.missingEfficiency) {
    out.push(`Recuperado × esperado: ${t.cases} de ${t.minCases} casos mínimos nas safras de score da janela.`);
  }
  return out;
}

/** Contagem por nível em texto ("A 2 · B 5 · C 1 · D 0"). */
export function tierCountsText(counts: Record<TierLevel, number> | undefined): string {
  return TIER_LEVELS.map((t) => `${t} ${counts?.[t] ?? 0}`).join(' · ');
}

/** Cópia profunda da matriz (edição sem mexer no original). */
export function cloneMatrix(m: TierMatrix): TierMatrix {
  return Object.fromEntries(QUARTILES_DESC.map((q) => [q, { ...m[q] }])) as TierMatrix;
}
