import type { IconName } from '../../../shared/components/icon/icon-names';
import {
  GlossaryTerm,
  MANUAL_SCHEMA_VERSION,
  ManualModuleId,
  ManualModuleInfo,
  ModuleManual,
  ScreenManual,
} from './manual.models';

/**
 * Índice do manual: módulos, de qual módulo é cada tela e os carregadores sob demanda. Este arquivo
 * é pequeno de propósito; o conteúdo de cada módulo e o glossário ficam em chunks próprios
 * (`import()` dinâmico) e só são baixados quando a ajuda ou o tour são abertos.
 */
export const MANUAL_MODULES: readonly (ManualModuleInfo & { icon: IconName })[] = [
  { id: 'geral', title: 'Visão geral', icon: 'signpost', summary: 'Como as partes do sistema se encaixam, a tela inicial, a sua conta e esta ajuda.' },
  { id: 'importacao', title: 'Importação de carteiras', icon: 'file-earmark-arrow-up', summary: 'Receber os arquivos dos credores, conferir antes de gravar e acompanhar cada lote.' },
  { id: 'cobranca', title: 'Cobrança', icon: 'headset', summary: 'Devedores, títulos e casos; filas, estação de atendimento, retornos e contestações.' },
  { id: 'comunicacao', title: 'Comunicação', icon: 'chat-left-text', summary: 'Conversas com os devedores por WhatsApp, e-mail e SMS, templates e canais.' },
  { id: 'campanhas', title: 'Campanhas', icon: 'megaphone', summary: 'Réguas automáticas de contato sobre segmentos de casos, com testes A/B.' },
  { id: 'telefonia', title: 'Telefonia', icon: 'telephone', summary: 'Clique para ligar, discador, presença dos operadores e gravações.' },
  { id: 'assistente', title: 'Assistente de IA', icon: 'robot', summary: 'Como o assistente conversa e negocia dentro das regras, e como a equipe o acompanha.' },
  { id: 'negociacao', title: 'Negociação', icon: 'calculator', summary: 'Políticas do credor, alçadas, simulação, propostas, acordos e o portal do devedor.' },
  { id: 'pagamentos', title: 'Pagamentos', icon: 'cash-stack', summary: 'Pix, pagamentos informados, aprovação, conciliação com o extrato e sobras.' },
  { id: 'prestacao-de-contas', title: 'Prestação de contas', icon: 'file-earmark-ruled', summary: 'Comissão da assessoria, demonstrativo ao credor e repasses.' },
  { id: 'remuneracao-variavel', title: 'Remuneração variável', icon: 'speedometer2', summary: 'Desempenho e comissão dos negociadores, planos, metas, monitoria e folha.' },
  { id: 'score', title: 'Score de propensão', icon: 'diagram-3', summary: 'Chance de pagamento de cada caso, usada para decidir por onde começar.' },
  { id: 'juridico', title: 'Jurídico', icon: 'bank2', summary: 'Casos que saem da cobrança amigável: viabilidade, autorização, medidas e processos.' },
  { id: 'compliance', title: 'Compliance', icon: 'shield-check', summary: 'Quando e como o devedor pode ser contatado e os direitos do titular dos dados.' },
  { id: 'paineis', title: 'Painéis', icon: 'grid-1x2', summary: 'Indicadores da operação, sempre com a base de cálculo, e exportação.' },
  { id: 'administracao', title: 'Administração', icon: 'people', summary: 'Credores, carteiras, usuários, perfis, equipes, configurações e rastreabilidade.' },
];

/** Módulo do manual por prefixo da chave da tela (`collection.queue` → `cobranca`). */
const MODULE_BY_PREFIX: Readonly<Record<string, ManualModuleId>> = {
  app: 'geral',
  account: 'geral',
  help: 'geral',
  ingestion: 'importacao',
  debt: 'cobranca',
  collection: 'cobranca',
  communication: 'comunicacao',
  campaign: 'campanhas',
  telephony: 'telefonia',
  assistant: 'assistente',
  negotiation: 'negociacao',
  portal: 'negociacao',
  payment: 'pagamentos',
  settlement: 'prestacao-de-contas',
  incentive: 'remuneracao-variavel',
  scoring: 'score',
  legal: 'juridico',
  compliance: 'compliance',
  analytics: 'paineis',
  admin: 'administracao',
  organization: 'administracao',
  governance: 'administracao',
  platform: 'administracao',
};

export function moduleOfScreen(screenKey: string): ManualModuleId | undefined {
  return MODULE_BY_PREFIX[screenKey.split('.')[0]];
}

export function findModuleInfo(id: string): (ManualModuleInfo & { icon: IconName }) | undefined {
  return MANUAL_MODULES.find((m) => m.id === id);
}

/** Âncora da tela no manual do módulo (`/ajuda/cobranca#collection.queue`). */
export function manualLink(screenKey: string): { path: string; fragment: string } | null {
  const module = moduleOfScreen(screenKey);
  return module ? { path: `/ajuda/${module}`, fragment: screenKey } : null;
}

const LOADERS: Readonly<Record<ManualModuleId, () => Promise<ModuleManual>>> = {
  geral: () => import('./content/geral').then((m) => m.MANUAL),
  importacao: () => import('./content/importacao').then((m) => m.MANUAL),
  cobranca: () => import('./content/cobranca').then((m) => m.MANUAL),
  comunicacao: () => import('./content/comunicacao').then((m) => m.MANUAL),
  campanhas: () => import('./content/campanhas').then((m) => m.MANUAL),
  telefonia: () => import('./content/telefonia').then((m) => m.MANUAL),
  assistente: () => import('./content/assistente').then((m) => m.MANUAL),
  negociacao: () => import('./content/negociacao').then((m) => m.MANUAL),
  pagamentos: () => import('./content/pagamentos').then((m) => m.MANUAL),
  'prestacao-de-contas': () => import('./content/prestacao-de-contas').then((m) => m.MANUAL),
  'remuneracao-variavel': () => import('./content/remuneracao-variavel').then((m) => m.MANUAL),
  score: () => import('./content/score').then((m) => m.MANUAL),
  juridico: () => import('./content/juridico').then((m) => m.MANUAL),
  compliance: () => import('./content/compliance').then((m) => m.MANUAL),
  paineis: () => import('./content/paineis').then((m) => m.MANUAL),
  administracao: () => import('./content/administracao').then((m) => m.MANUAL),
};

const moduleCache = new Map<ManualModuleId, Promise<ModuleManual>>();
let glossaryCache: Promise<readonly GlossaryTerm[]> | null = null;

/** Carrega (uma vez) o manual de um módulo. Uma falha de rede não fica guardada no cache. */
export function loadModuleManual(id: ManualModuleId): Promise<ModuleManual> {
  let pending = moduleCache.get(id);
  if (!pending) {
    pending = LOADERS[id]();
    moduleCache.set(id, pending);
    pending.catch(() => moduleCache.delete(id));
  }
  return pending;
}

export async function loadScreenManual(screenKey: string): Promise<ScreenManual | undefined> {
  const module = moduleOfScreen(screenKey);
  if (!module) {
    return undefined;
  }
  const manual = await loadModuleManual(module);
  return manual.screens.find((s) => s.id === screenKey);
}

export function loadGlossary(): Promise<readonly GlossaryTerm[]> {
  if (!glossaryCache) {
    const pending = import('./content/glossario').then((m) => m.GLOSSARY);
    glossaryCache = pending;
    pending.catch(() => (glossaryCache = null));
  }
  return glossaryCache;
}

/** Base de conhecimento completa (todos os módulos e o glossário), para o futuro chat de suporte e os testes. */
export interface ManualKnowledgeBase {
  schemaVersion: number;
  modules: readonly (ManualModuleInfo & { manual: ModuleManual })[];
  glossary: readonly GlossaryTerm[];
}

export async function loadKnowledgeBase(): Promise<ManualKnowledgeBase> {
  const [modules, glossary] = await Promise.all([
    Promise.all(MANUAL_MODULES.map(async ({ id, title, summary }) => ({ id, title, summary, manual: await loadModuleManual(id) }))),
    loadGlossary(),
  ]);
  return { schemaVersion: MANUAL_SCHEMA_VERSION, modules, glossary };
}
