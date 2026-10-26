import { NAVIGATION } from '../../layout/navigation';
import { SCREENS } from '../content/screens';
import { MANUAL_SCHEMA_VERSION, PROFILE_LABELS } from './manual.models';
import { OPEN_ROUTES, loadKnowledgeBase, moduleOfScreen } from './manual-index';

/**
 * Base de conhecimento do chat de suporte, gerada a partir do manual (fonte única) por
 * `npm run knowledge` e gravada em `backend/src/main/resources/helpdesk/knowledge-base.json`
 * (docs/tecnico/desenho/chat-suporte.md §4). O backend só lê esse arquivo: o navegador envia a chave da
 * tela e nunca o texto do manual, então não consegue injetar instruções no prompt de sistema.
 *
 * Acesso de cada tela (para a IA ensinar só o que a pessoa pode fazer):
 * - `OPEN`: qualquer usuário autenticado;
 * - `MENU`: item do menu, visível com ao menos uma das `permissions`;
 * - `CONTEXT`: tela que se abre a partir de outra (rota com identificador); `permissions` = as do item do
 *   menu que a contém, quando há.
 */
export interface KnowledgeScreenAccess {
  kind: 'OPEN' | 'MENU' | 'CONTEXT';
  permissions: readonly string[];
}

export interface KnowledgeExport {
  schemaVersion: number;
  profiles: Readonly<Record<string, string>>;
  modules: readonly {
    id: string;
    title: string;
    summary: string;
    overview: readonly string[];
    connections: readonly { module: string; text: string }[];
    rules: readonly string[];
    terms: readonly string[];
  }[];
  screens: readonly {
    id: string;
    module: string;
    title: string;
    objective: string;
    purpose: readonly string[];
    profiles: readonly string[];
    inputs: readonly string[];
    outputs: readonly string[];
    related: readonly { screen: string; why: string }[];
    terms: readonly string[];
    path: string | null;
    access: KnowledgeScreenAccess;
  }[];
  glossary: readonly { id: string; term: string; definition: string }[];
}

const MENU_ITEMS = NAVIGATION.flatMap((s) => s.items);

export function screenAccess(path: string | null): KnowledgeScreenAccess {
  if (path && OPEN_ROUTES.has(path)) {
    return { kind: 'OPEN', permissions: [] };
  }
  const item = path ? MENU_ITEMS.find((i) => i.route === path) : undefined;
  if (item) {
    return item.permissions.length ? { kind: 'MENU', permissions: [...item.permissions] } : { kind: 'OPEN', permissions: [] };
  }
  // tela aberta a partir de outra: as permissões do item do menu de prefixo mais longo
  const parent = path
    ? MENU_ITEMS.filter((i) => i.route !== '/' && path.startsWith(i.route + '/')).sort((a, b) => b.route.length - a.route.length)[0]
    : undefined;
  return { kind: 'CONTEXT', permissions: parent ? [...parent.permissions] : [] };
}

export async function buildKnowledgeExport(): Promise<KnowledgeExport> {
  const kb = await loadKnowledgeBase();
  const paths = new Map(SCREENS.map((s) => [s.key, s.path]));
  return {
    schemaVersion: MANUAL_SCHEMA_VERSION,
    profiles: PROFILE_LABELS,
    modules: kb.modules.map((m) => ({
      id: m.id,
      title: m.title,
      summary: m.summary,
      overview: m.manual.overview,
      connections: m.manual.connections.map((c) => ({ module: c.module, text: c.text })),
      rules: m.manual.rules,
      terms: m.manual.terms,
    })),
    screens: kb.modules.flatMap((m) =>
      m.manual.screens.map((s) => {
        const path = paths.get(s.id) ?? null;
        return {
          id: s.id,
          module: moduleOfScreen(s.id) ?? m.id,
          title: s.title,
          objective: s.objective,
          purpose: s.purpose,
          profiles: s.profiles,
          inputs: s.inputs,
          outputs: s.outputs,
          related: s.related.map((r) => ({ screen: r.screen, why: r.why })),
          terms: s.terms,
          path,
          access: screenAccess(path),
        };
      }),
    ),
    glossary: kb.glossary.map((g) => ({ id: g.id, term: g.term, definition: g.definition })),
  };
}
