import { NAVIGATION } from '../../layout/navigation';
import { SCREENS, findScreen } from '../content/screens';
import { findTour } from '../content/tours';
import { WELCOME_TOUR } from '../onboarding.service';
import { MANUAL_MODULES, loadKnowledgeBase, manualLink, moduleOfScreen } from './manual-index';
import { MANUAL_SCHEMA_VERSION, ScreenManual } from './manual.models';
import { HELP_POINTER_STEP, composeScreenTour } from './screen-tour';

/**
 * Cobertura e qualidade do manual (contrato em docs/tecnico/desenho/ux.md §8.5): toda tela
 * registrada e toda rota do menu têm resumo do tour e manual rápido; vínculos e termos existem; o
 * texto é para o usuário final.
 */
describe('conteúdo do manual', () => {
  const words = (text: string) => text.trim().split(/\s+/).length;

  /** Jargão técnico que não deve aparecer no texto do usuário final. */
  const JARGON = [
    /<|>/, // nada de HTML
    /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/, // códigos internos (PORTAL_SELF_SERVICE, CASE_WORK…)
    /\b(API|endpoint|HTTP|JSON|SQL|UUID|RLS|backend|frontend|webhook|payload|job|enum|tenant|evento de integração)\b/i,
  ];

  async function knowledgeBase() {
    return loadKnowledgeBase();
  }

  function allTexts(screen: ScreenManual): string[] {
    return [screen.title, screen.objective, ...screen.purpose, ...screen.inputs, ...screen.outputs, ...screen.related.map((r) => r.why)];
  }

  it('cobre os 15 módulos pedidos, mais a visão geral, com identificadores estáveis', () => {
    const ids = MANUAL_MODULES.map((m) => m.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(
      expect.arrayContaining([
        'cobranca',
        'negociacao',
        'pagamentos',
        'prestacao-de-contas',
        'remuneracao-variavel',
        'score',
        'assistente',
        'campanhas',
        'comunicacao',
        'telefonia',
        'compliance',
        'juridico',
        'importacao',
        'paineis',
        'administracao',
        'geral',
      ]),
    );
    for (const id of ids) {
      expect(id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    }
  });

  it('toda rota do menu corresponde a uma tela registrada', () => {
    for (const item of NAVIGATION.flatMap((s) => s.items)) {
      expect(findScreen(item.route), item.route).toBeDefined();
    }
  });

  it('toda tela registrada tem módulo e manual rápido; não há manual de tela inexistente', async () => {
    const kb = await knowledgeBase();
    expect(kb.schemaVersion).toBe(MANUAL_SCHEMA_VERSION);
    const manuals = kb.modules.flatMap((m) => m.manual.screens.map((s) => ({ module: m.id, screen: s })));
    const ids = manuals.map((m) => m.screen.id);
    expect(new Set(ids).size, 'telas repetidas no manual').toBe(ids.length);

    const missing = SCREENS.filter((s) => !ids.includes(s.key)).map((s) => s.key);
    expect(missing, 'telas sem manual').toEqual([]);
    const orphans = ids.filter((id) => !SCREENS.some((s) => s.key === id));
    expect(orphans, 'manual de tela não registrada').toEqual([]);

    for (const { module, screen } of manuals) {
      expect(moduleOfScreen(screen.id), `${screen.id} no módulo errado`).toBe(module);
      expect(manualLink(screen.id)).toEqual({ path: `/ajuda/${module}`, fragment: screen.id });
    }
    for (const m of kb.modules) {
      expect(m.manual.id).toBe(m.id);
    }
  });

  it('cada manual rápido é completo e curto', async () => {
    const kb = await knowledgeBase();
    const glossary = new Set(kb.glossary.map((t) => t.id));
    const keys = new Set(SCREENS.map((s) => s.key));
    for (const screen of kb.modules.flatMap((m) => m.manual.screens)) {
      const id = screen.id;
      expect(screen.title.length, id).toBeGreaterThan(0);
      expect(words(screen.objective), `${id}: objetivo com até 25 palavras`).toBeLessThanOrEqual(25);
      expect(words(screen.objective), id).toBeGreaterThanOrEqual(6);
      expect(screen.purpose.length, `${id}: para que serve`).toBeGreaterThanOrEqual(1);
      expect(screen.purpose.length, id).toBeLessThanOrEqual(3);
      expect(screen.profiles.length, `${id}: quem usa`).toBeGreaterThanOrEqual(1);
      expect(screen.inputs.length, `${id}: de onde vêm os dados`).toBeGreaterThanOrEqual(1);
      expect(screen.outputs.length, `${id}: para onde vai o resultado`).toBeGreaterThanOrEqual(1);
      expect(screen.related.length, `${id}: telas relacionadas`).toBeGreaterThanOrEqual(1);
      expect(screen.terms.length, `${id}: termos`).toBeGreaterThanOrEqual(1);
      for (const r of screen.related) {
        expect(keys.has(r.screen), `${id} → ${r.screen}`).toBe(true);
        expect(r.screen, `${id} relacionada a si mesma`).not.toBe(id);
        expect(r.why.length, `${id} → ${r.screen}: motivo`).toBeGreaterThan(0);
      }
      for (const term of screen.terms) {
        expect(glossary.has(term), `${id}: termo ${term}`).toBe(true);
      }
    }
  });

  it('cada módulo tem visão geral, encaixe, regras e termos válidos', async () => {
    const kb = await knowledgeBase();
    const modules = new Set(kb.modules.map((m) => m.id));
    const glossary = new Set(kb.glossary.map((t) => t.id));
    for (const { id, manual } of kb.modules) {
      expect(manual.overview.length, id).toBeGreaterThanOrEqual(2);
      expect(manual.connections.length, id).toBeGreaterThanOrEqual(1);
      expect(manual.rules.length, id).toBeGreaterThanOrEqual(1);
      expect(manual.screens.length, id).toBeGreaterThanOrEqual(1);
      for (const c of manual.connections) {
        expect(modules.has(c.module), `${id} → ${c.module}`).toBe(true);
        expect(c.module, `${id} ligado a si mesmo`).not.toBe(id);
      }
      for (const term of manual.terms) {
        expect(glossary.has(term), `${id}: termo ${term}`).toBe(true);
      }
    }
    // a visão geral liga todos os outros módulos (o "todo sistêmico")
    const geral = kb.modules.find((m) => m.id === 'geral')!.manual;
    expect(new Set(geral.connections.map((c) => c.module))).toEqual(new Set([...modules].filter((m) => m !== 'geral')));
  });

  it('glossário sem repetição, com identificadores estáveis', async () => {
    const { glossary } = await knowledgeBase();
    const ids = glossary.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const term of glossary) {
      expect(term.id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/);
      expect(term.definition.length, term.id).toBeGreaterThan(10);
    }
  });

  it('texto para o usuário final: sem HTML, códigos internos ou jargão técnico', async () => {
    const kb = await knowledgeBase();
    const texts: [string, string][] = [];
    for (const { id, manual } of kb.modules) {
      for (const t of [...manual.overview, ...manual.rules, ...manual.connections.map((c) => c.text)]) {
        texts.push([id, t]);
      }
      for (const s of manual.screens) {
        for (const t of allTexts(s)) {
          texts.push([s.id, t]);
        }
      }
    }
    for (const t of kb.glossary) {
      texts.push([t.id, `${t.term} ${t.definition}`]);
    }
    for (const [where, text] of texts) {
      for (const pattern of JARGON) {
        expect(pattern.test(text), `${where}: "${text}" contém ${pattern}`).toBe(false);
      }
    }
  });

  it('o tour de toda tela começa pelo objetivo, centralizado, e continua curto', async () => {
    const kb = await knowledgeBase();
    const manuals = new Map(kb.modules.flatMap((m) => m.manual.screens).map((s) => [s.id, s]));
    for (const screen of SCREENS) {
      const authored = screen.tourId ? (findTour(screen.tourId) ?? null) : screen.key === 'app.home' ? (findTour(WELCOME_TOUR) ?? null) : null;
      const tour = composeScreenTour(screen, manuals.get(screen.key), authored);
      expect(tour, screen.key).not.toBeNull();
      expect(tour!.steps[0].anchor, `${screen.key}: primeiro passo centralizado`).toBeUndefined();
      expect(tour!.steps[0].body).toBe(manuals.get(screen.key)!.objective);
      expect(tour!.steps.length, `${screen.key}: até 6 passos do tour + o objetivo`).toBeLessThanOrEqual(7);
      if (authored) {
        expect(tour!.id).toBe(authored.id);
        expect(tour!.transient).toBeFalsy();
      } else {
        expect(tour!.transient, screen.key).toBe(true);
        expect(tour!.steps.at(-1)).toEqual(HELP_POINTER_STEP);
      }
    }
  });

  it('sem manual carregado, o tour próprio segue como está', () => {
    const screen = findScreen('/admin/usuarios')!;
    const authored = findTour('admin.users')!;
    expect(composeScreenTour(screen, undefined, authored)).toBe(authored);
    expect(composeScreenTour(findScreen('/admin/unidades')!, undefined, null)).toBeNull();
  });
});
