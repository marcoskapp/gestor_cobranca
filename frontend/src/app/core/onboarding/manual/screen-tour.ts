import { ScreenGuide, TourDefinition, TourStep } from '../onboarding.models';
import { ScreenManual } from './manual.models';

export const OBJECTIVE_STEP_TITLE = 'Para que serve esta tela';

/** Passo final dos tours montados para telas sem tour próprio: aponta para o manual rápido. */
export const HELP_POINTER_STEP: TourStep = {
  anchor: 'help',
  title: 'Quer saber mais?',
  body: 'Em Ajuda › "Sobre esta tela" você vê quem usa, de onde vêm os dados, telas relacionadas e o manual do módulo.',
};

/**
 * Monta o "tour desta tela": o primeiro passo é sempre o objetivo da tela (centralizado, sem
 * âncora), seguido dos passos do tour próprio da tela. Sem tour próprio, objetivo + onde ler mais,
 * num tour transitório (não oferecido em convite nem gravado).
 *
 * Sem manual (falha ao carregar o conteúdo), devolve o tour próprio como está, para não impedir a
 * ajuda por um problema de rede.
 */
export function composeScreenTour(
  screen: ScreenGuide,
  manual: ScreenManual | undefined,
  authored: TourDefinition | null,
): TourDefinition | null {
  const objective: TourStep | null = manual ? { title: OBJECTIVE_STEP_TITLE, body: manual.objective } : null;
  if (authored) {
    return objective ? { ...authored, steps: [objective, ...authored.steps] } : authored;
  }
  if (!manual || !objective) {
    return null;
  }
  return {
    id: `screen.${screen.key}`,
    version: 1,
    title: manual.title,
    summary: manual.objective,
    steps: [objective, HELP_POINTER_STEP],
    transient: true,
  };
}
