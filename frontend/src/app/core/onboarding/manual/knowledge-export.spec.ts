import { buildKnowledgeExport, screenAccess } from './knowledge-export';

describe('Base de conhecimento do chat de suporte (exportação do manual)', () => {
  it('acesso de cada tela pelo menu: aberta, item do menu ou contextual com as permissões do item que a contém', () => {
    expect(screenAccess('/')).toEqual({ kind: 'OPEN', permissions: [] });
    expect(screenAccess('/ajuda')).toEqual({ kind: 'OPEN', permissions: [] });
    expect(screenAccess('/cobranca/fila')).toEqual({ kind: 'MENU', permissions: ['CASE_WORK'] });
    expect(screenAccess('/devedores/:id')).toEqual({ kind: 'CONTEXT', permissions: ['DEBTOR_VIEW'] });
    expect(screenAccess(null)).toEqual({ kind: 'CONTEXT', permissions: [] });
  });

  it('exporta todas as telas do manual com caminho, acesso e só dados (nada de HTML)', async () => {
    const kb = await buildKnowledgeExport();
    expect(kb.schemaVersion).toBe(1);
    const queue = kb.screens.find((s) => s.id === 'collection.queue')!;
    expect(queue.path).toBe('/cobranca/fila');
    expect(queue.module).toBe('cobranca');
    expect(queue.objective.length).toBeGreaterThan(10);
    expect(new Set(kb.screens.map((s) => s.id)).size).toBe(kb.screens.length);
    expect(kb.glossary.some((t) => t.id === 'chat-de-suporte')).toBe(true);
    expect(JSON.stringify(kb)).not.toMatch(/<[a-z]/i);
  });

  it('exporta a informação contextual dos indicadores com os mesmos textos das telas', async () => {
    const kb = await buildKnowledgeExport();
    const rate = kb.indicators.find((i) => i.id === 'analytics.recovery.rate')!;
    expect(rate.module).toBe('paineis');
    expect(rate.screens).toContain('analytics.recovery');
    expect(rate.what.length).toBeGreaterThan(20);
    expect(new Set(kb.indicators.map((i) => i.id)).size).toBe(kb.indicators.length);
    const screens = new Set(kb.screens.map((s) => s.id));
    for (const i of kb.indicators) {
      expect(i.module, i.id).not.toBe('');
      for (const s of i.screens) {
        expect(screens.has(s), `${i.id} → ${s}`).toBe(true);
      }
    }
  });
});
