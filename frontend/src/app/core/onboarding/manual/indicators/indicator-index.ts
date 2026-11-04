import type { ManualModuleId } from '../manual.models';
import { manualLink, moduleOfScreen } from '../manual-index';
import { IndicatorHelp } from './indicator.models';

/**
 * Índice do catálogo de indicadores: o módulo de cada indicador vem do prefixo do id (o mesmo das
 * telas) e o conteúdo de cada módulo é um chunk próprio, baixado só quando alguém abre um "i"
 * daquele módulo (ou na exportação para o chat de suporte). Nada do texto entra no bundle inicial.
 */
const LOADERS: Readonly<Partial<Record<ManualModuleId, () => Promise<readonly IndicatorHelp[]>>>> = {
  geral: () => import('./content/geral').then((m) => m.INDICATORS),
  paineis: () => import('./content/paineis').then((m) => m.INDICATORS),
  score: () => import('./content/score').then((m) => m.INDICATORS),
  'remuneracao-variavel': () => import('./content/remuneracao-variavel').then((m) => m.INDICATORS),
  'prestacao-de-contas': () => import('./content/prestacao-de-contas').then((m) => m.INDICATORS),
  pagamentos: () => import('./content/pagamentos').then((m) => m.INDICATORS),
  negociacao: () => import('./content/negociacao').then((m) => m.INDICATORS),
  importacao: () => import('./content/importacao').then((m) => m.INDICATORS),
  campanhas: () => import('./content/campanhas').then((m) => m.INDICATORS),
  comunicacao: () => import('./content/comunicacao').then((m) => m.INDICATORS),
  telefonia: () => import('./content/telefonia').then((m) => m.INDICATORS),
  assistente: () => import('./content/assistente').then((m) => m.INDICATORS),
  compliance: () => import('./content/compliance').then((m) => m.INDICATORS),
  juridico: () => import('./content/juridico').then((m) => m.INDICATORS),
  // o faturamento da plataforma tem arquivo próprio, juntado aos indicadores da administração
  administracao: () =>
    Promise.all([import('./content/administracao'), import('./content/faturamento-plataforma')]).then(
      ([m, platform]) => [...m.INDICATORS, ...platform.INDICATORS],
    ),
};

/** Módulos que têm indicadores no catálogo. */
export const INDICATOR_MODULES = Object.keys(LOADERS) as ManualModuleId[];

const cache = new Map<ManualModuleId, Promise<readonly IndicatorHelp[]>>();

/** Carrega (uma vez) os indicadores de um módulo. Uma falha de rede não fica guardada no cache. */
export function loadModuleIndicators(module: ManualModuleId): Promise<readonly IndicatorHelp[]> {
  const loader = LOADERS[module];
  if (!loader) {
    return Promise.resolve([]);
  }
  let pending = cache.get(module);
  if (!pending) {
    pending = loader();
    cache.set(module, pending);
    pending.catch(() => cache.delete(module));
  }
  return pending;
}

/** Explicação de um indicador pelo id; `undefined` se o id não existir no catálogo. */
export async function loadIndicator(id: string): Promise<IndicatorHelp | undefined> {
  const module = moduleOfScreen(id);
  if (!module) {
    return undefined;
  }
  const list = await loadModuleIndicators(module);
  return list.find((i) => i.id === id);
}

/** Indicadores que aparecem numa tela (para o manual rápido e o chat), na ordem do catálogo. */
export async function loadScreenIndicators(screenKey: string): Promise<readonly IndicatorHelp[]> {
  const all = await loadAllIndicators();
  return all.filter((i) => i.screens.includes(screenKey));
}

/** Catálogo completo: testes, manual e exportação para o chat de suporte. */
export async function loadAllIndicators(): Promise<readonly IndicatorHelp[]> {
  const lists = await Promise.all(INDICATOR_MODULES.map((m) => loadModuleIndicators(m)));
  return lists.flat();
}

/** "Saiba mais": a seção da tela principal do indicador no manual do módulo. */
export function indicatorManualLink(indicator: IndicatorHelp): { path: string; fragment: string } | null {
  return manualLink(indicator.screens[0]);
}
