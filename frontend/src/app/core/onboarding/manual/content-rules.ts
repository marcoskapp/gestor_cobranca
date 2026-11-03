/**
 * Regras de texto do conteúdo para o usuário final (manual, tours e informação contextual dos
 * indicadores): os specs de conteúdo usam a mesma lista, para que o "i" de um indicador e o manual
 * da tela sigam o mesmo padrão de linguagem (docs/tecnico/desenho/ux.md §8.5 e §8.6).
 */
export const JARGON: readonly RegExp[] = [
  /<|>/, // nada de HTML
  /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/, // códigos internos (PORTAL_SELF_SERVICE, CASE_WORK…)
  /\b(API|endpoint|HTTP|JSON|SQL|UUID|RLS|backend|frontend|webhook|payload|job|enum|tenant|evento de integração)\b/i,
];

/** Primeira regra de jargão que o texto viola, ou `null`. */
export function jargonIn(text: string): RegExp | null {
  return JARGON.find((pattern) => pattern.test(text)) ?? null;
}
